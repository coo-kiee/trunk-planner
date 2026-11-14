package ai.cargo.vehicleport

import ai.cargo.vehicleport.api.DoorAvailability
import ai.cargo.vehicleport.api.DoorPosition
import ai.cargo.vehicleport.api.DoorState
import ai.cargo.vehicleport.api.DoorSuggestion
import ai.cargo.vehicleport.api.FailureKind
import ai.cargo.vehicleport.api.NotReadyReason
import ai.cargo.vehicleport.api.UnsupportedReason
import ai.cargo.vehicleport.core.DoorController
import ai.cargo.vehicleport.gateway.DoorAreaKey
import ai.cargo.vehicleport.gateway.DoorBounds
import ai.cargo.vehicleport.gateway.DoorGateway
import ai.cargo.vehicleport.gateway.DoorListenerHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DoorControllerTest {
    private val sdk = FakeDoorGateway()
    private val scheduler = ManualScheduler()
    private val controller = DoorController({ sdk }, scheduler, DoorAreaKey.REAR)
    private val conn = Exception("Service connection failed")

    private fun ready() {
        controller.start()
        sdk.completeCapability()
    }

    @Test fun capability_decides_readiness_not_a_fixed_sleep() {
        controller.start()
        assertIs<DoorAvailability.NotReady>(controller.state.value.availability)
        assertFalse("register" in sdk.calls)
        assertTrue(scheduler.pending.isEmpty(), "no timer may assume readiness")
        sdk.completeCapability()
        assertEquals(DoorAvailability.Available(0, 100), controller.state.value.availability)
        assertEquals(listOf("initialize", "capability", "register", "read"), sdk.calls)
    }

    @Test fun not_connected_retries_with_bounded_backoff_then_fails_typed() {
        controller.start()
        repeat(5) { i ->
            sdk.failCapability(conn)
            assertEquals(DoorAvailability.NotReady(NotReadyReason.SERVICE_NOT_CONNECTED, i + 1), controller.state.value.availability)
            scheduler.runNext()
        }
        sdk.failCapability(conn)
        assertEquals(FailureKind.CONNECTION_TIMEOUT, assertIs<DoorAvailability.Failed>(controller.state.value.availability).kind)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test fun states_stay_distinct() {
        fun outcome(act: (FakeDoorGateway) -> Unit): DoorAvailability {
            val gateway = FakeDoorGateway()
            val c = DoorController({ gateway }, ManualScheduler(), DoorAreaKey.REAR)
            gateway.initializeError = null
            c.start()
            act(gateway)
            return c.state.value.availability
        }
        assertEquals(DoorAvailability.Unsupported(UnsupportedReason.CAPABILITY_NULL), outcome { it.completeCapability(null) })
        assertEquals(DoorAvailability.Unsupported(UnsupportedReason.CAPABILITY_ABSENT), outcome { it.completeCapability(emptyMap()) })
        assertEquals(DoorAvailability.Unsupported(UnsupportedReason.UNEXPECTED_BOUNDS), outcome { it.completeCapability(mapOf(DoorAreaKey.REAR to DoorBounds(0, 1))) })
        assertEquals(DoorAvailability.PermissionMissing, outcome { it.failCapability(SecurityException("need Permission")) })
        assertEquals(DoorAvailability.Unsupported(UnsupportedReason.AREA_REJECTED), outcome { it.failCapability(Exception("java.lang.IllegalArgumentException: area ID: 0x100 not supported")) })
        assertEquals(FailureKind.UNCLASSIFIED, assertIs<DoorAvailability.Failed>(outcome { it.failCapability(Exception("boom")) }).kind)
    }

    @Test fun synchronous_throw_at_every_sdk_boundary_is_typed_and_not_fatal() {
        val init = FakeDoorGateway().apply { initializeError = SecurityException("x") }
        DoorController({ init }, ManualScheduler(), DoorAreaKey.REAR).run { start(); assertEquals(DoorAvailability.PermissionMissing, state.value.availability) }
        val cap = FakeDoorGateway().apply { capabilityThrows = SecurityException("x") }
        DoorController({ cap }, ManualScheduler(), DoorAreaKey.REAR).run { start(); assertEquals(DoorAvailability.PermissionMissing, state.value.availability) }
        val reg = FakeDoorGateway().apply { registerThrows = SecurityException("x") }
        DoorController({ reg }, ManualScheduler(), DoorAreaKey.REAR).run {
            start(); reg.completeCapability(); assertEquals(DoorAvailability.PermissionMissing, state.value.availability)
        }
    }

    @Test fun position_flows_after_listener_and_null_is_unknown() {
        ready()
        sdk.completeRead(0)
        assertEquals(DoorPosition.Closed, controller.state.value.position)
        sdk.emit(40)
        assertEquals(DoorPosition.Moving(40), controller.state.value.position)
        sdk.emit(null)
        assertEquals(DoorPosition.Unknown, controller.state.value.position)
    }

    @Test fun edges_become_suggestions_and_nothing_else() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        controller.suggestions.onEach { seen += it }.launchIn(scope)
        ready()
        sdk.completeRead(0)
        sdk.emit(0)
        sdk.emit(100)
        sdk.emit(100)
        sdk.emit(0)
        assertEquals(listOf(DoorSuggestion.SHOW_LOADING_CHECKLIST, DoorSuggestion.OFFER_SAVE_CONFIRMATION), seen)
        scope.cancel()
    }

    @Test fun release_waits_for_in_flight_and_late_callback_changes_nothing() {
        ready()
        controller.release()
        assertFalse("release" in sdk.calls, "SDK release must wait for the in-flight read")
        assertEquals(DoorAvailability.Releasing(1), controller.state.value.availability)
        assertTrue("unregister" in sdk.calls)
        sdk.completeRead(100)
        assertEquals("release", sdk.calls.last())
        assertEquals(DoorAvailability.Released, controller.state.value.availability)
        assertEquals(DoorPosition.Unknown, controller.state.value.position)
    }

    @Test fun release_during_capability_never_registers_later() {
        controller.start()
        controller.release()
        sdk.completeCapability()
        assertFalse("register" in sdk.calls)
        assertEquals(DoorAvailability.Released, controller.state.value.availability)
    }

    @Test fun release_cancels_a_pending_retry() {
        controller.start()
        sdk.failCapability(conn)
        val before = sdk.calls.count { it == "capability" }
        controller.release()
        assertEquals(before, sdk.calls.count { it == "capability" })
        assertEquals(DoorAvailability.Released, controller.state.value.availability)
    }

    @Test fun listener_after_release_is_discarded() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        controller.suggestions.onEach { seen += it }.launchIn(scope)
        ready(); sdk.completeRead(0)
        val staleHandle = sdk.handle!!
        controller.release()
        staleHandle.sink.onPosition(100)
        assertTrue(seen.isEmpty())
        assertEquals(DoorAvailability.Released, controller.state.value.availability)
        scope.cancel()
    }

    @Test fun late_callback_from_old_session_cannot_touch_the_new_session() {
        val first = FakeDoorGateway(); val second = FakeDoorGateway()
        val gateways = ArrayDeque(listOf(first, second))
        val c = DoorController({ gateways.removeFirst() }, ManualScheduler(), DoorAreaKey.REAR)
        c.start(); first.completeCapability()
        c.release()
        c.start(); second.completeCapability(); second.completeRead(0)
        first.completeRead(100)
        assertEquals("release", first.calls.last())
        assertEquals(DoorAvailability.Available(0, 100), c.state.value.availability)
        assertEquals(DoorPosition.Closed, c.state.value.position)
    }

    @Test fun stuck_in_flight_reports_but_still_withholds_sdk_release() {
        ready()
        controller.release()
        scheduler.runNext()
        assertEquals(FailureKind.RELEASE_STUCK, assertIs<DoorAvailability.Failed>(controller.state.value.availability).kind)
        assertFalse("release" in sdk.calls)
        sdk.completeRead(0)
        assertEquals("release", sdk.calls.last())
        assertEquals(DoorAvailability.Released, controller.state.value.availability)
    }

    @Test fun duplicate_callback_cannot_drive_in_flight_negative() {
        ready()
        sdk.completeRead(0)
        sdk.completeRead(0)
        controller.release()
        assertEquals("release", sdk.calls.last())
    }

    private fun collecting(block: (MutableList<DoorSuggestion>) -> Unit) {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        controller.suggestions.onEach { seen += it }.launchIn(scope)
        block(seen)
        scope.cancel()
    }

    @Test fun listener_error_ends_trust_so_the_same_listener_cannot_suggest_or_move_position() = collecting { seen ->
        ready(); sdk.completeRead(0)
        val link = sdk.handle!!
        link.onFailed(SecurityException("revoked"))
        assertEquals(DoorAvailability.PermissionMissing, controller.state.value.availability)
        assertTrue("unregister" in sdk.calls)
        link.sink.onPosition(100)
        link.sink.onPosition(0)
        assertTrue(seen.isEmpty())
        assertEquals(DoorState(DoorAvailability.PermissionMissing, DoorPosition.Unknown), controller.state.value)
    }

    @Test fun unclassified_listener_error_also_ends_trust() = collecting { seen ->
        ready(); sdk.completeRead(0)
        sdk.handle!!.onFailed(Exception("boom"))
        sdk.emit(100)
        assertTrue(seen.isEmpty())
        assertEquals(FailureKind.UNCLASSIFIED, assertIs<DoorAvailability.Failed>(controller.state.value.availability).kind)
    }

    @Test fun read_error_ends_trust_like_a_listener_error() = collecting { seen ->
        ready()
        sdk.emit(0)
        sdk.failRead(SecurityException("revoked"))
        sdk.emit(100)
        assertTrue(seen.isEmpty())
        assertEquals(DoorAvailability.PermissionMissing, controller.state.value.availability)
    }

    @Test fun late_read_after_listener_error_cannot_publish_a_position() {
        ready()
        sdk.handle!!.onFailed(SecurityException("revoked"))
        sdk.completeRead(100)
        assertEquals(DoorState(DoorAvailability.PermissionMissing, DoorPosition.Unknown), controller.state.value)
    }

    @Test fun during_lost_connection_the_old_listener_is_silent() = collecting { seen ->
        ready(); sdk.completeRead(0)
        val old = sdk.handle!!
        old.onFailed(conn)
        old.sink.onPosition(100)
        assertTrue(seen.isEmpty())
        assertEquals(DoorPosition.Unknown, controller.state.value.position)
    }

    @Test fun late_callback_of_the_old_registration_cannot_become_baseline_after_reconnect() = collecting { seen ->
        ready(); sdk.completeRead(0)
        val old = sdk.handle!!
        val oldRead = sdk.pendingRead!!
        old.onFailed(conn)
        sdk.completeCapability()
        val fresh = sdk.handle!!
        assertTrue(fresh !== old, "each registration needs its own identity")
        // Stale open from the old registration, then the true closed value: without identity this is a false edge.
        old.sink.onPosition(100)
        oldRead.first(100)
        assertEquals(DoorPosition.Unknown, controller.state.value.position)
        fresh.sink.onPosition(0)
        assertEquals(DoorPosition.Closed, controller.state.value.position)
        assertTrue(seen.isEmpty())
        fresh.sink.onPosition(100)
        assertEquals(listOf(DoorSuggestion.SHOW_LOADING_CHECKLIST), seen)
    }

    @Test fun repeated_failure_of_a_dead_link_does_not_start_a_second_reconnect() {
        ready(); sdk.completeRead(0)
        val old = sdk.handle!!
        old.onFailed(conn)
        val attempts = sdk.calls.count { it == "capability" }
        old.onFailed(conn)
        assertEquals(attempts, sdk.calls.count { it == "capability" })
    }

    /** The real SDK was not observed doing this. Nothing documents that it cannot, so the boundary is held in fixture. */
    private class InlineRegister(val fake: FakeDoorGateway, val during: (DoorListenerHandle) -> Unit) : DoorGateway by fake {
        var rounds = 0
        override fun register(area: DoorAreaKey, handle: DoorListenerHandle) {
            fake.register(area, handle)
            rounds++
            during(handle)
        }
    }

    private fun inline(during: (DoorListenerHandle) -> Unit): Triple<FakeDoorGateway, InlineRegister, DoorController> {
        val fake = FakeDoorGateway()
        val gateway = InlineRegister(fake, during)
        return Triple(fake, gateway, DoorController({ gateway }, scheduler, DoorAreaKey.REAR))
    }

    @Test fun inline_permission_failure_during_register_wins_over_success() {
        val (fake, _, c) = inline { it.onFailed(SecurityException("registration denied")) }
        c.start(); fake.completeCapability()
        assertEquals(DoorState(DoorAvailability.PermissionMissing, DoorPosition.Unknown), c.state.value)
        assertFalse("read" in fake.calls, "no read may start on a failed link")
        assertTrue("unregister" in fake.calls)
    }

    @Test fun inline_unclassified_failure_during_register_is_failed() {
        val (fake, _, c) = inline { it.onFailed(Exception("boom")) }
        c.start(); fake.completeCapability()
        assertEquals(FailureKind.UNCLASSIFIED, assertIs<DoorAvailability.Failed>(c.state.value.availability).kind)
        assertFalse("read" in fake.calls)
    }

    @Test fun inline_lost_connection_retries_and_reaches_available_when_it_stops_failing() {
        var failing = true
        val (fake, _, c) = inline { if (failing) it.onFailed(conn) }
        c.start(); fake.completeCapability()
        assertEquals(DoorAvailability.NotReady(NotReadyReason.CONNECTION_LOST, 1), c.state.value.availability)
        assertFalse("read" in fake.calls)
        failing = false
        fake.completeCapability()
        assertEquals(DoorAvailability.Available(0, 100), c.state.value.availability)
        assertEquals(1, fake.calls.count { it == "read" })
    }

    @Test fun register_that_keeps_losing_connection_ends_typed_and_does_not_spin() {
        val (fake, gateway, c) = inline { it.onFailed(conn) }
        c.start()
        var rounds = 0
        while (c.state.value.availability !is DoorAvailability.Failed && rounds++ < 30) {
            fake.completeCapability()
            if (scheduler.pending.isNotEmpty()) scheduler.runNext()
        }
        assertEquals(FailureKind.CONNECTION_TIMEOUT, assertIs<DoorAvailability.Failed>(c.state.value.availability).kind)
        assertEquals(7, gateway.rounds, "first register, one immediate reconnect, then the five bounded backoff waits")
        assertFalse("read" in fake.calls)
    }

    @Test fun register_throw_is_typed_for_each_failure_kind_and_starts_no_read() {
        val denied = FakeDoorGateway().apply { registerThrows = SecurityException("x") }
        val c = DoorController({ denied }, ManualScheduler(), DoorAreaKey.REAR)
        c.start(); denied.completeCapability()
        assertEquals(DoorAvailability.PermissionMissing, c.state.value.availability)
        assertFalse("read" in denied.calls)
        val lost = FakeDoorGateway().apply { registerThrows = conn }
        val d = DoorController({ lost }, ManualScheduler(), DoorAreaKey.REAR)
        d.start(); lost.completeCapability()
        assertEquals(DoorAvailability.NotReady(NotReadyReason.CONNECTION_LOST, 1), d.state.value.availability)
        lost.registerThrows = null
        lost.completeCapability()
        assertEquals(DoorAvailability.Available(0, 100), d.state.value.availability)
    }

    @Test fun inline_value_during_register_is_a_baseline_and_only_later_edges_suggest() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val (fake, _, c) = inline { it.sink.onPosition(0); it.sink.onPosition(100) }
        c.suggestions.onEach { seen += it }.launchIn(scope)
        c.start(); fake.completeCapability()
        assertTrue(seen.isEmpty(), "nothing may be suggested before the link is declared usable")
        assertEquals(DoorState(DoorAvailability.Available(0, 100), DoorPosition.Open), c.state.value)
        fake.emit(0)
        assertEquals(listOf(DoorSuggestion.OFFER_SAVE_CONFIRMATION), seen)
        scope.cancel()
    }

    @Test fun inline_failure_after_an_inline_value_leaves_no_position_or_suggestion() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val (fake, _, c) = inline { it.sink.onPosition(100); it.onFailed(SecurityException("x")) }
        c.suggestions.onEach { seen += it }.launchIn(scope)
        c.start(); fake.completeCapability()
        assertEquals(DoorState(DoorAvailability.PermissionMissing, DoorPosition.Unknown), c.state.value)
        assertTrue(seen.isEmpty())
        scope.cancel()
    }

    @Test fun release_after_failed_registration_still_releases_the_sdk() {
        val (fake, _, c) = inline { it.onFailed(SecurityException("x")) }
        c.start(); fake.completeCapability()
        c.release()
        assertEquals("release", fake.calls.last())
        assertEquals(DoorAvailability.Released, c.state.value.availability)
    }

    /** Fixture: the real SDK was not observed calling back from unregister. Nothing documents that it cannot. */
    private class InlineUnregister(val fake: FakeDoorGateway, val during: (DoorListenerHandle) -> Unit) : DoorGateway by fake {
        var armed = true
        override fun unregister(area: DoorAreaKey, handle: DoorListenerHandle) {
            fake.unregister(area, handle)
            if (armed) { armed = false; during(handle) }
        }
    }

    private fun duringUnregister(during: (DoorListenerHandle) -> Unit): Pair<FakeDoorGateway, DoorController> {
        val fake = FakeDoorGateway()
        return fake to DoorController({ InlineUnregister(fake, during) }, scheduler, DoorAreaKey.REAR)
    }

    private fun readyAtClosed(fake: FakeDoorGateway, c: DoorController) {
        c.start(); fake.completeCapability(); fake.completeRead(0)
    }

    @Test fun value_delivered_from_unregister_after_an_error_cannot_suggest() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val (fake, c) = duringUnregister { it.sink.onPosition(100) }
        c.suggestions.onEach { seen += it }.launchIn(scope)
        readyAtClosed(fake, c)
        fake.handle!!.onFailed(SecurityException("revoked"))
        assertTrue(seen.isEmpty())
        assertEquals(DoorState(DoorAvailability.PermissionMissing, DoorPosition.Unknown), c.state.value)
        scope.cancel()
    }

    @Test fun error_delivered_from_unregister_cannot_replace_the_first_outcome() {
        val (fake, c) = duringUnregister { it.onFailed(Exception("late boom")) }
        readyAtClosed(fake, c)
        fake.handle!!.onFailed(SecurityException("revoked"))
        assertEquals(DoorAvailability.PermissionMissing, c.state.value.availability)
    }

    @Test fun connection_loss_delivered_from_unregister_cannot_start_a_second_reconnect() {
        val (fake, c) = duringUnregister { it.onFailed(conn) }
        readyAtClosed(fake, c)
        val before = fake.calls.count { it == "capability" }
        fake.handle!!.onFailed(conn)
        assertEquals(before + 1, fake.calls.count { it == "capability" })
        assertEquals(DoorAvailability.NotReady(NotReadyReason.CONNECTION_LOST, 1), c.state.value.availability)
    }

    @Test fun value_delivered_from_unregister_during_release_changes_nothing() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val (fake, c) = duringUnregister { it.sink.onPosition(100); it.onFailed(conn) }
        c.suggestions.onEach { seen += it }.launchIn(scope)
        readyAtClosed(fake, c)
        c.release()
        assertTrue(seen.isEmpty())
        assertEquals(DoorAvailability.Released, c.state.value.availability)
        assertEquals(1, fake.calls.count { it == "capability" })
        assertEquals("release", fake.calls.last())
    }

    @Test fun value_delivered_from_unregister_cannot_seed_the_baseline_after_reconnect() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val (fake, c) = duringUnregister { it.sink.onPosition(100) }
        c.suggestions.onEach { seen += it }.launchIn(scope)
        readyAtClosed(fake, c)
        fake.handle!!.onFailed(conn)
        fake.completeCapability()
        fake.emit(0)
        assertEquals(DoorPosition.Closed, c.state.value.position)
        assertTrue(seen.isEmpty(), "closed after reconnect is a baseline, not a close edge from the stale open")
        fake.emit(100)
        assertEquals(listOf(DoorSuggestion.SHOW_LOADING_CHECKLIST), seen)
        scope.cancel()
    }

    @Test fun release_from_a_state_collector_stops_the_suggestion_of_that_same_value() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        ready(); sdk.completeRead(0)
        controller.suggestions.onEach { seen += it }.launchIn(scope)
        controller.state.onEach { if (it.position == DoorPosition.Open) controller.release() }.launchIn(scope)
        sdk.emit(100)
        assertTrue(seen.isEmpty())
        assertIs<DoorAvailability.Released>(controller.state.value.availability)
        scope.cancel()
    }

    /** Fixture: release or restart from an SDK call or a state collector. Not observed on the real SDK. */
    @Test fun release_during_initialize_requests_nothing_and_still_releases_the_sdk() {
        lateinit var c: DoorController
        val fake = FakeDoorGateway()
        val gateway = object : DoorGateway by fake {
            override fun initialize() { fake.initialize(); c.release() }
        }
        c = DoorController({ gateway }, scheduler, DoorAreaKey.REAR)
        c.start()
        assertFalse("capability" in fake.calls)
        assertEquals("release", fake.calls.last())
        assertEquals(DoorAvailability.Released, c.state.value.availability)
    }

    @Test fun release_from_a_collector_on_not_ready_never_initializes() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        controller.state.onEach { if (it.availability == DoorAvailability.NotReady(NotReadyReason.SERVICE_NOT_CONNECTED, 0)) controller.release() }.launchIn(scope)
        controller.start()
        assertFalse("initialize" in sdk.calls)
        assertEquals(DoorAvailability.Released, controller.state.value.availability)
        scope.cancel()
    }

    @Test fun release_from_a_collector_on_available_starts_no_read() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        controller.state.onEach { if (it.availability is DoorAvailability.Available) controller.release() }.launchIn(scope)
        ready()
        assertFalse("read" in sdk.calls)
        assertEquals("release", sdk.calls.last())
        assertEquals(DoorAvailability.Released, controller.state.value.availability)
        scope.cancel()
    }

    @Test fun release_from_unregister_during_a_failure_writes_no_failure_afterwards() {
        lateinit var c: DoorController
        val fake = FakeDoorGateway()
        val gateway = object : DoorGateway by fake {
            override fun unregister(area: DoorAreaKey, handle: DoorListenerHandle) { fake.unregister(area, handle); c.release() }
        }
        c = DoorController({ gateway }, scheduler, DoorAreaKey.REAR)
        c.start(); fake.completeCapability(); fake.completeRead(0)
        fake.handle!!.onFailed(SecurityException("x"))
        assertEquals(DoorAvailability.Released, c.state.value.availability)
    }

    @Test fun restart_from_unregister_during_release_keeps_the_new_session_state() {
        lateinit var c: DoorController
        val first = FakeDoorGateway(); val second = FakeDoorGateway()
        var restarted = false
        val wrapped = object : DoorGateway by first {
            override fun unregister(area: DoorAreaKey, handle: DoorListenerHandle) {
                first.unregister(area, handle)
                if (!restarted) { restarted = true; c.start() }
            }
        }
        val gateways = ArrayDeque<DoorGateway>(listOf(wrapped, second))
        c = DoorController({ gateways.removeFirst() }, ManualScheduler(), DoorAreaKey.REAR)
        c.start(); first.completeCapability(); first.completeRead(0)
        c.release()
        assertEquals(DoorAvailability.NotReady(NotReadyReason.SERVICE_NOT_CONNECTED, 0), c.state.value.availability)
        assertEquals("release", first.calls.last())
        second.completeCapability()
        assertEquals(DoorAvailability.Available(0, 100), c.state.value.availability)
    }

    @Test fun connection_lost_reconnects_without_a_false_edge() {
        val seen = ArrayList<DoorSuggestion>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        controller.suggestions.onEach { seen += it }.launchIn(scope)
        ready(); sdk.completeRead(0)
        sdk.handle!!.onFailed(conn)
        assertEquals(DoorAvailability.NotReady(NotReadyReason.CONNECTION_LOST, 1), controller.state.value.availability)
        sdk.completeCapability()
        assertEquals(listOf("unregister"), sdk.calls.filter { it == "unregister" })
        sdk.emit(100)
        assertTrue(seen.isEmpty(), "open observed after reconnect has no known origin")
        scope.cancel()
    }
}
