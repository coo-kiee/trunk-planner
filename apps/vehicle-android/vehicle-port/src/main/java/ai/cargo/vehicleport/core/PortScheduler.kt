package ai.cargo.vehicleport.core

import android.os.Handler
import android.os.Looper

/** Delay seam so that backoff is event driven and testable. Returns a cancel action. */
fun interface PortScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

class HandlerPortScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : PortScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}

/** Waits are the retry interval after an actual negative answer; none of them assumes readiness. */
val READY_BACKOFF_MS = listOf(250L, 500L, 1_000L, 2_000L, 4_000L)
const val RELEASE_STUCK_MS = 10_000L
