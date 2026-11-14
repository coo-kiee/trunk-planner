package ai.cargo.sync

import ai.cargo.data.InboundDecision
import ai.cargo.sync.SyncWorkerService as W
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real expiry stays expiry. The relay runs its test profile with a short claim limit and credential lifetime, on the wall clock. */
@RunWith(AndroidJUnit4::class)
class ShortCredentialRealTest : RelayStageBase() {
    private val claimLimitMs = args.getString("claimLimitSeconds")!!.toLong() * 1000
    private val credentialMs = args.getString("credentialSeconds")!!.toLong() * 1000

    private fun waitUntil(at: Long) {
        val left = at - SystemClock.elapsedRealtime()
        if (left > 0) Thread.sleep(left)
    }

    /**
     * Repeats only after a recorded transport failure, which is what NetworkUnavailable correctly reports. One such failure
     * was seen against the running relay. An answered poll is never repeated, so a wrong answer still fails.
     */
    private fun pollAnswered(draftId: String? = null, pairing: String? = null): org.json.JSONObject {
        repeat(2) {
            val out = stage(W.POLL, draftId, pairing)
            if (!out.has("transportError")) return out
            Thread.sleep(3_000)
        }
        return stage(W.POLL, draftId, pairing)
    }

    @Test fun unclaimed_qr_expires_on_the_server_limit() {
        resetDeviceState()
        val start = SystemClock.elapsedRealtime()
        stage(W.PAIR)
        waitUntil(start + claimLimitMs + 3_000)
        // Offline first: past the limit, but only the server can say the QR has ended.
        stage(W.OFFLINE).let { assertTrue(it.getString("report").startsWith("NetworkUnavailable"), it.toString()) }
        pollAnswered().let {
            assertTrue(it.getString("report").startsWith("Expired"), it.toString())
            assertTrue(it.isNull("roomStatus"), "an unclaimed QR never reaches Room")
        }
    }

    @Test fun claimed_credential_expires_on_the_server_limit_and_only_then_ends_the_owed_ack() {
        resetDeviceState()
        val paired = stage(W.PAIR)
        val pairing = paired.getString("pairingId")
        val qrToken = paired.getString("qrUrl").substringAfter("#pairing=")
        val claim = http("POST", "/phone/v1/pairing/claim", null, """{"pairingToken":"$qrToken"}""").second
        val claimedAt = SystemClock.elapsedRealtime()
        val phoneToken = claim.getString("phoneToken")
        stage(W.POLL).let { assertTrue(it.getString("report").startsWith("Paired"), it.toString()) }

        // Survives its claim limit, which is shorter than the credential on this profile.
        val draft = itemDraft(pairing, 31, base = null).copy(baseItemId = null)
        assertEquals(201, submit(phoneToken, draft).first)
        waitUntil(claimedAt + claimLimitMs + 3_000)
        assertTrue(stage(W.POLL, draft.draftId, pairing).getString("report").startsWith("Paired"))

        stage(W.OFFLINE_DECIDE, draft.draftId, pairing).let {
            assertTrue(it.getString("decision").startsWith("Accepted"), it.toString())
            assertEquals(1, it.getInt("unsentAfter"))
            assertTrue(it.isNull("delivery"))
        }
        waitUntil(claimedAt + credentialMs + 3_000)
        stage(W.OFFLINE, draft.draftId, pairing).let {
            assertTrue(it.getString("report").startsWith("NetworkUnavailable"), it.toString())
            assertEquals(1, it.getInt("unsentAfter"), "a local clock alone must not write this ack off")
            assertTrue(it.isNull("delivery"))
        }
        pollAnswered(draft.draftId, pairing).let {
            assertTrue(it.getString("report").startsWith("Expired"), it.toString())
            assertEquals("UNDELIVERED_EXPIRED", it.getString("delivery"))
            assertEquals(0, it.getInt("unsentAfter"))
            assertEquals("EXPIRED", it.getString("roomStatus"))
        }
    }
}
