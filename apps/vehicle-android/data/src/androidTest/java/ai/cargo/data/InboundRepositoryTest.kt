package ai.cargo.data

import ai.cargo.contracts.ItemDraft
import ai.cargo.data.Seed.ITEM_A
import ai.cargo.data.Seed.SCENE
import ai.cargo.data.Seed.T
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Storage contract only. Relay transport and user screens belong to their own owners. */
@RunWith(AndroidJUnit4::class)
class InboundRepositoryTest : StoreTestBase() {
    @After fun cleanup() = removeCreated()

    private val pairing = Seed.uuid(900)

    private fun draft(
        id: Int = 901,
        clientRevision: Long = 1,
        base: Long? = 1,
        x: Long = 300,
    ) = ItemDraft(
        draftId = Seed.uuid(id), pairingId = pairing,
        baseItemId = if (base != null) ITEM_A else null, baseItemRevision = base,
        schemaVersion = 1, payload = Seed.item(ITEM_A, revision = 1, x = x), clientRevision = clientRevision,
    )

    private fun store(): CargoStore = Seed.open(context, databaseName("inbound")).also {
        Seed.firstRevision(it)
        it.inbound.createPairing(pairing, "h".repeat(64), 1000, 5000)
    }

    @Test fun replay_is_idempotent_and_changed_payload_with_same_key_is_refused() {
        val store = store()
        val first = draft()
        assertIs<ReceiveResult.Stored>(store.inbound.receive(first, 1500))
        assertIs<ReceiveResult.Duplicate>(store.inbound.receive(first, 1600))
        assertIs<ReceiveResult.PayloadMismatch>(store.inbound.receive(first.copy(payload = Seed.item(ITEM_A, x = 999)), 1600))
        assertEquals(1, store.inbound.pending().size)
        store.close()
    }

    @Test fun accept_writes_version_and_ack_once_without_touching_scenes() {
        val store = store()
        val d = draft()
        store.inbound.receive(d, 1500)
        val key = InboundKey(pairing, d.draftId, 1)
        assertEquals(AcceptResult.Accepted(ITEM_A, 2), store.inbound.decide(key, InboundDecision.ApplyVersion, 1600, T))
        assertEquals(AcceptResult.AlreadyDecided(InboundStatus.ACCEPTED, ITEM_A, 2), store.inbound.decide(key, InboundDecision.ApplyVersion, 1700, T))
        assertIs<ReceiveResult.Duplicate>(store.inbound.receive(d, 1800))
        assertEquals(2L, store.inbound.unsentAcks().single().acceptedItemRevision)
        store.inbound.markAckSent(key, 1900)
        assertTrue(store.inbound.unsentAcks().isEmpty())
        assertEquals(1, store.scenes.revision(SCENE, 1)!!.instances.single().itemRevision)
        assertEquals(1, assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T)).revision.instances.single().itemRevision)
        store.close()
    }

    @Test fun both_sides_edited_requires_a_user_choice_and_never_overwrites_silently() {
        val store = store()
        store.library.saveItemVersion(Seed.item(ITEM_A, revision = 2, x = 250))
        val d = draft(base = 1)
        store.inbound.receive(d, 1500)
        val key = InboundKey(pairing, d.draftId, 1)
        assertEquals(AcceptResult.ChoiceRequired(1, 2), store.inbound.decide(key, InboundDecision.ApplyVersion, 1600, T))
        assertEquals(250, store.library.item(ITEM_A, 2, T)!!.measuredDimensionsMm.x)
        assertEquals(1, store.inbound.pending().size)

        assertEquals(AcceptResult.Kept, store.inbound.decide(key, InboundDecision.KeepExisting, 1700, T))
        assertEquals(null, store.library.item(ITEM_A, 3, T))

        val second = draft(id = 902)
        store.inbound.receive(second, 1800)
        val separate = Seed.uuid(950)
        assertEquals(AcceptResult.Accepted(separate, 1), store.inbound.decide(InboundKey(pairing, second.draftId, 1), InboundDecision.SeparateItem(separate), 1900, T))

        val third = draft(id = 903)
        store.inbound.receive(third, 1950)
        assertEquals(AcceptResult.Accepted(ITEM_A, 3), store.inbound.decide(InboundKey(pairing, third.draftId, 1), InboundDecision.OverwriteAfterConflictShown, 2000, T))
        store.close()
    }

    @Test fun expired_or_revoked_pairing_refuses_new_drafts_and_acceptance() {
        val store = store()
        val pendingDraft = draft()
        store.inbound.receive(pendingDraft, 1500)
        assertEquals(PairingStatus.EXPIRED, store.inbound.pairingStatus(pairing, 5000))
        assertEquals(ReceiveResult.PairingInactive(pairing, PairingStatus.EXPIRED), store.inbound.receive(draft(id = 905), 5000))
        store.inbound.revokePairing(pairing, 2000)
        assertEquals(ReceiveResult.PairingInactive(pairing, PairingStatus.REVOKED), store.inbound.receive(draft(id = 906), 2100))
        assertEquals(AcceptResult.PairingInactive(pairing, PairingStatus.REVOKED), store.inbound.decide(InboundKey(pairing, pendingDraft.draftId, 1), InboundDecision.ApplyVersion, 2200, T))
        assertEquals(1, store.inbound.pending().size)
        store.close()
    }

    @Test fun invalid_payload_and_unknown_pairing_are_refused() {
        val store = store()
        assertIs<ReceiveResult.Invalid>(store.inbound.receive(draft().copy(draftId = "not-a-uuid"), 1500))
        assertEquals(ReceiveResult.PairingInactive(Seed.uuid(999), "UNKNOWN"), store.inbound.receive(draft().copy(pairingId = Seed.uuid(999)), 1500))
        store.close()
    }
}
