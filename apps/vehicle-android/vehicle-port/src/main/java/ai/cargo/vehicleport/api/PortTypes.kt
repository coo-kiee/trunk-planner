package ai.cargo.vehicleport.api

/** Why the door link is not usable yet. A retry is still in progress. */
enum class NotReadyReason { NOT_STARTED, SERVICE_NOT_CONNECTED, CONNECTION_LOST }

enum class UnsupportedReason {
    /** Capability answered but has no entry for this area. */
    CAPABILITY_ABSENT,

    /** Capability call completed with no map at all. */
    CAPABILITY_NULL,

    /** The SDK rejected the area when it was addressed. */
    AREA_REJECTED,

    /** Reported bounds are not the observed 0..100, so open and closed cannot be told apart. */
    UNEXPECTED_BOUNDS,
}

enum class FailureKind { CONNECTION_TIMEOUT, RELEASE_STUCK, UNCLASSIFIED }

/** Mutually exclusive link states. There is deliberately no fallback that reads as a working door. */
sealed interface DoorAvailability {
    data class NotReady(val reason: NotReadyReason, val attempt: Int) : DoorAvailability
    data class Available(val minValue: Int, val maxValue: Int) : DoorAvailability
    data class Unsupported(val reason: UnsupportedReason) : DoorAvailability

    /** Normal manifest permission missing. No dialog can fix it: it needs a rebuilt manifest. */
    data object PermissionMissing : DoorAvailability

    data class Failed(val kind: FailureKind, val detail: String?) : DoorAvailability
    data class Releasing(val inFlight: Int) : DoorAvailability
    data object Released : DoorAvailability
}

/** Moving and Unknown never produce a suggestion by themselves. */
sealed interface DoorPosition {
    data object Closed : DoorPosition
    data object Open : DoorPosition
    data class Moving(val rawValue: Int) : DoorPosition
    data object Unknown : DoorPosition
}

data class DoorState(val availability: DoorAvailability, val position: DoorPosition)

/** Suggestions only: the UI may offer them. They never save, confirm or imply that cargo was loaded. */
enum class DoorSuggestion { SHOW_LOADING_CHECKLIST, OFFER_SAVE_CONFIRMATION }

enum class UxUnavailableReason { NOT_STARTED, CAR_NULL, MANAGER_NULL, PERMISSION_MISSING, RESTRICTIONS_NULL, FAILED, RELEASED }

/**
 * The only source for edit/QR gating. The raw mask is preserved for evidence and diagnosis; the gate is requiresDO
 * because this app does not declare distraction optimization. Unknown always fails closed.
 */
sealed interface UxRestrictionState {
    val editingAllowed: Boolean
    val qrAllowed: Boolean
    val renderStop: Boolean

    data class Known(val requiresDistractionOptimization: Boolean, val rawRestrictionMask: Int) : UxRestrictionState {
        override val editingAllowed get() = !requiresDistractionOptimization
        override val qrAllowed get() = !requiresDistractionOptimization
        override val renderStop get() = requiresDistractionOptimization
    }

    data class Unavailable(val reason: UxUnavailableReason, val detail: String? = null) : UxRestrictionState {
        override val editingAllowed get() = false
        override val qrAllowed get() = false
        override val renderStop get() = true
    }
}

/** Arrival was never observed on the SDK, so the feature stays off and no Navi dependency exists. */
sealed interface NaviArrivalState {
    data class Disabled(val reason: String) : NaviArrivalState
}
