package ai.cargo.vehicleport

import ai.cargo.vehicleport.api.DoorPosition
import ai.cargo.vehicleport.api.DoorSuggestion
import ai.cargo.vehicleport.core.DoorTrack
import ai.cargo.vehicleport.core.observeInitialRead
import ai.cargo.vehicleport.core.observeListener
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DoorTrackTest {
    private fun run(vararg values: Int?, from: DoorTrack = DoorTrack()): List<DoorSuggestion?> {
        var track = from
        return values.map { v -> observeListener(track, v).also { track = it.track }.suggestion }
    }

    @Test fun first_value_is_a_baseline_even_when_open() = assertEquals(listOf<DoorSuggestion?>(null), run(100))
    @Test fun repeated_value_is_not_an_edge() = assertEquals(listOf<DoorSuggestion?>(null, null, null), run(0, 0, 0))

    @Test fun zero_to_hundred_suggests_checklist_and_back_suggests_save_confirmation() =
        assertEquals(listOf(null, DoorSuggestion.SHOW_LOADING_CHECKLIST, DoorSuggestion.OFFER_SAVE_CONFIRMATION), run(0, 100, 0))

    @Test fun moving_alone_suggests_nothing_and_keeps_the_origin() =
        assertEquals(listOf(null, null, null, DoorSuggestion.SHOW_LOADING_CHECKLIST), run(0, 30, 70, 100))

    @Test fun moving_back_to_the_same_stable_value_is_not_an_edge() =
        assertEquals(listOf<DoorSuggestion?>(null, null, null), run(0, 50, 0))

    @Test fun null_clears_the_origin_so_the_next_stable_value_cannot_claim_a_transition() =
        assertEquals(listOf<DoorSuggestion?>(null, null, null), run(0, null, 100))

    @Test fun out_of_range_is_unknown_not_open() {
        val step = observeListener(DoorTrack(DoorPosition.Closed, DoorPosition.Closed), 101)
        assertEquals(DoorPosition.Unknown, step.track.position)
        assertNull(step.suggestion)
    }

    @Test fun initial_read_is_a_baseline_and_listener_after_it_can_edge() {
        val track = observeInitialRead(DoorTrack(), 0)
        assertEquals(DoorPosition.Closed, track.position)
        assertEquals(DoorSuggestion.SHOW_LOADING_CHECKLIST, observeListener(track, 100).suggestion)
    }

    @Test fun stale_initial_read_cannot_overwrite_a_listener_value() {
        val fromListener = observeListener(DoorTrack(), 100).track
        assertEquals(fromListener, observeInitialRead(fromListener, 0))
    }
}
