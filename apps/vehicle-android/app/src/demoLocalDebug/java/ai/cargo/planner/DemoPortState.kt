package ai.cargo.planner

import ai.cargo.vehicleport.api.DoorAvailability
import ai.cargo.vehicleport.api.DoorPosition
import ai.cargo.vehicleport.api.DoorState
import ai.cargo.vehicleport.api.DoorSuggestion
import ai.cargo.vehicleport.api.FailureKind
import ai.cargo.vehicleport.api.NaviArrivalState
import ai.cargo.vehicleport.api.NotReadyReason
import ai.cargo.vehicleport.api.UnsupportedReason
import ai.cargo.vehicleport.api.UxRestrictionState
import ai.cargo.vehicleport.api.UxUnavailableReason
import ai.cargo.vehicleport.api.VehiclePort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holds one named port state, to show how the real screen presents states the SDK will not produce on demand here.
 * Never a stand-in for SDK success: it has no Available value, and evidence marks every result from it fixture_state.
 */
class DemoPortState(name: String) : VehiclePort {
    private val availability: DoorAvailability
    private val ux: UxRestrictionState

    init {
        val allowed = UxRestrictionState.Known(false, 0)
        val pair: Pair<DoorAvailability, UxRestrictionState> = when (name) {
            "NOT_READY" -> DoorAvailability.NotReady(NotReadyReason.SERVICE_NOT_CONNECTED, 3) to allowed
            "UNSUPPORTED_ABSENT" -> DoorAvailability.Unsupported(UnsupportedReason.CAPABILITY_ABSENT) to allowed
            "UNSUPPORTED_NULL" -> DoorAvailability.Unsupported(UnsupportedReason.CAPABILITY_NULL) to allowed
            "UNSUPPORTED_REJECTED" -> DoorAvailability.Unsupported(UnsupportedReason.AREA_REJECTED) to allowed
            "PERMISSION_MISSING" -> DoorAvailability.PermissionMissing to UxRestrictionState.Unavailable(UxUnavailableReason.PERMISSION_MISSING)
            "FAILED" -> DoorAvailability.Failed(FailureKind.CONNECTION_TIMEOUT, null) to UxRestrictionState.Unavailable(UxUnavailableReason.FAILED)
            else -> error("Unknown port state $name")
        }
        availability = pair.first
        ux = pair.second
    }

    override val door = MutableStateFlow(DoorState(availability, DoorPosition.Unknown))
    override val doorSuggestions = MutableSharedFlow<DoorSuggestion>()
    override val uxRestriction = MutableStateFlow(ux)
    override val naviArrival: NaviArrivalState = NaviArrivalState.Disabled("NOT_OBSERVED")
    override fun start() = Unit
    override fun release() = Unit
}
