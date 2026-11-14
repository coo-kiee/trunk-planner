package ai.cargo.sync

import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CE-19-02: both sides edited means a typed conflict and an explicit user command. Fixture grade. */
class ConflictSyncTest {
    private val rig = Rig(inbound = FakeInbound(headRevision = 3))
    private val d = draft(base = 1)
    private val key = keyOf(d)

    private fun conflicted(): SyncEngine {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        assertIs<DecisionResult.ChoiceRequired>(engine.decide(key, InboundDecision.ApplyVersion))
        return engine
    }

    @Test fun ce19_moved_library_reports_conflict_writes_nothing_and_names_the_authority() {
        val engine = conflicted()
        assertEquals(3L, rig.inbound.headRevision)
        assertEquals(InboundStatus.PENDING, rig.inbound.rows[key]!!.status)
        val ack = rig.relay.acks.single()
        assertTrue("\"result\":\"CONFLICT\"" in ack && "\"authoritativeRevision\":3" in ack && ITEM in ack, ack)
        assertEquals(ConflictBody(ITEM, 3), engine.inbox().single().conflict)
        assertEquals("CONFLICT_REPORTED", rig.relay.drafts[key]!!.state)
    }

    @Test fun ce19_conflict_ack_is_retained_when_offline_and_sent_on_return() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        assertIs<DecisionResult.ChoiceRequired>(engine.decide(key, InboundDecision.ApplyVersion))
        assertTrue(rig.relay.acks.isEmpty())
        val restarted = rig.engine()
        rig.relay.down = false
        restarted.poll()
        assertEquals("CONFLICT_REPORTED", rig.relay.drafts[key]!!.state)
        assertEquals(1, rig.relay.acks.size)
    }

    @Test fun ce19_user_overwrite_uses_conflict_reported_as_expected_state() {
        val engine = conflicted()
        val result = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.OverwriteAfterConflictShown))
        assertEquals(4, result.revision)
        assertEquals(AckDelivery.DELIVERED, result.delivery)
        assertTrue("\"expectedState\":\"CONFLICT_REPORTED\"" in rig.relay.acks.last())
    }

    @Test fun ce19_keep_after_conflict_is_kept_vehicle_version_and_without_conflict_is_discarded() {
        val engine = conflicted()
        engine.decide(key, InboundDecision.KeepExisting)
        assertTrue(RejectCode.USER_KEPT_VEHICLE_VERSION in rig.relay.acks.last())
        val plain = Rig()
        val second = plain.paired()
        plain.relay.submit(d, hashOf(1))
        second.poll()
        second.decide(key, InboundDecision.KeepExisting)
        assertTrue(RejectCode.USER_DISCARDED in plain.relay.acks.last())
    }

    @Test fun ce19_separate_item_reports_its_own_identity() {
        val engine = conflicted()
        val result = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.SeparateItem(uuid(77))))
        assertEquals(uuid(77), result.itemId)
        assertTrue(uuid(77) in rig.relay.acks.last())
        assertEquals(3L, rig.inbound.headRevision, "the existing item is untouched")
    }

    @Test fun ce19_lost_conflict_response_retries_once_with_the_state_the_relay_reported() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.drafts[key]!!.state = "CONFLICT_REPORTED"
        rig.inbound.headRevision = 1
        val result = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion))
        assertEquals(AckDelivery.DELIVERED, result.delivery)
        assertEquals(listOf("PENDING", "CONFLICT_REPORTED"), rig.relay.acks.map { Regex("\"expectedState\":\"(\\w+)\"").find(it)!!.groupValues[1] })
    }

    @Test fun ce19_state_conflict_retry_is_bounded() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.inbound.headRevision = 1
        repeat(2) { rig.relay.failNext.add(rig.relay.error(409, "STATE_CONFLICT", ""","currentState":"CONFLICT_REPORTED"""")) }
        val result = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion))
        assertEquals(AckDelivery.REJECTED, result.delivery)
        assertTrue(rig.relay.failNext.isEmpty(), "both canned refusals consumed: one try and one retry, then stop")
    }

    @Test fun ce19_superseded_on_ack_is_rejected_and_never_rolled_back() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.inbound.headRevision = 1
        rig.relay.drafts[key]!!.state = "SUPERSEDED"
        val result = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion))
        assertEquals(AckDelivery.REJECTED, result.delivery)
        assertEquals(2L, rig.inbound.headRevision)
    }

    @Test fun ce19_conflict_state_rebuilds_from_relay_list_after_journal_loss() {
        val engine = conflicted()
        assertNotNull(engine)
        rig.dir.resolve("journal.json").delete()
        val restarted = rig.engine()
        restarted.poll()
        assertEquals(ConflictBody(ITEM, 3), restarted.inbox().single().conflict)
        restarted.decide(key, InboundDecision.KeepExisting)
        assertTrue(RejectCode.USER_KEPT_VEHICLE_VERSION in rig.relay.acks.last())
    }
}
