package ai.cargo.data

import ai.cargo.contracts.Vec3Mm
import ai.cargo.data.Seed.apply
import ai.cargo.data.migration.MigrationFaultHook
import ai.cargo.data.migration.MigrationPoint
import ai.cargo.scene.ItemRef
import ai.cargo.scene.SceneCommand
import ai.cargo.scene.SceneDraftState
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import java.io.File

/**
 * Runs in its own OS process. It performs a real store operation and signals through a marker file when it reaches
 * the requested point; the test process then sends SIGKILL to this pid and inspects what the database kept.
 */
class WriterService : Service() {
    override fun onBind(intent: Intent): IBinder {
        Thread { runScenario(intent) }.start()
        return Binder()
    }

    private fun runScenario(intent: Intent) {
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)!!
        val database = intent.getStringExtra(EXTRA_DATABASE)!!
        val point = intent.getStringExtra(EXTRA_POINT)
        val marker = File(intent.getStringExtra(EXTRA_MARKER)!!)
        when (scenario) {
            SAVE_AT -> {
                val store = Seed.open(this, database)
                store.scenes.save(Seed.secondRevision(store)) { reached ->
                    if (reached.name == point) park(marker)
                }
                park(marker)
            }
            SAVE_LOOP -> saveLoop(database, marker)
            DRAFT_EDITS -> {
                val store = Seed.open(this, database)
                store.drafts.commit(editedDraft(store), Seed.T)
                park(marker)
            }
            SEARCH_RUNNING -> {
                val store = Seed.open(this, database)
                val context = store.library.sceneContext(Seed.T)
                val head = (store.scenes.load(Seed.SCENE, Seed.T) as SceneLoad.Healthy).revision
                val searching = SceneDraftState.fromRevision(head).apply(SceneCommand.BeginSearch("kill-search", ItemRef(Seed.ITEM_B, 1)), context)
                store.drafts.commit(searching, Seed.T)
                park(marker)
            }
            MIGRATE_AT -> {
                StoreOpener.open(this, database, 2_000, faults = MigrationFaultHook { reached: MigrationPoint ->
                    if (reached.name == point) park(marker)
                })
                park(marker)
            }
            else -> error("Unknown scenario $scenario")
        }
    }

    /** Saves continuously so that the kill lands at an unplanned moment. */
    private fun saveLoop(database: String, marker: File) {
        val store = Seed.open(this, database)
        signal(marker)
        var x = 400L
        while (true) {
            val context = store.library.sceneContext(Seed.T)
            val head = (store.scenes.load(Seed.SCENE, Seed.T) as SceneLoad.Healthy).revision
            x = if (x >= 1500) 400 else x + 1
            var draft = SceneDraftState.fromRevision(head)
            draft = if (draft.instances.any { it.instanceId == Seed.INST_B }) {
                draft.apply(SceneCommand.Move(Seed.INST_B, Vec3Mm(x, 0, 0)), context)
            } else {
                draft.apply(SceneCommand.Add(Seed.INST_B, ItemRef(Seed.ITEM_B, 1), Vec3Mm(x, 0, 0), 0), context)
            }
            store.drafts.commit(draft, Seed.T)
            check(store.scenes.save(Seed.prepare(draft, head.revision, context)) is SaveResult.Saved)
        }
    }

    private fun signal(marker: File) {
        val temporary = File(marker.path + ".tmp")
        temporary.writeText(Process.myPid().toString())
        temporary.renameTo(marker)
    }

    private fun park(marker: File): Nothing {
        signal(marker)
        while (true) Thread.sleep(60_000)
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_DATABASE = "database"
        const val EXTRA_POINT = "point"
        const val EXTRA_MARKER = "marker"
        const val SAVE_AT = "SAVE_AT"
        const val SAVE_LOOP = "SAVE_LOOP"
        const val DRAFT_EDITS = "DRAFT_EDITS"
        const val SEARCH_RUNNING = "SEARCH_RUNNING"
        const val MIGRATE_AT = "MIGRATE_AT"

        /** The exact edit sequence both processes compute: two moves, an undo, and a camera change. */
        fun editedDraft(store: CargoStore): SceneDraftState {
            val context = store.library.sceneContext(Seed.T)
            val head = (store.scenes.load(Seed.SCENE, Seed.T) as SceneLoad.Healthy).revision
            return SceneDraftState.fromRevision(head)
                .apply(SceneCommand.Add(Seed.INST_B, ItemRef(Seed.ITEM_B, 1), Vec3Mm(400, 0, 0), 0), context)
                .apply(SceneCommand.Move(Seed.INST_B, Vec3Mm(500, 0, 0)), context)
                .apply(SceneCommand.Move(Seed.INST_B, Vec3Mm(600, 0, 0)), context)
                .apply(SceneCommand.Undo, context)
                .apply(SceneCommand.SetCamera(SceneDraftState.DEFAULT_CAMERA.copy(yawDegrees = 37, pitchDegrees = 12, distanceMm = 2500)), context)
        }
    }
}
