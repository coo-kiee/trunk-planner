package ai.cargo.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import kotlin.test.assertEquals

/** The loop exists only between foreground and background. Fixture grade. */
class CoordinatorTest {
    @Test fun ce20_polls_only_while_foreground_and_reports_network_state() {
        val rig = Rig()
        val engine = rig.paired()
        rig.relay.down = true
        val scope = CoroutineScope(SupervisorJob())
        val coordinator = SyncCoordinator(engine, scope, Dispatchers.Default)
        coordinator.onForeground()
        val deadline = System.currentTimeMillis() + 5_000
        while (coordinator.status.value != SyncStatus.NetworkUnavailable(PAIRING) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(SyncStatus.NetworkUnavailable(PAIRING), coordinator.status.value)
        coordinator.onBackground()
        Thread.sleep(50)
        val after = rig.relay.requests.size
        Thread.sleep(2_500)
        assertEquals(after, rig.relay.requests.size, "backoff of 2s has passed; a live loop would have polled again")
        scope.cancel()
    }
}
