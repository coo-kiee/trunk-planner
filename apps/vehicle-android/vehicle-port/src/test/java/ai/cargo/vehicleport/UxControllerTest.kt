package ai.cargo.vehicleport

import ai.cargo.vehicleport.api.UxRestrictionState
import ai.cargo.vehicleport.api.UxUnavailableReason
import ai.cargo.vehicleport.core.UxController
import ai.cargo.vehicleport.gateway.UxConnectResult
import ai.cargo.vehicleport.gateway.UxGateway
import ai.cargo.vehicleport.gateway.UxSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UxControllerTest {
    private fun assertClosed(state: UxRestrictionState) {
        assertFalse(state.editingAllowed); assertFalse(state.qrAllowed); assertTrue(state.renderStop)
    }

    @Test fun gate_is_requires_do_and_the_raw_mask_is_preserved() {
        val sdk = FakeUxGateway(sample = UxSample(false, 80))
        val c = UxController { sdk }
        c.start()
        val allowed = assertIs<UxRestrictionState.Known>(c.state.value)
        assertEquals(80, allowed.rawRestrictionMask)
        assertTrue(allowed.editingAllowed); assertTrue(allowed.qrAllowed); assertFalse(allowed.renderStop)
        sdk.listener!!(UxSample(true, 80))
        assertClosed(c.state.value)
        assertEquals(80, assertIs<UxRestrictionState.Known>(c.state.value).rawRestrictionMask)
        sdk.listener!!(UxSample(false, 0))
        assertTrue(c.state.value.editingAllowed)
    }

    @Test fun not_started_and_every_unavailable_form_fail_closed() {
        val c = UxController { FakeUxGateway() }
        assertClosed(c.state.value)
        listOf(UxConnectResult.CAR_NULL to UxUnavailableReason.CAR_NULL, UxConnectResult.MANAGER_NULL to UxUnavailableReason.MANAGER_NULL).forEach { (result, reason) ->
            val u = UxController { FakeUxGateway(result) }
            u.start()
            assertEquals(reason, assertIs<UxRestrictionState.Unavailable>(u.state.value).reason)
            assertClosed(u.state.value)
        }
    }

    @Test fun null_restrictions_fail_closed_at_read_and_in_callback() {
        val u = UxController { FakeUxGateway(sample = null) }
        u.start()
        assertEquals(UxUnavailableReason.RESTRICTIONS_NULL, assertIs<UxRestrictionState.Unavailable>(u.state.value).reason)
        val sdk = FakeUxGateway()
        val v = UxController { sdk }
        v.start()
        sdk.listener!!(null)
        assertClosed(v.state.value)
    }

    @Test fun failure_types_stay_distinct_and_are_not_fatal() {
        val denied = UxController { throw SecurityException("x") }
        denied.start()
        assertEquals(UxUnavailableReason.PERMISSION_MISSING, assertIs<UxRestrictionState.Unavailable>(denied.state.value).reason)
        val other = UxController { throw IllegalStateException("x") }
        other.start()
        assertEquals(UxUnavailableReason.FAILED, assertIs<UxRestrictionState.Unavailable>(other.state.value).reason)
    }

    /** Records every gate value, so that a brief reopening is caught and not only the final state. */
    private class ReentrantUx(
        val base: FakeUxGateway = FakeUxGateway(),
        val onConnect: () -> Unit = {},
        val onRegister: (((UxSample?) -> Unit)) -> Unit = {},
        val onCurrent: () -> UxSample? = { UxSample(false, 0) },
    ) : UxGateway by base {
        override fun connect(): UxConnectResult { onConnect(); return UxConnectResult.CONNECTED }
        override fun register(listener: (UxSample?) -> Unit) { base.listeners++; onRegister(listener) }
        override fun current() = onCurrent()
    }

    private fun assertReleasedAndClosed(c: UxController) {
        assertEquals(UxUnavailableReason.RELEASED, assertIs<UxRestrictionState.Unavailable>(c.state.value).reason)
        assertClosed(c.state.value)
    }

    @Test fun release_from_a_collector_during_inline_register_is_not_overwritten_by_current() {
        var currentRead = false
        val sdk = ReentrantUx(onRegister = { it(UxSample(true, 7)) }, onCurrent = { currentRead = true; UxSample(false, 0) })
        val c = UxController { sdk }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        c.state.onEach { if (it is UxRestrictionState.Known && it.requiresDistractionOptimization) c.release() }.launchIn(scope)
        c.start()
        assertReleasedAndClosed(c)
        assertFalse(currentRead, "a released attempt must not touch the SDK again")
        assertEquals(listOf("unregister", "disconnect"), sdk.base.calls)
        scope.cancel()
    }

    @Test fun release_during_connect_stops_the_attempt_and_still_disconnects() {
        lateinit var c: UxController
        val sdk = ReentrantUx(onConnect = { c.release() })
        c = UxController { sdk }
        c.start()
        assertReleasedAndClosed(c)
        assertEquals(0, sdk.base.listeners)
        assertEquals(listOf("unregister", "disconnect"), sdk.base.calls)
    }

    @Test fun release_during_current_is_not_overwritten_by_its_value() {
        lateinit var c: UxController
        val sdk = ReentrantUx(onCurrent = { c.release(); UxSample(false, 0) })
        c = UxController { sdk }
        c.start()
        assertReleasedAndClosed(c)
    }

    @Test fun late_error_of_a_released_attempt_does_not_replace_released() {
        lateinit var c: UxController
        val during = ReentrantUx(onRegister = { c.release(); throw IllegalStateException("late") })
        c = UxController { during }
        c.start()
        assertReleasedAndClosed(c)
        val fromCurrent = ReentrantUx(onCurrent = { c.release(); throw SecurityException("late") })
        c = UxController { fromCurrent }
        c.start()
        assertReleasedAndClosed(c)
    }

    @Test fun new_start_from_inside_the_old_attempt_owns_the_state_and_old_continuation_writes_nothing() {
        lateinit var c: UxController
        val second = FakeUxGateway(sample = UxSample(true, 80))
        val first = ReentrantUx(onRegister = { c.release(); c.start() }, onCurrent = { UxSample(false, 0) })
        val factories = ArrayDeque<UxGateway>(listOf(first, second))
        c = UxController { factories.removeFirst() }
        c.start()
        assertEquals(UxRestrictionState.Known(true, 80), c.state.value)
        assertEquals(listOf("connect"), second.calls, "the new gateway stays connected")
        assertEquals(listOf("unregister", "disconnect"), first.base.calls)
        val staleListener = second.listener!!
        staleListener(UxSample(true, 81))
        assertEquals(UxRestrictionState.Known(true, 81), c.state.value)
    }

    @Test fun restart_from_inside_unregister_is_not_overwritten_by_released() {
        lateinit var c: UxController
        val second = FakeUxGateway(sample = UxSample(true, 80))
        var restarted = false
        val first = object : UxGateway by FakeUxGateway() {
            override fun unregister() { if (!restarted) { restarted = true; c.start() } }
        }
        val factories = ArrayDeque<UxGateway>(listOf(first, second))
        c = UxController { factories.removeFirst() }
        c.start()
        c.release()
        assertEquals(UxRestrictionState.Known(true, 80), c.state.value)
    }

    @Test fun normal_start_release_restart_still_works() {
        val first = FakeUxGateway(); val second = FakeUxGateway(sample = UxSample(true, 80))
        val factories = ArrayDeque(listOf(first, second))
        val c = UxController { factories.removeFirst() }
        c.start()
        assertTrue(c.state.value.editingAllowed)
        c.release()
        assertReleasedAndClosed(c)
        c.start()
        assertEquals(UxRestrictionState.Known(true, 80), c.state.value)
    }

    /** Fixture: re-entrant and leftover callbacks were not observed on the real manager. */
    @Test fun leftover_listener_of_a_failed_start_cannot_reopen_the_gate() {
        val sdk = object : UxGateway by FakeUxGateway() {
            var callback: ((UxSample?) -> Unit)? = null
            override fun register(listener: (UxSample?) -> Unit) { callback = listener }
            override fun current(): UxSample? = throw IllegalStateException("current failed")
        }
        val c = UxController { sdk }
        c.start()
        assertEquals(UxUnavailableReason.FAILED, assertIs<UxRestrictionState.Unavailable>(c.state.value).reason)
        sdk.callback!!(UxSample(false, 0))
        assertClosed(c.state.value)
    }

    @Test fun callback_delivered_from_unregister_during_release_cannot_reopen_the_gate() {
        var late: ((UxSample?) -> Unit)? = null
        val base = FakeUxGateway()
        val sdk = object : UxGateway by base {
            override fun register(listener: (UxSample?) -> Unit) { late = listener }
            override fun unregister() { late!!(UxSample(false, 0)) }
        }
        val c = UxController { sdk }
        c.start()
        c.release()
        assertEquals(UxUnavailableReason.RELEASED, assertIs<UxRestrictionState.Unavailable>(c.state.value).reason)
        assertClosed(c.state.value)
    }

    @Test fun single_listener_and_late_callback_after_release_is_discarded() {
        val sdk = FakeUxGateway()
        val c = UxController { sdk }
        c.start(); c.start()
        assertEquals(1, sdk.listeners)
        val late = sdk.listener!!
        c.release()
        late(UxSample(false, 0))
        assertEquals(UxUnavailableReason.RELEASED, assertIs<UxRestrictionState.Unavailable>(c.state.value).reason)
        assertClosed(c.state.value)
        assertEquals(listOf("connect", "unregister", "disconnect"), sdk.calls)
    }
}
