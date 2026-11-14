package ai.cargo.sync

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** URLs, credentials and photos. Fixture grade. */
class TrustBoundaryTest {
    @Test fun release_refuses_unset_and_cleartext_and_invalid_urls() {
        fun problem(v: String?, p: String?, t: TransportTrust = TransportTrust.RELEASE) = (resolveEndpoints(v, p, t) as? RelayEndpoints.Disabled)?.problem
        assertEquals(EndpointProblem.VEHICLE_URL_UNSET, problem(null, "https://p"))
        assertEquals(EndpointProblem.PHONE_URL_UNSET, problem("https://r", ""))
        assertEquals(EndpointProblem.CLEARTEXT_REFUSED, problem("http://r", "https://p"))
        assertEquals(EndpointProblem.CLEARTEXT_REFUSED, problem("https://r", "http://p"))
        assertEquals(EndpointProblem.VEHICLE_URL_INVALID, problem("ftp://r", "https://p"))
        assertEquals(EndpointProblem.VEHICLE_URL_INVALID, problem("https://u:p@r", "https://p"))
        assertEquals(EndpointProblem.PHONE_URL_INVALID, problem("https://r", "https://p/#x"))
    }

    @Test fun demo_local_allows_cleartext_only_by_explicit_setting_and_urls_stay_separate() {
        val on = assertIs<RelayEndpoints.Enabled>(resolveEndpoints("http://10.0.2.2:3/", "http://10.0.2.2:4/", TransportTrust.DEMO_LOCAL))
        assertEquals("http://10.0.2.2:3", on.vehicleRelayBaseUrl)
        assertEquals("http://10.0.2.2:4/", on.phoneWebBaseUrl)
    }

    @Test fun disabled_engine_never_touches_the_network() {
        val relay = FakeRelayHttp()
        val dir = Files.createTempDirectory("s").toFile()
        val engine = SyncEngine(resolveEndpoints(null, null, TransportTrust.RELEASE), relay, FakeInbound(), SyncJournal(File(dir, "j")), CredentialStore(dir, FakeCipher()), PhotoStore(dir), { T0 }, { T_ISO })
        assertIs<SyncStatus.Disabled>(engine.poll().status)
        assertIs<PairingStart.Refused>(engine.startPairing())
        assertTrue(relay.requests.isEmpty())
    }

    @Test fun pairing_stores_token_hash_only_and_uses_credential_expiry_not_claim_expiry() {
        val rig = Rig()
        val engine = rig.engine()
        val start = assertIs<PairingStart.WaitingClaim>(engine.startPairing())
        engine.poll()
        val (hash, expiry) = rig.inbound.pairings.getValue(PAIRING)
        assertEquals(sha256Hex(TOKEN.toByteArray()), hash)
        assertEquals(java.time.Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(), expiry)
        assertEquals(java.time.Instant.parse("2026-11-03T00:05:00Z").toEpochMilli(), start.claimExpiresAtMs)
    }

    @Test fun origin_mismatch_is_refused_and_the_token_is_given_back() {
        listOf<(FakeRelayHttp) -> Unit>(
            { it.qrUrl = "https://evil.example/pair#pairing=qp_x" },
            { it.echoedVehicleBase = "https://evil.example/" },
            { it.qrUrl = "https://phone.example/pair?pairing=qp_x" },
        ).forEach { setup ->
            val rig = Rig()
            setup(rig.relay)
            assertIs<PairingStart.Refused>(rig.engine().startPairing())
            assertIs<CredentialLoad.None>(rig.credentialStore.load())
            assertTrue(rig.relay.revoked, "created token must be returned")
            assertTrue(rig.inbound.pairings.isEmpty())
        }
    }

    @Test fun redirect_is_a_protocol_error_and_real_transport_is_configured_not_to_follow() {
        val rig = Rig()
        val engine = rig.paired()
        rig.relay.redirect = true
        assertEquals(SyncStatus.ProtocolError("REDIRECT_REFUSED:302"), engine.poll().status)
        val source = File("src/main/java/ai/cargo/sync/RelayTransport.kt").readText()
        assertTrue("instanceFollowRedirects = false" in source)
    }

    @Test fun secrets_are_redacted_and_the_stored_file_has_no_raw_token() {
        val secret = VehicleBearer(TOKEN)
        assertFalse(TOKEN in secret.toString())
        assertFalse(TOKEN in VehicleCredential(PAIRING, secret, 1).toString())
        assertFalse("qp_" in QrUrl("https://p/#pairing=qp_x").toString())
        val rig = Rig()
        rig.paired()
        val stored = rig.dir.resolve("vehicle-credential.json").readText()
        assertFalse(TOKEN in stored)
        assertEquals(VehicleBearer(TOKEN), assertIs<CredentialLoad.Found>(rig.credentialStore.load()).credential.bearer)
    }

    @Test fun keystore_loss_is_a_typed_state_not_a_crash() {
        val rig = Rig()
        val engine = rig.paired()
        rig.cipher.broken = true
        assertEquals(SyncStatus.CredentialLost, engine.poll().status)
    }

    @Test fun crash_between_credential_and_room_row_restores_the_row() {
        val rig = Rig()
        rig.paired()
        rig.inbound.pairings.clear()
        rig.engine().poll()
        assertNotNull(rig.inbound.pairings[PAIRING])
    }

    @Test fun photo_is_stored_only_when_header_ref_and_bytes_agree() {
        val bytes = "jpeg bytes".toByteArray()
        val good = "sha256:${sha256Hex(bytes)}"
        val rig = Rig()
        val engine = rig.paired()
        rig.relay.submit(draft(photoRef = good), hashOf(1), photo = bytes)
        engine.poll()
        assertEquals(bytes.toList(), engine.photoFile(good)!!.readBytes().toList())

        val wrong = Rig()
        val second = wrong.paired()
        wrong.relay.submit(draft(photoRef = "sha256:" + "0".repeat(64)), hashOf(1), photo = bytes)
        assertTrue(second.poll().warnings.any { it.startsWith("PHOTO_HASH_MISMATCH") })
        assertNull(second.photoFile("sha256:" + "0".repeat(64)))

        val header = Rig()
        val third = header.paired()
        header.relay.photoHeaderOverride = "0".repeat(64)
        header.relay.submit(draft(photoRef = good), hashOf(1), photo = bytes)
        assertTrue(third.poll().warnings.any { it.startsWith("PHOTO_HASH_MISMATCH") })
        assertNull(third.photoFile(good))
    }

    @Test fun photo_ref_cannot_escape_the_store_directory() {
        val dir = Files.createTempDirectory("p").toFile()
        val store = PhotoStore(dir)
        assertNull(store.fileFor("sha256:../../etc/passwd"))
        assertFalse(store.storeVerified("../x", "x", ByteArray(1)))
    }

    @Test fun module_has_no_path_to_scenes() {
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val code = file.readText().replace(Regex("/\\*.*?\\*/|//[^\\n]*", RegexOption.DOT_MATCHES_ALL), "")
            listOf("SceneRepository", "CargoStore", "DraftRepository", "LibraryRepository", ".scenes", "SceneRevision", "WorkManager").forEach { banned ->
                assertFalse(banned in code, "$banned in ${file.name}")
            }
        }
    }
}
