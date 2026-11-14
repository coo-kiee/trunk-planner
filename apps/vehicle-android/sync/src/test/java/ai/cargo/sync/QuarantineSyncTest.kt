package ai.cargo.sync

import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundKey
import ai.cargo.data.InboundStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** CE-18-02: a key is bound to the content first seen. The relay must not be told a different content was accepted. Fixture grade. */
class QuarantineSyncTest {
    private val rig = Rig()
    private val original = draft()
    private val changed = draft(x = 900)
    private val key = keyOf(original)

    private fun received(): SyncEngine {
        val engine = rig.paired()
        rig.relay.submit(original, hashOf(1))
        engine.poll()
        return engine
    }

    private fun swapContent(engine: SyncEngine) {
        rig.relay.submit(changed, hashOf(2))
        assertTrue(engine.poll().warnings.any { it.startsWith("PAYLOAD_MISMATCH") })
    }

    @Test fun ce18_changed_hash_quarantines_blocks_the_decision_and_sends_nothing() {
        val engine = received()
        swapContent(engine)
        assertIs<DecisionResult.Quarantined>(engine.decide(key, InboundDecision.ApplyVersion))
        assertTrue(rig.relay.acks.isEmpty(), "sent ${rig.relay.acks}")
        assertEquals(InboundStatus.PENDING, rig.inbound.rows[key]!!.status)
        assertEquals(1L, rig.inbound.headRevision)
    }

    @Test fun ce18_first_hash_stays_bound_and_the_replacement_is_never_stored() {
        val engine = received()
        swapContent(engine)
        assertEquals(hashOf(1), SyncJournal(rig.dir.resolve("journal.json")).entry(key)!!.relayPayloadHash)
        assertEquals(300, rig.inbound.rows[key]!!.draft.payload.measuredDimensionsMm.x)
        assertEquals(listOf(key), engine.quarantined())
        assertTrue(engine.inbox().single().quarantined, "stays visible rather than vanishing")
    }

    @Test fun ce18_quarantine_survives_restart_and_later_polls_and_a_return_to_the_old_content() {
        val engine = received()
        swapContent(engine)
        val restarted = rig.engine()
        assertEquals(listOf(key), restarted.quarantined())
        assertIs<DecisionResult.Quarantined>(restarted.decide(key, InboundDecision.KeepExisting))
        rig.relay.submit(original, hashOf(1))
        assertTrue(restarted.poll().warnings.any { it.startsWith("PAYLOAD_MISMATCH") }, "a key once changed is not trusted again")
        assertIs<DecisionResult.Quarantined>(restarted.decide(key, InboundDecision.ApplyVersion))
        assertTrue(rig.relay.acks.isEmpty())
    }

    @Test fun ce18_same_hash_with_other_content_quarantines_through_room() {
        val engine = received()
        rig.relay.submit(changed, hashOf(1))
        assertTrue(engine.poll().warnings.any { it.startsWith("PAYLOAD_MISMATCH") })
        assertIs<DecisionResult.Quarantined>(engine.decide(key, InboundDecision.ApplyVersion))
        assertTrue(rig.relay.acks.isEmpty())
    }

    @Test fun ce18_unsent_ack_of_an_earlier_decision_is_ended_not_sent_and_room_is_kept() {
        val engine = received()
        rig.relay.down = true
        assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion))
        rig.relay.down = false
        // The ack of this decision goes out first in a poll, with the bound hash, so the relay's own hash check refuses it.
        // Hold it back once with a 503 to reach the path where the mismatch is seen while the ack is still owed.
        val restarted = rig.engine()
        rig.relay.submit(changed, hashOf(2))
        rig.relay.failNext.add(HttpResponse(503, emptyMap(), """{"error":{"code":"DATABASE_UNAVAILABLE"}}""".toByteArray()))
        restarted.poll()
        assertEquals(listOf(key), restarted.quarantined())
        assertTrue(rig.relay.acks.none { hashOf(2) in it }, "hash2 must never be reported: ${rig.relay.acks}")
        assertEquals(AckDelivery.REJECTED, restarted.deliveryOf(key))
        assertEquals(InboundStatus.ACCEPTED, rig.inbound.rows[key]!!.status)
        assertTrue(rig.inbound.unsentAcks().isEmpty())
    }

    @Test fun ce18_lost_journal_recovery_refuses_a_listing_with_other_content() {
        val engine = received()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        rig.relay.submit(changed, hashOf(2))
        rig.dir.resolve("journal.json").delete()
        val restarted = rig.engine()
        restarted.flushAcks()
        assertTrue(rig.relay.acks.isEmpty(), "sent ${rig.relay.acks}")
        assertEquals(AckDelivery.REJECTED, restarted.deliveryOf(key))
        assertEquals(listOf(key), restarted.quarantined())
    }

    @Test fun ce18_lost_journal_with_pending_room_row_detects_the_change_through_room() {
        received()
        rig.relay.submit(changed, hashOf(2))
        rig.dir.resolve("journal.json").delete()
        val restarted = rig.engine()
        assertTrue(restarted.poll().warnings.any { it.startsWith("PAYLOAD_MISMATCH") })
        assertIs<DecisionResult.Quarantined>(restarted.decide(key, InboundDecision.ApplyVersion))
        assertTrue(rig.relay.acks.isEmpty())
    }

    @Test fun ce18_invalid_replacement_of_a_stored_draft_is_quarantined_not_validation_failed() {
        val engine = received()
        rig.relay.submit(draft(x = -5), hashOf(2))
        assertTrue(engine.poll().warnings.any { it.startsWith("PAYLOAD_MISMATCH") })
        assertTrue(rig.relay.acks.isEmpty(), "sent ${rig.relay.acks}")
        assertEquals(InboundStatus.PENDING, rig.inbound.rows[key]!!.status)
    }

    @Test fun ce18_invalid_replacement_with_the_same_hash_and_lost_journal_is_also_quarantined() {
        val engine = received()
        assertFalse(engine.inbox().single().quarantined)
        rig.relay.submit(draft(x = -5), hashOf(1))
        rig.dir.resolve("journal.json").delete()
        val restarted = rig.engine()
        assertTrue(restarted.poll().warnings.any { it.startsWith("PAYLOAD_MISMATCH") })
        assertTrue(rig.relay.acks.isEmpty(), "sent ${rig.relay.acks}")
    }

    @Test fun ce18_valid_replacement_of_an_invalid_draft_is_quarantined_and_not_stored() {
        val engine = rig.paired()
        val bad = draft(n = 950, x = -5)
        rig.relay.submit(bad, hashOf(3))
        val invalidKey = InboundKey(PAIRING, bad.draftId, 1)
        engine.poll()
        assertEquals("REJECTED", rig.relay.drafts[invalidKey]!!.state)
        rig.relay.submit(draft(n = 950, x = 300), hashOf(4))
        assertTrue(engine.poll().warnings.any { it.startsWith("PAYLOAD_MISMATCH") })
        assertTrue(rig.inbound.rows.isEmpty())
    }

    @Test fun ce18_a_new_client_revision_is_a_new_key_and_is_not_quarantined() {
        val engine = received()
        val next = draft(rev = 2, x = 900)
        rig.relay.drafts[key]!!.state = "SUPERSEDED"
        rig.relay.submit(next, hashOf(9))
        assertEquals(1, engine.poll().received)
        assertTrue(engine.quarantined().isEmpty())
        assertIs<DecisionResult.Accepted>(engine.decide(keyOf(next), InboundDecision.ApplyVersion))
        assertTrue(hashOf(9) in rig.relay.acks.single())
    }
}
