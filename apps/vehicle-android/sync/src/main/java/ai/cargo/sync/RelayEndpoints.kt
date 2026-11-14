package ai.cargo.sync

import java.net.URI

/** RELEASE requires HTTPS and refuses unset URLs. DEMO_LOCAL is a separate explicit setting for loopback demos. */
enum class TransportTrust { RELEASE, DEMO_LOCAL }

enum class EndpointProblem { VEHICLE_URL_UNSET, PHONE_URL_UNSET, VEHICLE_URL_INVALID, PHONE_URL_INVALID, CLEARTEXT_REFUSED }

/** The vehicle's outbound API base and the phone-facing pairing page are separate settings with no derivation. */
sealed interface RelayEndpoints {
    data class Disabled(val problem: EndpointProblem) : RelayEndpoints
    data class Enabled(val vehicleRelayBaseUrl: String, val phoneWebBaseUrl: String) : RelayEndpoints
}

fun resolveEndpoints(vehicleUrl: String?, phoneUrl: String?, trust: TransportTrust): RelayEndpoints {
    if (vehicleUrl.isNullOrBlank()) return RelayEndpoints.Disabled(EndpointProblem.VEHICLE_URL_UNSET)
    if (phoneUrl.isNullOrBlank()) return RelayEndpoints.Disabled(EndpointProblem.PHONE_URL_UNSET)
    val vehicle = parseBase(vehicleUrl) ?: return RelayEndpoints.Disabled(EndpointProblem.VEHICLE_URL_INVALID)
    val phone = parseBase(phoneUrl) ?: return RelayEndpoints.Disabled(EndpointProblem.PHONE_URL_INVALID)
    if (trust == TransportTrust.RELEASE && (vehicle.scheme != "https" || phone.scheme != "https")) {
        return RelayEndpoints.Disabled(EndpointProblem.CLEARTEXT_REFUSED)
    }
    return RelayEndpoints.Enabled(vehicleUrl.trimEnd('/'), phoneUrl)
}

private fun parseBase(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
    (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() &&
        it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null
}

internal fun originOf(value: String): String? = runCatching {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
    "$scheme://$host:$port"
}.getOrNull()
