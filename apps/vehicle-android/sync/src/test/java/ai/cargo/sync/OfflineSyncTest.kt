package ai.cargo.sync

import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** CE-20-02: relay down is a typed state and vehicle storage keeps working. Plus sidecar crash points. Fixture grade. */
class OfflineSyncTest {
    private val rig = Rig()
    private val d = draft()
    private val key = keyOf(d)

    @Test fun ce20_network_down_is_typed_and_decisions_still_commit() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        assertEquals(SyncStatus.NetworkUnavailable(PAIRING), engine.poll().status)
        val decided = assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion))
        assertNull(decided.delivery)
        assertEquals(InboundStatus.ACCEPTED, rig.inbound.rows[key]!!.status)
        assertEquals(1, rig.inbound.unsentAcks().size)
    }

    @Test fun ce20_durable_ack_is_resent_after_restart_when_the_relay_returns() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        val restarted = rig.engine()
        restarted.poll()
        assertEquals(AckDelivery.DELIVERED, restarted.deliveryOf(key))
        assertEquals("ACCEPTED", rig.relay.drafts[key]!!.state)
        assertTrue(rig.inbound.unsentAcks().isEmpty())
    }

    @Test fun ce20_server_side_unavailability_keeps_the_ack_and_reports_retry_after() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.failNext.add(rig.relay.error(503, "DATABASE_UNAVAILABLE", retryAfter = "7"))
        engine.decide(key, InboundDecision.ApplyVersion)
        assertNull(engine.deliveryOf(key))
        repeat(2) { rig.relay.failNext.add(rig.relay.error(429, "RATE_LIMITED", retryAfter = "9")) }
        assertEquals(SyncStatus.Throttled("RATE_LIMITED", 9), engine.poll().status)
    }

    @Test fun retry_policy_is_bounded_and_respects_retry_after() {
        assertEquals(ForegroundRetryPolicy.IDLE_MS, ForegroundRetryPolicy.delayMs(0, null))
        assertEquals(2_000, ForegroundRetryPolicy.delayMs(1, null))
        assertEquals(4_000, ForegroundRetryPolicy.delayMs(2, null))
        assertEquals(ForegroundRetryPolicy.MAX_MS, ForegroundRetryPolicy.delayMs(40, null))
        assertEquals(90_000, ForegroundRetryPolicy.delayMs(1, 90))
    }

    @Test fun crash_after_journal_before_room_converges_by_relisting() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        rig.inbound.crashBeforeReceive = true
        runCatching { engine.poll() }.onSuccess { fail("expected the simulated crash") }
        assertTrue(rig.inbound.rows.isEmpty())
        rig.inbound.crashBeforeReceive = false
        assertEquals(1, rig.engine().poll().received)
    }

    @Test fun crash_after_relay_accepted_ack_converges_with_idempotent_replay() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.crashAfterAck = true
        runCatching { engine.decide(key, InboundDecision.ApplyVersion) }.onSuccess { fail("expected the simulated crash") }
        rig.relay.crashAfterAck = false
        val restarted = rig.engine()
        restarted.flushAcks()
        assertEquals(AckDelivery.DELIVERED, restarted.deliveryOf(key))
        assertEquals(1, rig.relay.acks.distinct().size)
    }

    @Test fun crash_between_journal_terminal_and_room_mark_does_not_send_again() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.inbound.crashAtMark = true
        runCatching { engine.decide(key, InboundDecision.ApplyVersion) }.onSuccess { fail("expected the simulated crash") }
        rig.inbound.crashAtMark = false
        val sent = rig.relay.acks.size
        rig.engine().flushAcks()
        assertEquals(sent, rig.relay.acks.size)
        assertTrue(rig.inbound.unsentAcks().isEmpty())
    }

    @Test fun lost_journal_recovers_hash_from_listing() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(5))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        rig.dir.resolve("journal.json").writeText("{ damaged")
        val restarted = rig.engine()
        restarted.flushAcks()
        assertEquals(AckDelivery.DELIVERED, restarted.deliveryOf(key))
        assertTrue(hashOf(5) in rig.relay.acks.single())
        assertTrue(rig.dir.listFiles()!!.any { "damaged-" in it.name }, "damaged file is preserved")
    }

    @Test fun lost_journal_with_expired_listing_ends_typed() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(5))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        rig.relay.drafts.clear()
        rig.dir.resolve("journal.json").delete()
        val restarted = rig.engine()
        restarted.flushAcks()
        assertEquals(AckDelivery.UNDELIVERED_EXPIRED, restarted.deliveryOf(key))
        assertTrue(rig.inbound.unsentAcks().isEmpty())
    }

    @Test fun server_expired_credential_ends_the_undelivered_ack_as_expired() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        rig.relay.credentialExpired = true
        rig.now = java.time.Instant.parse("2026-11-04T00:00:00Z").toEpochMilli()
        assertEquals(SyncStatus.Expired(PAIRING), engine.poll().status)
        assertEquals(AckDelivery.UNDELIVERED_EXPIRED, engine.deliveryOf(key))
    }
}
