package ai.cargo.sync

import ai.cargo.contracts.ContractCodec
import ai.cargo.contracts.ItemDraft
import ai.cargo.data.InboundKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Relay's own hash over JCS({draft, photoSha256}). Not computed here; only stored and echoed. */
typealias RelayPayloadHash = String

data class PairingCreated(
    val pairingId: String,
    val bearer: VehicleBearer,
    val qrUrl: QrUrl,
    val claimExpiresAtMs: Long,
    val credentialExpiresAtMs: Long,
    val vehicleRelayBaseUrl: String,
)

/** Carries the one-time pairing token in its fragment, so it never prints. */
class QrUrl(private val value: String) {
    fun reveal(): String = value
    override fun toString() = "QrUrl(REDACTED)"
}

data class PairingStatusView(val pairingId: String, val claimed: Boolean, val claimExpiresAtMs: Long, val credentialExpiresAtMs: Long)

data class PhotoMeta(val mime: String, val sha256: String, val byteLength: Long)

/** Previous conflict ack as stored by the relay. */
data class ConflictBody(val authoritativeItemId: String, val authoritativeRevision: Long)

/** [draft] is null when the payload failed the schema or its keys disagree with the envelope. */
data class RelayDraft(
    val seq: Long,
    val key: InboundKey,
    val state: String,
    val payloadHash: RelayPayloadHash,
    val draft: ItemDraft?,
    val photo: PhotoMeta?,
    val conflict: ConflictBody?,
)

data class DraftPage(val pairingId: String, val drafts: List<RelayDraft>)

sealed interface AckBody {
    val expectedState: String
    val payloadHash: RelayPayloadHash

    data class Accepted(override val expectedState: String, override val payloadHash: RelayPayloadHash, val itemId: String, val revision: Long) : AckBody
    data class Conflict(override val expectedState: String, override val payloadHash: RelayPayloadHash, val itemId: String, val revision: Long) : AckBody
    data class Rejected(override val expectedState: String, override val payloadHash: RelayPayloadHash, val code: String) : AckBody
}

object RejectCode {
    const val USER_DISCARDED = "USER_DISCARDED"
    const val USER_KEPT_VEHICLE_VERSION = "USER_KEPT_VEHICLE_VERSION"
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
}

internal object RelayWire {
    fun encodeAck(ack: AckBody): String = buildJsonObject {
        put("ackVersion", 1)
        put("expectedState", ack.expectedState)
        put("payloadHash", ack.payloadHash)
        when (ack) {
            is AckBody.Accepted -> { put("result", "VEHICLE_ACCEPTED"); put("acceptedItemId", ack.itemId); put("acceptedRevision", ack.revision) }
            is AckBody.Conflict -> { put("result", "CONFLICT"); put("authoritativeItemId", ack.itemId); put("authoritativeRevision", ack.revision) }
            is AckBody.Rejected -> { put("result", "VEHICLE_REJECTED"); put("rejectCode", ack.code) }
        }
    }.toString()

    fun decodePairingCreated(root: JsonObject) = PairingCreated(
        pairingId = root.text("pairingId"),
        bearer = VehicleBearer(root.text("vehicleToken")),
        qrUrl = QrUrl(root.text("qrUrl")),
        claimExpiresAtMs = instantMs(root.text("claimExpiresAt")),
        credentialExpiresAtMs = instantMs(root.text("credentialExpiresAt")),
        vehicleRelayBaseUrl = root.text("vehicleRelayBaseUrl"),
    )

    fun decodeStatus(root: JsonObject) = PairingStatusView(
        pairingId = root.text("pairingId"),
        claimed = root.text("state") == "CLAIMED",
        claimExpiresAtMs = instantMs(root.text("claimExpiresAt")),
        credentialExpiresAtMs = instantMs(root.text("credentialExpiresAt")),
    )

    fun decodePage(root: JsonObject): DraftPage {
        val pairingId = root.text("pairingId")
        return DraftPage(pairingId, root.getValue("drafts").jsonArray.map { decodeDraft(pairingId, it.jsonObject) })
    }

    private fun decodeDraft(pairingId: String, row: JsonObject): RelayDraft {
        val key = InboundKey(pairingId, row.text("draftId"), row.getValue("clientRevision").jsonPrimitive.long)
        val draft = row["draft"]?.toString()?.let { text -> runCatching { ContractCodec.decodeDraft(text) }.getOrNull() }
            ?.takeIf { it.pairingId == key.pairingId && it.draftId == key.draftId && it.clientRevision == key.clientRevision }
        return RelayDraft(
            seq = row.getValue("seq").jsonPrimitive.long,
            key = key,
            state = row.text("state"),
            payloadHash = row.text("payloadHash"),
            draft = draft,
            photo = (row["photo"] as? JsonObject)?.let { PhotoMeta(it.text("mime"), it.text("sha256"), it.getValue("byteLength").jsonPrimitive.long) },
            conflict = (row["conflict"] as? JsonObject)?.let { body ->
                val item = (body["authoritativeItemId"] as? JsonPrimitive)?.contentOrNull
                val revision = (body["authoritativeRevision"] as? JsonPrimitive)?.long
                if (item != null && revision != null) ConflictBody(item, revision) else null
            },
        )
    }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun instantMs(text: String) = java.time.Instant.parse(text).toEpochMilli()
}
