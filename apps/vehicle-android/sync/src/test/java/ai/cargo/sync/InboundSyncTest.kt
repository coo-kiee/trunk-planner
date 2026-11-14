package ai.cargo.sync

import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundKey
import ai.cargo.data.InboundStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CE-18-02: duplicates and replays apply once. Every assertion here is fixture grade. */
class InboundSyncTest {
    private val rig = Rig()
    private val d = draft()
    private val key = keyOf(d)

    @Test fun ce18_list_replay_stores_one_row() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        assertEquals(1, engine.poll().received)
        assertEquals(0, engine.poll().received)
        assertEquals(1, engine.inbox().size)
        assertTrue(rig.inbound.decisions.isEmpty(), "poll must never decide")
    }

    @Test fun ce18_nothing_is_applied_without_an_explicit_command() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        repeat(3) { engine.poll() }
        assertEquals(InboundStatus.PENDING, rig.inbound.rows[key]!!.status)
        assertEquals(1L, rig.inbound.headRevision)
        assertTrue(rig.relay.acks.isEmpty())
    }

    @Test fun ce18_decide_twice_writes_once_and_acks_once() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        val first = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion))
        assertEquals(2, first.revision)
        assertEquals(AckDelivery.DELIVERED, first.delivery)
        assertIs<DecisionResult.AlreadyDecided>(engine.decide(key, InboundDecision.ApplyVersion))
        assertEquals(1, rig.relay.acks.size)
        assertEquals(2L, rig.inbound.headRevision)
    }

    @Test fun ce18_ack_carries_relay_hash_and_accepted_revision() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(7))
        engine.poll()
        engine.decide(key, InboundDecision.ApplyVersion)
        val ack = rig.relay.acks.single()
        assertTrue(hashOf(7) in ack && "\"acceptedRevision\":2" in ack && "VEHICLE_ACCEPTED" in ack, ack)
    }

    @Test fun ce18_changed_payload_with_same_key_warns_and_sends_no_ack() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.submit(d.copy(payload = item(x = 999)), hashOf(1))
        val warnings = engine.poll().warnings
        assertTrue(warnings.any { it.startsWith("PAYLOAD_MISMATCH") }, warnings.toString())
        assertTrue(rig.relay.acks.isEmpty())
        assertEquals(300, rig.inbound.rows[key]!!.draft.payload.measuredDimensionsMm.x)
    }

    @Test fun ce18_schema_invalid_draft_is_rejected_with_validation_failed_and_never_stored() {
        val engine = rig.paired()
        rig.relay.submit(draft(n = 902, x = -5), hashOf(2))
        engine.poll()
        assertTrue(rig.inbound.rows.isEmpty())
        assertTrue("VALIDATION_FAILED" in rig.relay.acks.single())
    }

    @Test fun ce18_envelope_key_disagreeing_with_payload_is_invalid() {
        val engine = rig.paired()
        val mismatch = draft(n = 903)
        rig.relay.drafts[InboundKey(PAIRING, uuid(904), 1)] = FakeRelayHttp.Stored(mismatch, hashOf(3))
        engine.poll()
        assertTrue(rig.inbound.rows.isEmpty())
    }

    @Test fun ce18_revoked_after_commit_stops_retry_and_keeps_room() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.revoked = true
        val result = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion))
        assertEquals(AckDelivery.UNDELIVERED_REVOKED, result.delivery)
        assertEquals(InboundStatus.ACCEPTED, rig.inbound.rows[key]!!.status)
        assertTrue(rig.inbound.unsentAcks().isEmpty())
        val before = rig.relay.requests.size
        engine.flushAcks()
        assertEquals(before, rig.relay.requests.size, "no endless retry")
    }

    @Test fun ce18_credential_expired_and_draft_expired_are_distinct_from_revoked() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.credentialExpired = true
        assertEquals(AckDelivery.UNDELIVERED_EXPIRED, assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion)).delivery)
        val other = Rig()
        val second = other.paired()
        other.relay.submit(d, hashOf(1))
        second.poll()
        other.relay.failNext.add(other.relay.error(410, "DRAFT_EXPIRED"))
        assertEquals(AckDelivery.UNDELIVERED_EXPIRED, assertIs<DecisionResult.Accepted>(second.decide(key, InboundDecision.ApplyVersion)).delivery)
    }

    @Test fun ce18_hash_mismatch_ends_retry_as_rejected_without_rollback() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.failNext.add(rig.relay.error(409, "PAYLOAD_HASH_MISMATCH"))
        assertEquals(AckDelivery.REJECTED, assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion)).delivery)
        assertEquals(InboundStatus.ACCEPTED, rig.inbound.rows[key]!!.status)
    }

    @Test fun ce18_mark_sent_alone_is_never_reported_as_delivered() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        assertNull(engine.deliveryOf(key))
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        assertNull(engine.deliveryOf(key), "undelivered must stay unknown, not success")
        rig.inbound.markAckSent(key, T0)
        assertNull(engine.deliveryOf(key))
    }

    @Test fun ce18_revoke_is_immediate_locally_and_blocks_decisions_and_new_receipts() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        assertIs<SyncStatus.Revoked>(engine.revoke())
        assertIs<DecisionResult.PairingInactive>(engine.decide(key, InboundDecision.ApplyVersion))
        assertIs<CredentialLoad.None>(rig.credentialStore.load())
        assertEquals(SyncStatus.NotPaired, engine.poll().status)
    }

    @Test fun ce18_revoke_offline_keeps_retrying_and_blocks_a_new_qr_until_settled() {
        val engine = rig.paired()
        rig.relay.down = true
        assertIs<SyncStatus.RevokePending>(engine.revoke())
        assertIs<PairingStart.PriorRevokePending>(engine.startPairing())
        assertIs<DecisionResult.NotFound>(engine.decide(key, InboundDecision.ApplyVersion))
        val restarted = rig.engine()
        assertIs<SyncStatus.RevokePending>(restarted.poll().status)
        rig.relay.down = false
        assertIs<SyncStatus.Revoked>(restarted.poll().status)
        assertTrue(rig.relay.revoked)
        assertIs<CredentialLoad.None>(rig.credentialStore.load())
    }

    @Test fun ce18_new_pairing_revokes_the_previous_one_first() {
        val engine = rig.paired()
        val paths = { rig.relay.requests.map { it.url.substringAfter(".example").substringBefore('?') }.filter { "drafts" !in it && !it.endsWith("/pairing") } }
        rig.relay.revoked = false
        engine.startPairing()
        assertEquals(listOf("/vehicle/v1/pairings", "/vehicle/v1/pairing/revoke", "/vehicle/v1/pairings"), paths())
    }

    @Test fun ce18_superseded_revision_is_hidden_and_cannot_be_decided() {
        val engine = rig.paired()
        val old = draft(rev = 1)
        val new = draft(rev = 2, x = 350)
        rig.relay.submit(old, hashOf(1))
        engine.poll()
        rig.relay.drafts[keyOf(old)]!!.state = "SUPERSEDED"
        rig.relay.submit(new, hashOf(2))
        engine.poll()
        assertEquals(listOf(keyOf(new)), engine.inbox().map { it.key })
        assertIs<DecisionResult.Superseded>(engine.decide(keyOf(old), InboundDecision.ApplyVersion))
        assertFalse(rig.inbound.rows[keyOf(old)]!!.status != InboundStatus.PENDING)
    }
}
