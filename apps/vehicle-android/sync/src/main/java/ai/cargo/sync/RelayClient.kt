package ai.cargo.sync

import ai.cargo.data.InboundKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** Every relay answer as a value. Retry and terminal decisions live in the engine, not here. */
sealed interface RelayResult<out T> {
    data class Ok<T>(val value: T) : RelayResult<T>
    data class Rejected(val status: Int, val code: String, val currentState: String?, val retryAfterSeconds: Long?) : RelayResult<Nothing>
    data object NetworkUnavailable : RelayResult<Nothing>
    data class RedirectRefused(val status: Int) : RelayResult<Nothing>
    data class Malformed(val detail: String) : RelayResult<Nothing>
}

class PhotoBytes(val bytes: ByteArray, val declaredSha256: String)

class RelayClient(private val transport: RelayTransport, private val endpoints: RelayEndpoints.Enabled) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = endpoints.vehicleRelayBaseUrl

    fun createPairing(): RelayResult<PairingCreated> =
        call("POST", "/vehicle/v1/pairings", null, null) { RelayWire.decodePairingCreated(it) }.andThen(::checkOrigins)

    fun status(bearer: VehicleBearer) = call("GET", "/vehicle/v1/pairing", bearer, null) { RelayWire.decodeStatus(it) }

    fun revoke(bearer: VehicleBearer) = call("POST", "/vehicle/v1/pairing/revoke", bearer, null) { }

    fun listDrafts(bearer: VehicleBearer, after: Long, limit: Int) =
        call("GET", "/vehicle/v1/drafts?after=$after&limit=$limit", bearer, null) { RelayWire.decodePage(it) }

    fun ack(bearer: VehicleBearer, key: InboundKey, ack: AckBody) =
        call("POST", "${draftPath(key)}/ack", bearer, RelayWire.encodeAck(ack)) { }

    fun photo(bearer: VehicleBearer, key: InboundKey): RelayResult<PhotoBytes> = when (val raw = send("GET", "${draftPath(key)}/photo", bearer, null)) {
        is Sent.Failure -> raw.result
        is Sent.Response -> {
            val declared = raw.response.header("X-Photo-Sha256")
            if (declared == null) RelayResult.Malformed("photo hash header missing") else RelayResult.Ok(PhotoBytes(raw.response.body, declared))
        }
    }

    private fun draftPath(key: InboundKey) = "/vehicle/v1/drafts/${key.draftId}/${key.clientRevision}"

    /** Both URLs in the answer must match the two configured origins, or the pairing is not trusted. */
    private fun checkOrigins(created: PairingCreated): RelayResult<PairingCreated> {
        val qr = created.qrUrl.reveal()
        val wellFormedFragment = qr.substringAfter('#', "").startsWith("pairing=qp_")
        val phoneOk = originOf(qr) != null && originOf(qr) == originOf(endpoints.phoneWebBaseUrl)
        val vehicleOk = originOf(created.vehicleRelayBaseUrl) != null && originOf(created.vehicleRelayBaseUrl) == originOf(base)
        if (wellFormedFragment && phoneOk && vehicleOk) return RelayResult.Ok(created)
        // The token exists on the relay but must not be used: give it back immediately, best effort.
        runCatching { revoke(created.bearer) }
        return RelayResult.Malformed("pairing origins do not match configuration")
    }

    private sealed interface Sent {
        class Response(val response: HttpResponse) : Sent
        class Failure(val result: RelayResult<Nothing>) : Sent
    }

    private fun send(method: String, path: String, bearer: VehicleBearer?, body: String?): Sent {
        val response = try {
            transport.execute(HttpRequest(method, base + path, bearer, body))
        } catch (_: IOException) {
            return Sent.Failure(RelayResult.NetworkUnavailable)
        }
        return when (response.status) {
            in 200..299 -> Sent.Response(response)
            in 300..399 -> Sent.Failure(RelayResult.RedirectRefused(response.status))
            else -> Sent.Failure(decodeError(response))
        }
    }

    private fun <T> call(method: String, path: String, bearer: VehicleBearer?, body: String?, decode: (JsonObject) -> T): RelayResult<T> =
        when (val raw = send(method, path, bearer, body)) {
            is Sent.Failure -> raw.result
            is Sent.Response -> runCatching { decode(json.parseToJsonElement(raw.response.body.decodeToString()).jsonObject) }
                .fold({ RelayResult.Ok(it) }, { RelayResult.Malformed(it.javaClass.simpleName) })
        }

    private fun decodeError(response: HttpResponse): RelayResult.Rejected {
        val error = runCatching { json.parseToJsonElement(response.body.decodeToString()).jsonObject["error"]?.jsonObject }.getOrNull()
        fun text(name: String) = (error?.get(name) as? JsonPrimitive)?.contentOrNull
        return RelayResult.Rejected(response.status, text("code") ?: "HTTP_${response.status}", text("currentState"), response.header("Retry-After")?.toLongOrNull())
    }

    private inline fun <T, R> RelayResult<T>.andThen(next: (T) -> RelayResult<R>): RelayResult<R> = when (this) {
        is RelayResult.Ok -> next(value)
        is RelayResult.Rejected -> this
        RelayResult.NetworkUnavailable -> RelayResult.NetworkUnavailable
        is RelayResult.RedirectRefused -> this
        is RelayResult.Malformed -> this
    }
}
