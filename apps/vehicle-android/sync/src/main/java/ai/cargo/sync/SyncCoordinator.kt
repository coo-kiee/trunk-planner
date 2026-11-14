package ai.cargo.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Runs poll cycles only while the app says it is in the foreground. Vehicle flows never wait on this. */
class SyncCoordinator(private val engine: SyncEngine, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val mutable = MutableStateFlow<SyncStatus>(SyncStatus.NotPaired)
    private var job: Job? = null
    val status: StateFlow<SyncStatus> = mutable.asStateFlow()

    fun onForeground() {
        if (job?.isActive == true) return
        job = scope.launch(io) {
            var failures = 0
            while (isActive) {
                val report = engine.poll()
                mutable.value = report.status
                val failed = report.status is SyncStatus.NetworkUnavailable || report.status is SyncStatus.Throttled
                failures = if (failed) failures + 1 else 0
                delay(ForegroundRetryPolicy.delayMs(failures, (report.status as? SyncStatus.Throttled)?.retryAfterSeconds))
            }
        }
    }

    fun onBackground() {
        job?.cancel()
        job = null
    }
}
