package ai.cargo.sync

import ai.cargo.sync.SyncWorkerService as W
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-01 on real time: production credential lifetime, real relay and PostgreSQL, real Room. Waits out the 300 second claim limit. */
@RunWith(AndroidJUnit4::class)
class PairingExpiryRealTest : RelayStageBase() {
    @Test fun claimed_link_outlives_the_claim_limit_on_the_wall_clock() {
        resetDeviceState()
        val pairedAt = SystemClock.elapsedRealtime()
        val paired = stage(W.PAIR)
        val pairing = paired.getString("pairingId")
        val qrToken = paired.getString("qrUrl").substringAfter("#pairing=")
        val phoneToken = http("POST", "/phone/v1/pairing/claim", null, """{"pairingToken":"$qrToken"}""").second.getString("phoneToken")
        stage(W.POLL)
        Thread.sleep(pairedAt + CLAIM_LIMIT_MS + 10_000 - SystemClock.elapsedRealtime())

        val draft = itemDraft(pairing, 21, base = null).copy(baseItemId = null)
        assertEquals(201, submit(phoneToken, draft).first)
        val decided = stage(W.DECIDE_APPLY, draft.draftId, pairing)
        assertEquals("ACTIVE", decided.getString("roomStatus"), decided.toString())
        assertTrue(decided.getString("decision").startsWith("Accepted"), decided.toString())
        assertEquals("DELIVERED", decided.getString("delivery"))
        val view = phoneView(phoneToken, draft)
        assertEquals("ACCEPTED", view.getString("state"), view.toString())
    }

    private companion object { const val CLAIM_LIMIT_MS = 300_000L }
}
