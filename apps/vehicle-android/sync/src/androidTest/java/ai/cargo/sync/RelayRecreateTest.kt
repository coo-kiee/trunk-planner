package ai.cargo.sync

import ai.cargo.contracts.ItemDraft
import ai.cargo.sync.SyncWorkerService as W
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real Room, real Android Keystore, real OS process death and the real relay on an isolated PostgreSQL, called through
 * the emulator's host alias. The phone side here is a plain HTTP client for protocol input: this is not the phone app.
 */
@RunWith(AndroidJUnit4::class)
class RelayRecreateTest : RelayStageBase() {
    @Test fun real_room_keystore_process_death_and_relay() {
        assumeTrue("relayUrl argument missing: local integration not run", relay.isNotEmpty())
        resetDeviceState()

        // Bearer is restored from Keystore-wrapped storage after a real process restart.
        val paired = stage(W.PAIR)
        val pairing = paired.getString("pairingId")
        assertFalse(paired.getString("redactedQr").contains("qp_"))
        val restored = stage(W.RESTORE)
        assertEquals(false, restored.getBoolean("fileHasRawToken"))
        assertEquals(false, restored.getBoolean("toStringHasRawToken"))
        assertEquals("vh_", restored.getString("tokenPrefix"))
        // Before a claim nothing is paired yet and no draft can exist, so Room has no pairing row.
        assertTrue(restored.isNull("pairingStatus"), restored.toString())
        assertEquals("NotPaired", restored.getString("poll"))

        val qrToken = paired.getString("qrUrl").substringAfter("#pairing=")
        val (claimStatus, claim) = http("POST", "/phone/v1/pairing/claim", null, """{"pairingToken":"$qrToken"}""")
        assertEquals(200, claimStatus, claim.toString())
        val phoneToken = claim.getString("phoneToken")
        stage(W.POLL).let {
            assertEquals(true, it.getBoolean("credentialClaimed"))
            assertEquals("ACTIVE", it.getString("roomStatus"))
            assertTrue(it.getLong("credentialExpiresAtMs") > System.currentTimeMillis() + 3_600_000, it.toString())
        }

        // Crash after journal, before Room. Relisting converges and a further poll changes nothing.
        val first = itemDraft(pairing, 11, base = null)
        assertEquals(201, submit(phoneToken, first).first)
        stage(W.CRASH_BEFORE_ROOM, first.draftId, pairing).let { assertTrue(it.getBoolean("killedAtSeam")) }
        stage(W.POLL, first.draftId, pairing).let {
            assertTrue(it.getBoolean("journalBefore"), "journal is durable before the receive step")
            assertEquals(0, it.getInt("pendingBefore"))
            assertEquals(1, it.getInt("pendingAfter"))
            assertTrue(it.getString("report").contains("received=1"), it.getString("report"))
        }
        stage(W.POLL, first.draftId, pairing).let {
            assertTrue(it.getString("report").contains("received=0"), it.getString("report"))
            assertEquals(1, it.getInt("pendingAfter"))
        }

        // Crash after the Room commit, before the ack leaves. The durable obligation is sent by the next process.
        stage(W.CRASH_BEFORE_ACK, first.draftId, pairing).let { assertTrue(it.getBoolean("killedAtSeam")) }
        assertEquals("PENDING", phoneView(phoneToken, first).getString("state"))
        stage(W.POLL, first.draftId, pairing).let {
            assertEquals(1, it.getInt("unsentBefore"))
            assertTrue(it.isNull("deliveryBefore"), "committed but undelivered must not read as delivered")
            assertEquals("DELIVERED", it.getString("delivery"))
            assertEquals(0, it.getInt("unsentAfter"))
        }
        phoneView(phoneToken, first).let {
            assertEquals("ACCEPTED", it.getString("state"), it.toString())
            assertEquals(1, it.getJSONObject("terminalAck").getInt("acceptedRevision"))
        }

        // Crash after the relay accepted the ack, before the journal knows. The resend is an idempotent replay.
        val second = itemDraft(pairing, 12, base = 1)
        assertEquals(201, submit(phoneToken, second).first)
        stage(W.CRASH_AFTER_ACK, second.draftId, pairing).let { assertTrue(it.getBoolean("killedAtSeam")) }
        assertEquals("ACCEPTED", phoneView(phoneToken, second).getString("state"))
        stage(W.POLL, second.draftId, pairing).let {
            assertEquals("DELIVERED", it.getString("delivery"))
            assertEquals(0, it.getInt("unsentAfter"))
        }
        assertEquals(2, phoneView(phoneToken, second).getJSONObject("terminalAck").getInt("acceptedRevision"))

        // Same submit again is the relay's duplicate, and it does not create a second vehicle row.
        assertEquals(200, submit(phoneToken, second).first)
        stage(W.POLL, second.draftId, pairing).let { assertTrue(it.getString("report").contains("received=0"), it.getString("report")) }

        // Both sides edited: typed conflict reaches the phone, nothing is overwritten, the user's keep ends it.
        val stale = itemDraft(pairing, 13, base = 1)
        assertEquals(201, submit(phoneToken, stale).first)
        stage(W.DECIDE_APPLY, stale.draftId, pairing).let {
            assertTrue(it.getString("decision").startsWith("ChoiceRequired"), it.getString("decision"))
            assertEquals("ConflictBody(authoritativeItemId=${uuid(1)}, authoritativeRevision=2)", it.getString("conflictView"))
            assertTrue(it.isNull("delivery"))
        }
        phoneView(phoneToken, stale).let {
            assertEquals("CONFLICT_REPORTED", it.getString("state"), it.toString())
            assertEquals(2, it.getJSONObject("conflict").getInt("authoritativeRevision"))
        }
        stage(W.DECIDE_KEEP, stale.draftId, pairing).let {
            assertEquals("ConflictBody(authoritativeItemId=${uuid(1)}, authoritativeRevision=2)", it.getString("conflictView"))
            assertEquals("DELIVERED", it.getString("delivery"))
        }
        phoneView(phoneToken, stale).let {
            assertEquals("REJECTED", it.getString("state"))
            assertEquals("USER_KEPT_VEHICLE_VERSION", it.getJSONObject("terminalAck").getString("rejectCode"))
        }

        // Real photo through the relay's structure check, verified against header and photoRef before storing.
        val bytes = context.assets.open("canvas-chrome.jpg").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val withPhoto = itemDraft(pairing, 14, base = null, photoRef = "sha256:$hash").copy(baseItemId = null)
        assertEquals(201, submit(phoneToken, withPhoto, bytes).first)
        stage(W.PHOTO, withPhoto.draftId, pairing, "sha256:$hash").let { assertEquals(bytes.size.toLong(), it.getLong("photoBytes")) }

        // Changed content on real Room. Stage 1: journal gone, so real Room's own comparison must catch it.
        val forSwap = itemDraft(pairing, 16, base = null).copy(baseItemId = null)
        assertEquals(201, submit(phoneToken, forSwap).first)
        stage(W.POLL, forSwap.draftId, pairing)
        stage(W.SWAP_CONTENT, forSwap.draftId, pairing).let {
            assertTrue(it.getString("warnings").contains("PAYLOAD_MISMATCH:${forSwap.draftId}"), it.toString())
            assertEquals("Quarantined", it.getString("decision"))
        }
        // Stage 2: a changed hash for a stored draft, where the journal's first binding must hold.
        stage(W.SWAP_HASH, withPhoto.draftId, pairing).let {
            assertTrue(it.getBoolean("quarantined"), it.toString())
            assertEquals("Quarantined", it.getString("decision"))
        }
        // Stage 3: a recreated process reading unaltered data. Quarantine holds and neither key was acknowledged.
        stage(W.QUARANTINE_CHECK, withPhoto.draftId, pairing).let {
            assertEquals("Quarantined", it.getString("decision"))
            assertEquals(2, it.getInt("pendingAfter"), it.toString())
        }
        listOf(withPhoto, forSwap).forEach { assertEquals("PENDING", phoneView(phoneToken, it).getString("state")) }

        // Network down is typed and storage stays usable; this stage's endpoint is a closed port on the host.
        stage(W.OFFLINE).let { assertEquals("NetworkUnavailable(pairingId=$pairing)", it.getString("report")) }

        // Revoke: the relay refuses further phone input and the vehicle forgets the credential across a restart.
        stage(W.REVOKE).let { assertEquals("Revoked(pairingId=$pairing)", it.getString("revoke")) }
        val after = submit(phoneToken, itemDraft(pairing, 15, base = null))
        assertEquals(403, after.first)
        assertEquals("PAIRING_REVOKED", after.second.getJSONObject("error").getString("code"))
        stage(W.AFTER_REVOKE).let {
            assertEquals("None", it.getString("credential"))
            assertEquals("NotPaired", it.getString("poll"))
        }
    }
}
