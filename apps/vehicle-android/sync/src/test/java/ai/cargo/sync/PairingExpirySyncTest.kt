package ai.cargo.sync

import ai.cargo.data.InboundDecision
import ai.cargo.data.PairingStatus
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-01: the server owns claim state and credential expiry. A local clock alone never ends a link. Fixture grade. */
class PairingExpirySyncTest {
    private val rig = Rig()
    private val claimDeadline = Instant.parse("2026-11-03T00:05:00Z").toEpochMilli()
    private val credentialEnd = Instant.parse("2026-11-04T00:00:00Z").toEpochMilli()
    private val d = draft(base = 1)
    private val key = keyOf(d)

    private fun waitingForClaim(): SyncEngine {
        rig.relay.claimed = false
        return rig.engine().also { assertIs<PairingStart.WaitingClaim>(it.startPairing()) }
    }

    private fun load() = assertIs<CredentialLoad.Found>(rig.credentialStore.load()).credential

    @Test fun f01_claimed_link_survives_past_the_claim_deadline_and_still_receives_and_decides() {
        rig.relay.claimed = true
        val engine = rig.paired()
        rig.now = claimDeadline + 55_000
        rig.relay.submit(d, hashOf(1))
        assertEquals(SyncStatus.Paired(PAIRING, credentialEnd), engine.poll().status)
        assertEquals(AckDelivery.DELIVERED, assertIs<DecisionResult.Accepted>(engine.decide(key, InboundDecision.ApplyVersion)).delivery)
    }

    @Test fun f01_claim_observed_late_uses_the_server_value_and_not_the_expired_local_one() {
        val engine = waitingForClaim()
        rig.relay.claimed = true
        rig.now = claimDeadline + 1
        assertEquals(SyncStatus.Paired(PAIRING, credentialEnd), engine.poll().status)
        assertEquals(credentialEnd, load().credentialExpiresAtMs)
        assertEquals(credentialEnd, rig.inbound.pairings.getValue(PAIRING).second)
    }

    @Test fun waiting_for_claim_is_not_reported_as_paired_and_creates_no_room_row() {
        val engine = waitingForClaim()
        assertEquals(SyncStatus.NotPaired, engine.poll().status)
        assertTrue(rig.inbound.pairings.isEmpty())
        assertEquals(claimDeadline, load().credentialExpiresAtMs, "no invented lifetime")
        assertEquals(false, load().claimed)
        assertTrue(rig.relay.requests.none { "/drafts" in it.url }, "no drafts can exist before a claim")
    }

    @Test fun unclaimed_qr_expires_when_the_server_says_so() {
        val engine = waitingForClaim()
        rig.relay.credentialExpired = true
        rig.now = claimDeadline + 1
        assertEquals(SyncStatus.Expired(PAIRING), engine.poll().status)
        assertTrue(rig.inbound.pairings.isEmpty())
    }

    @Test fun offline_past_the_claim_deadline_is_network_unavailable_not_expired() {
        val engine = waitingForClaim()
        rig.relay.down = true
        rig.now = claimDeadline + 1
        assertEquals(SyncStatus.NetworkUnavailable(PAIRING), engine.poll().status)
    }

    @Test fun offline_past_the_real_expiry_keeps_the_owed_ack_until_the_server_answers() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.now = credentialEnd + 1
        assertEquals(SyncStatus.NetworkUnavailable(PAIRING), engine.poll().status)
        assertNull(engine.deliveryOf(key), "a local clock alone must not write this ack off")
        assertEquals(1, rig.inbound.unsentAcks().size)
        rig.relay.down = false
        rig.relay.credentialExpired = true
        assertEquals(SyncStatus.Expired(PAIRING), rig.engine().poll().status)
        assertEquals(AckDelivery.UNDELIVERED_EXPIRED, rig.engine().deliveryOf(key))
        assertTrue(rig.inbound.unsentAcks().isEmpty())
    }

    @Test fun throttled_status_past_expiry_is_not_expired_and_keeps_the_ack() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        rig.now = credentialEnd + 1
        rig.relay.failNext.add(rig.relay.error(503, "DATABASE_UNAVAILABLE", retryAfter = "3"))
        assertEquals(SyncStatus.Throttled("DATABASE_UNAVAILABLE", 3), engine.poll().status)
        assertEquals(1, rig.inbound.unsentAcks().size)
    }

    @Test fun refreshed_expiry_and_claim_survive_restart_without_asking_again() {
        rig.paired()
        val before = rig.relay.requests.count { it.url.endsWith("/vehicle/v1/pairing") }
        val restarted = rig.engine()
        assertEquals(SyncStatus.Paired(PAIRING, credentialEnd), restarted.poll().status)
        assertEquals(before, rig.relay.requests.count { it.url.endsWith("/vehicle/v1/pairing") })
        assertEquals(true, load().claimed)
    }

    @Test fun crash_between_saving_the_credential_and_creating_the_row_recovers_with_the_server_expiry() {
        rig.paired()
        rig.inbound.pairings.clear()
        rig.engine().poll()
        assertEquals(credentialEnd, rig.inbound.pairings.getValue(PAIRING).second)
    }

    @Test fun crash_before_saving_the_claim_repeats_the_question_and_converges() {
        val engine = waitingForClaim()
        rig.relay.claimed = true
        val ownCopy = rig.engine()
        ownCopy.poll()
        assertEquals(credentialEnd, rig.inbound.pairings.getValue(PAIRING).second)
        assertEquals(SyncStatus.Paired(PAIRING, credentialEnd), engine.poll().status)
    }

    @Test fun unauthenticated_before_expiry_means_revoked_and_after_it_means_expired_on_every_path() {
        val early = Rig()
        val first = early.paired()
        early.relay.tokenUnknown = true
        assertEquals(SyncStatus.Revoked(PAIRING), first.poll().status)
        val late = Rig()
        val second = late.paired()
        late.relay.tokenUnknown = true
        late.now = credentialEnd + 1
        assertEquals(SyncStatus.Expired(PAIRING), second.poll().status)
        val unclaimed = Rig().apply { relay.claimed = false }
        val third = unclaimed.engine().also { it.startPairing() }
        unclaimed.relay.tokenUnknown = true
        unclaimed.now = claimDeadline + 1
        assertEquals(SyncStatus.Expired(PAIRING), third.poll().status)
    }

    @Test fun unauthenticated_ends_the_owed_ack_consistently_with_the_status() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        rig.relay.tokenUnknown = true
        rig.now = credentialEnd + 1
        assertEquals(SyncStatus.Expired(PAIRING), engine.poll().status)
        assertEquals(AckDelivery.UNDELIVERED_EXPIRED, engine.deliveryOf(key))
    }

    @Test fun server_revoke_seen_on_status_is_revoked_not_expired() {
        val engine = waitingForClaim()
        rig.relay.revoked = true
        rig.now = claimDeadline + 1
        assertEquals(SyncStatus.Revoked(PAIRING), engine.poll().status)
    }

    @Test fun revoke_before_claim_works_and_leaves_no_row() {
        val engine = waitingForClaim()
        assertEquals(SyncStatus.Revoked(PAIRING), engine.revoke())
        assertTrue(rig.relay.revoked)
        assertTrue(rig.inbound.pairings.isEmpty())
        assertIs<CredentialLoad.None>(rig.credentialStore.load())
    }

    @Test fun status_for_another_pairing_changes_nothing() {
        val engine = waitingForClaim()
        rig.relay.statusPairingId = uuid(999)
        assertIs<SyncStatus.ProtocolError>(engine.poll().status)
        assertEquals(false, load().claimed)
        assertTrue(rig.inbound.pairings.isEmpty())
    }

    @Test fun old_install_row_with_the_claim_deadline_is_reported_and_the_ack_still_goes_out() {
        val engine = rig.paired()
        rig.relay.submit(d, hashOf(1))
        engine.poll()
        rig.relay.down = true
        engine.decide(key, InboundDecision.ApplyVersion)
        rig.relay.down = false
        rig.inbound.pairingStatusValue = PairingStatus.EXPIRED
        val status = assertIs<SyncStatus.ProtocolError>(engine.poll().status)
        assertEquals("LOCAL_PAIRING_EXPIRED_SERVER_ACTIVE", status.detail)
        assertEquals(AckDelivery.DELIVERED, engine.deliveryOf(key))
    }
}
