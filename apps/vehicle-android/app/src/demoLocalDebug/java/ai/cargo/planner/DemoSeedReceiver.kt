package ai.cargo.planner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Runs one named seed in this process. Requires the shell's DUMP permission, and exists only in this variant. */
class DemoSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("seed") ?: return
        val pending = goAsync()
        // Room refuses the main thread, and the shell waits for this result before it goes on.
        Thread {
            val outcome = runCatching { DemoSeeds(context.applicationContext as CargoApp).run(name) }
            val text = outcome.fold({ "SEED_DONE $name $it" }, { "SEED_FAILED $name ${it.javaClass.simpleName}: ${it.message}" })
            Log.i("CargoSeed", text)
            pending.setResult(0, text, null)
            pending.finish()
        }.start()
    }
}
