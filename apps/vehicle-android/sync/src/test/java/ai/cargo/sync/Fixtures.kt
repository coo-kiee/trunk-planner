package ai.cargo.sync

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemDraft
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm
import ai.cargo.data.AcceptResult
import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundKey
import ai.cargo.data.InboundStatus
import ai.cargo.data.PairingStatus
import ai.cargo.data.ReceiveResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Fixture only. Real Room, real relay and process death are covered by the androidTest and marked with their own grade. */
const val PAIRING = "00000000-0000-4000-8000-000000000900"
const val ITEM = "00000000-0000-4000-8000-000000000001"
const val TOKEN = "vh_secret_vehicle_token"
const val T0 = 1_000_000L
const val T_ISO = "2026-11-03T00:00:00Z"
fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
fun hashOf(n: Int) = n.toString(16).padStart(64, '0')

fun item(x: Long = 300, photoRef: String? = null) = ItemVersion(
    schemaVersion = 1, id = ITEM, revision = 1, createdAt = T_ISO, updatedAt = T_ISO, name = "시험 물품", photoRef = photoRef,
    shapeKind = ShapeKind.BOX, shapeProxy = listOf(BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(x, 200, 200))),
    measuredDimensionsMm = DimensionsMm(x, 200, 200), errorMm = 0, measurementSource = MeasurementSource.DEMO_SYNTHETIC,
    rotationRule = RotationRule.ANY24, stackable = true, removalPriority = 1, confirmedDimensions = true,
)

fun draft(n: Int = 901, rev: Long = 1, base: Long? = 1, x: Long = 300, photoRef: String? = null) = ItemDraft(
    draftId = uuid(n), pairingId = PAIRING, baseItemId = if (base != null) ITEM else null, baseItemRevision = base,
    schemaVersion = 1, payload = item(x, photoRef), clientRevision = rev,
)

fun keyOf(d: ItemDraft) = InboundKey(d.pairingId, d.draftId, d.clientRevision)

/** Mirrors the storage rules of InboundRepository, which InboundRepositoryTest verifies on real Room. */
class FakeInbound(var headRevision: Long = 1) : InboundGateway {
    data class Row(val draft: ItemDraft, var status: String = InboundStatus.PENDING, var item: String? = null, var revision: Long? = null, var sent: Boolean = false)
    val rows = LinkedHashMap<InboundKey, Row>()
    val pairings = HashMap<String, Pair<String, Long>>()
    val decisions = ArrayList<InboundDecision>()
    var pairingStatusValue = PairingStatus.ACTIVE
    var crashBeforeReceive = false
    var crashAtMark = false

    override fun createPairing(pairingId: String, tokenHash: String, nowMs: Long, expiresAtMs: Long) { pairings[pairingId] = tokenHash to expiresAtMs; pairingStatusValue = PairingStatus.ACTIVE }
    override fun revokePairing(pairingId: String, nowMs: Long) { pairingStatusValue = PairingStatus.REVOKED }
    override fun pairingStatus(pairingId: String, nowMs: Long) = if (pairingId in pairings) pairingStatusValue else null
    override fun receive(draft: ItemDraft, nowMs: Long): ReceiveResult {
        if (crashBeforeReceive) throw SimulatedCrash()
        val key = keyOf(draft)
        rows[key]?.let { return if (it.draft == draft) ReceiveResult.Duplicate(key, it.status) else ReceiveResult.PayloadMismatch(key) }
        if (pairingStatusValue != PairingStatus.ACTIVE) return ReceiveResult.PairingInactive(draft.pairingId, pairingStatusValue)
        rows[key] = Row(draft)
        return ReceiveResult.Stored(key)
    }
    override fun pending() = rows.values.filter { it.status == InboundStatus.PENDING }.map { it.draft }
    override fun decide(key: InboundKey, decision: InboundDecision, nowMs: Long, at: String): AcceptResult {
        val row = rows[key] ?: return AcceptResult.NotFound
        if (row.status != InboundStatus.PENDING) return AcceptResult.AlreadyDecided(row.status, row.item, row.revision)
        if (pairingStatusValue != PairingStatus.ACTIVE) return AcceptResult.PairingInactive(key.pairingId, pairingStatusValue)
        decisions += decision
        return when (decision) {
            InboundDecision.KeepExisting -> { row.status = InboundStatus.KEPT; AcceptResult.Kept }
            is InboundDecision.SeparateItem -> accept(row, decision.newItemId, 1)
            else -> {
                val moved = headRevision != (row.draft.baseItemRevision ?: 0L)
                if (moved && decision != InboundDecision.OverwriteAfterConflictShown) return AcceptResult.ChoiceRequired(row.draft.baseItemRevision, headRevision)
                headRevision += 1
                accept(row, row.draft.baseItemId ?: row.draft.payload.id, headRevision)
            }
        }
    }
    private fun accept(row: Row, id: String, revision: Long): AcceptResult {
        row.status = InboundStatus.ACCEPTED; row.item = id; row.revision = revision
        return AcceptResult.Accepted(id, revision)
    }
    override fun unsentAcks() = rows.filter { it.value.status != InboundStatus.PENDING && !it.value.sent }.map { (k, v) -> UnsentAck(k, v.status, v.item, v.revision) }
    override fun markAckSent(key: InboundKey, nowMs: Long) {
        if (crashAtMark) throw SimulatedCrash()
        rows[key]?.sent = true
    }
}

class SimulatedCrash : RuntimeException()

/** Protocol-shaped HTTP fixture. It is not the relay; the relay itself is exercised by the local integration run. */
class FakeRelayHttp : RelayTransport {
    class Stored(val draft: ItemDraft, val hash: String, var state: String = "PENDING", var conflict: String? = null, var ack: String? = null, val photo: ByteArray? = null)
    val drafts = LinkedHashMap<InboundKey, Stored>()
    val acks = ArrayList<String>()
    val requests = ArrayList<HttpRequest>()
    var down = false
    var revoked = false
    var credentialExpired = false
    var tokenUnknown = false
    var claimed = true
    var claimExpiresAt = "2026-11-03T00:05:00Z"
    var serverCredentialExpiresAt = "2026-11-04T00:00:00Z"
    var statusPairingId = PAIRING
    var redirect = false
    var crashAfterAck = false
    var qrUrl = "https://phone.example/pair#pairing=qp_one_time"
    var echoedVehicleBase = "https://relay.example/"
    var failNext: ArrayDeque<HttpResponse> = ArrayDeque()
    var photoHeaderOverride: String? = null
    private var seq = 0L

    fun submit(d: ItemDraft, hash: String, photo: ByteArray? = null) { drafts[keyOf(d)] = Stored(d, hash, photo = photo) }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        if (down) throw IOException("connect failed")
        failNext.removeFirstOrNull()?.let { return it }
        if (redirect) return HttpResponse(302, mapOf("Location" to "https://evil.example/"), ByteArray(0))
        val path = request.url.removePrefix("https://relay.example").substringBefore('?')
        if (path == "/vehicle/v1/pairings") return ok(
            """{"pairingId":"$PAIRING","vehicleToken":"$TOKEN","qrUrl":"$qrUrl","claimExpiresAt":"$claimExpiresAt","credentialExpiresAt":"$claimExpiresAt","vehicleRelayBaseUrl":"$echoedVehicleBase","relayConfigVersion":1}""", 201)
        if (request.bearer?.reveal() != TOKEN || tokenUnknown) return error(401, "UNAUTHENTICATED")
        if (revoked) return error(403, "PAIRING_REVOKED")
        if (credentialExpired) return error(401, "CREDENTIAL_EXPIRED")
        if (path == "/vehicle/v1/pairing/revoke") { revoked = true; return ok("""{"revoked":true,"pairingId":"$PAIRING","purgedDrafts":0}""") }
        if (path == "/vehicle/v1/pairing") return ok(
            """{"pairingId":"$statusPairingId","state":"${if (claimed) "CLAIMED" else "WAITING_CLAIM"}","claimExpiresAt":"$claimExpiresAt","credentialExpiresAt":"${if (claimed) serverCredentialExpiresAt else claimExpiresAt}"}""")
        if (path == "/vehicle/v1/drafts") return list()
        val parts = path.removePrefix("/vehicle/v1/drafts/").split('/')
        val key = InboundKey(PAIRING, parts[0], parts[1].toLong())
        val stored = drafts[key] ?: return error(404, "DRAFT_NOT_FOUND")
        return if (parts[2] == "photo") {
            HttpResponse(200, mapOf("X-Photo-Sha256" to (photoHeaderOverride ?: sha256Hex(stored.photo!!))), stored.photo!!)
        } else ack(stored, request.jsonBody!!)
    }

    private fun ack(stored: Stored, body: String): HttpResponse {
        acks += body
        val root = Json.parseToJsonElement(body).jsonObject
        if (root["payloadHash"]!!.jsonPrimitive.content != stored.hash) return error(409, "PAYLOAD_HASH_MISMATCH")
        if (stored.ack == body || stored.conflict == body) return ok("""{"replayed":true}""")
        val expected = root["expectedState"]!!.jsonPrimitive.content
        if (stored.state != expected) return error(409, "STATE_CONFLICT", """,\"currentState\":\"${stored.state}\"""".replace("\\", ""))
        if (root["result"]!!.jsonPrimitive.content == "CONFLICT") {
            stored.state = "CONFLICT_REPORTED"; stored.conflict = body
        } else {
            stored.state = if (root["result"]!!.jsonPrimitive.content == "VEHICLE_ACCEPTED") "ACCEPTED" else "REJECTED"; stored.ack = body
        }
        if (crashAfterAck) throw SimulatedCrash()
        return ok("""{"replayed":false}""")
    }

    private fun list(): HttpResponse {
        val rows = drafts.filterValues { it.state == "PENDING" || it.state == "CONFLICT_REPORTED" }.map { (key, s) ->
            val conflict = s.conflict?.let { c -> ""","conflict":$c""" } ?: ""
            val photo = s.photo?.let { """,\"photo\":{\"mime\":\"image/jpeg\",\"sha256\":\"${sha256Hex(it)}\",\"byteLength\":${it.size}}""".replace("\\", "") } ?: ""
            val json = Json { explicitNulls = false }.encodeToString(ItemDraft.serializer(), s.draft)
            """{"seq":${++seq},"draftId":"${key.draftId}","clientRevision":${key.clientRevision},"state":"${s.state}","payloadHash":"${s.hash}","draft":$json$photo$conflict}"""
        }
        return ok("""{"pairingId":"$PAIRING","drafts":[${rows.joinToString(",")}]}""")
    }

    private fun ok(body: String, status: Int = 200) = HttpResponse(status, emptyMap(), body.toByteArray())
    fun error(status: Int, code: String, extra: String = "", retryAfter: String? = null) =
        HttpResponse(status, retryAfter?.let { mapOf("Retry-After" to it) } ?: emptyMap(), """{"error":{"code":"$code","requestId":"r"$extra}}""".toByteArray())
}

class FakeCipher : CredentialCipher {
    var broken = false
    override fun seal(plain: ByteArray) = byteArrayOf(1) to plain.map { (it + 1).toByte() }.toByteArray()
    override fun open(iv: ByteArray, sealed: ByteArray): ByteArray {
        check(!broken) { "keystore wiped" }
        return sealed.map { (it - 1).toByte() }.toByteArray()
    }
    override fun destroy() {}
}

class Rig(
    val inbound: FakeInbound = FakeInbound(),
    val relay: FakeRelayHttp = FakeRelayHttp(),
    val dir: File = Files.createTempDirectory("sync").toFile(),
    val cipher: FakeCipher = FakeCipher(),
    var now: Long = T0,
) {
    val endpoints = resolveEndpoints("https://relay.example", "https://phone.example/pair", TransportTrust.RELEASE)
    /** A new engine over the same durable files is what a restarted process would build. */
    fun engine() = SyncEngine(endpoints, relay, inbound, SyncJournal(File(dir, "journal.json")), CredentialStore(dir, cipher), PhotoStore(File(dir, "photos")), { now }, { T_ISO })
    val credentialStore get() = CredentialStore(dir, cipher)
}

/** Paired and claimed, as the vehicle sees it once a poll has observed the claim. */
fun Rig.paired(): SyncEngine = engine().also { it.startPairing(); it.poll() }
