package ai.cargo.vehicleport.core

import ai.cargo.vehicleport.api.DoorAvailability
import ai.cargo.vehicleport.api.FailureKind
import ai.cargo.vehicleport.api.UnsupportedReason

enum class SdkPhase { BEFORE_INITIALIZE, CONNECTING, READY, AFTER_RELEASE }

sealed interface SdkFailure {
    data object PermissionMissing : SdkFailure
    data object NotConnected : SdkFailure
    data object CallAfterRelease : SdkFailure
    data object AreaRejected : SdkFailure
    data class Unclassified(val detail: String?) : SdkFailure
}

private const val CONNECTION_MESSAGE = "Service connection failed"
private const val AREA_MESSAGE = "not supported"

/**
 * Observed forms: SecurityException thrown or delivered through onFailed; a plain Exception wrapping
 * "java.lang.IllegalArgumentException: area ID: 0x100 not supported"; a plain Exception "Service connection failed".
 * Order is type, then lifecycle, then message, so a message can never override what the type already proves.
 * Anything else stays unclassified and is reported as Failed.
 */
fun classifySdkFailure(error: Throwable, phase: SdkPhase): SdkFailure {
    if (error is SecurityException) return SdkFailure.PermissionMissing
    if (error is IllegalArgumentException) return SdkFailure.AreaRejected
    val message = error.message.orEmpty()
    if (phase == SdkPhase.AFTER_RELEASE) return SdkFailure.CallAfterRelease
    return when {
        message.contains(CONNECTION_MESSAGE) -> SdkFailure.NotConnected
        message.contains(AREA_MESSAGE) && message.contains("IllegalArgumentException") -> SdkFailure.AreaRejected
        else -> SdkFailure.Unclassified("${error.javaClass.name}: ${message.take(160)}")
    }
}

/** Terminal mapping for failures that do not retry. NotConnected is handled by the backoff loop instead. */
internal fun SdkFailure.toTerminalAvailability(): DoorAvailability = when (this) {
    SdkFailure.PermissionMissing -> DoorAvailability.PermissionMissing
    SdkFailure.AreaRejected -> DoorAvailability.Unsupported(UnsupportedReason.AREA_REJECTED)
    SdkFailure.NotConnected -> DoorAvailability.Failed(FailureKind.CONNECTION_TIMEOUT, null)
    SdkFailure.CallAfterRelease -> DoorAvailability.Released
    is SdkFailure.Unclassified -> DoorAvailability.Failed(FailureKind.UNCLASSIFIED, detail)
}
