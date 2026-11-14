package ai.cargo.vehicleport.core

import ai.cargo.vehicleport.api.DoorPosition
import ai.cargo.vehicleport.api.DoorSuggestion

const val DOOR_CLOSED_VALUE = 0
const val DOOR_OPEN_VALUE = 100

/** Pure edge detector. Only a stable 0 to 100 or 100 to 0 transition can suggest anything. */
data class DoorTrack(val position: DoorPosition = DoorPosition.Unknown, val lastStable: DoorPosition? = null)

data class DoorStep(val track: DoorTrack, val suggestion: DoorSuggestion?)

fun positionOf(value: Int?): DoorPosition = when {
    value == null -> DoorPosition.Unknown
    value == DOOR_CLOSED_VALUE -> DoorPosition.Closed
    value == DOOR_OPEN_VALUE -> DoorPosition.Open
    value in (DOOR_CLOSED_VALUE + 1) until DOOR_OPEN_VALUE -> DoorPosition.Moving(value)
    else -> DoorPosition.Unknown
}

fun observeListener(track: DoorTrack, value: Int?): DoorStep {
    val position = positionOf(value)
    return when (position) {
        // After Unknown there is no trustworthy origin, so the next stable value is a baseline, not an edge.
        DoorPosition.Unknown -> DoorStep(DoorTrack(position, null), null)
        is DoorPosition.Moving -> DoorStep(track.copy(position = position), null)
        DoorPosition.Closed, DoorPosition.Open -> {
            val suggestion = when {
                track.lastStable == null || track.lastStable == position -> null
                position == DoorPosition.Open -> DoorSuggestion.SHOW_LOADING_CHECKLIST
                else -> DoorSuggestion.OFFER_SAVE_CONFIRMATION
            }
            DoorStep(DoorTrack(position, position), suggestion)
        }
    }
}

/** An initial read is only a baseline. It is ignored once the listener has spoken, because it may be older. */
fun observeInitialRead(track: DoorTrack, value: Int?): DoorTrack {
    if (track.position != DoorPosition.Unknown) return track
    val position = positionOf(value)
    return when (position) {
        DoorPosition.Closed, DoorPosition.Open -> DoorTrack(position, position)
        else -> DoorTrack(position, null)
    }
}
