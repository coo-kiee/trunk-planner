package ai.cargo.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Opaque secret. The value is only readable through [reveal], so logs and data classes cannot print it. */
class VehicleBearer(private val secret: String) {
    fun reveal(): String = secret
    override fun toString() = "VehicleBearer(REDACTED)"
    override fun equals(other: Any?) = other is VehicleBearer && other.secret == secret
    override fun hashCode() = secret.hashCode()
}

class HttpRequest(val method: String, val url: String, val bearer: VehicleBearer?, val jsonBody: String?)

class HttpResponse(val status: Int, headers: Map<String, String>, val body: ByteArray) {
    private val lowered = headers.mapKeys { it.key.lowercase() }
    fun header(name: String): String? = lowered[name.lowercase()]
}

/** Throws IOException when no HTTP answer exists. An answer of any status is a response. */
fun interface RelayTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

/** Never follows a redirect: a bearer must not reach another origin. The 3xx comes back to the client as is. */
class UrlConnectionTransport(private val connectMs: Int = 10_000, private val readMs: Int = 20_000) : RelayTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectMs
            connection.readTimeout = readMs
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            request.bearer?.let { connection.setRequestProperty("Authorization", "Bearer ${it.reveal()}") }
            request.jsonBody?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use(::readBounded) ?: ByteArray(0)
            val headers = connection.headerFields.entries.filter { it.key != null }.associate { it.key to it.value.joinToString(",") }
            return HttpResponse(status, headers, bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_RESPONSE_BYTES) throw IOException("Response exceeds bound")
        }
    }
}
