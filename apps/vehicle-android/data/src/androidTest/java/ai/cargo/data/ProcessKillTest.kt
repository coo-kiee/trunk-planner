package ai.cargo.data

import ai.cargo.contracts.Vec3Mm
import ai.cargo.data.Seed.INST_A
import ai.cargo.data.Seed.INST_B
import ai.cargo.data.Seed.SCENE
import ai.cargo.data.Seed.T
import ai.cargo.data.db.CargoDatabase
import ai.cargo.contracts.SearchStatus
import ai.cargo.scene.SceneChecksum
import ai.cargo.scene.SceneJson
import ai.cargo.scene.SearchInterruptCause
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real process death: a separate OS process owned by this test package runs the store and receives SIGKILL from
 * Process.killProcess at a chosen or seeded moment. This is process death on an emulator, not power loss.
 */
@RunWith(AndroidJUnit4::class)
class ProcessKillTest : StoreTestBase() {
    private val markerDir: File get() = File(context.filesDir, "kill-markers").apply { mkdirs() }

    @After fun cleanup() = removeCreated()

    private class Connection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    /** Starts the writer and waits until it signals. Returns (pid, connection). */
    private fun launch(scenario: String, database: String, point: String?): Pair<Int, Connection> {
        val marker = File(markerDir, "$database-${SystemClock.uptimeMillis()}")
        // Unique data makes this a new binding: Android reuses onBind results for equal intents and ignores extras.
        val intent = Intent(context, WriterService::class.java)
            .setData(Uri.parse("cargo-test://writer/" + marker.name))
            .putExtra(WriterService.EXTRA_SCENARIO, scenario)
            .putExtra(WriterService.EXTRA_DATABASE, database)
            .putExtra(WriterService.EXTRA_POINT, point)
            .putExtra(WriterService.EXTRA_MARKER, marker.path)
        val connection = Connection()
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE), "writer service bound")
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (!marker.isFile) {
            if (SystemClock.uptimeMillis() > deadline) fail("writer never reached $scenario/$point")
            SystemClock.sleep(10)
        }
        val pid = marker.readText().trim().toInt()
        assertNotEquals(Process.myPid(), pid, "writer is a different OS process")
        return pid to connection
    }

    /** Unbinds first, since a live binding would make the system restart the service after the kill. */
    private fun sigkill(pid: Int, connection: Connection): Long {
        context.unbindService(connection)
        val started = SystemClock.uptimeMillis()
        Process.killProcess(pid)
        val deadline = started + 15_000
        while (File("/proc/$pid").exists()) {
            if (SystemClock.uptimeMillis() > deadline) fail("pid $pid survived SIGKILL")
            SystemClock.sleep(5)
        }
        val waited = SystemClock.uptimeMillis() - started
        awaitSystemNoticedDeath(pid, deadline)
        return waited
    }

    /**
     * /proc going away is not enough. Binding again before the system processes the death would target the dead
     * process record and fall into the service restart backoff, so the next test could wait on a writer that is late.
     */
    private fun awaitSystemNoticedDeath(pid: Int, deadline: Long) {
        val manager = context.getSystemService(ActivityManager::class.java)
        while (manager.runningAppProcesses.orEmpty().any { it.pid == pid }) {
            if (SystemClock.uptimeMillis() > deadline) fail("system still lists dead pid $pid")
            SystemClock.sleep(5)
        }
    }

    private fun receipt(fields: Map<String, Any?>) {
        Log.i("CargoKillReceipt", JSONObject(fields).put("signal", "SIGKILL").put("controllerPid", Process.myPid()).toString())
    }

    private fun seeded(name: String) {
        val store = Seed.open(context, name)
        Seed.firstRevision(store)
        store.close()
    }

    private fun killDuringSave(point: SavePoint, expectedHead: Long) {
        val name = databaseName("kill-save")
        seeded(name)
        val (pid, connection) = launch(WriterService.SAVE_AT, name, point.name)
        val waitMs = sigkill(pid, connection)

        val store = Seed.open(context, name)
        assertTrue(store.integrityOk(), "integrity_check after kill at $point")
        val head = store.scenes.head(SCENE)
        val load = assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T))
        assertEquals(expectedHead, head, "kill at $point")
        assertEquals(head, load.revision.revision)
        assertTrue(SceneChecksum.verify(SceneJson.encodeRevision(load.revision)))
        val first = store.scenes.revision(SCENE, 1)!!
        assertEquals(Seed.ORIGIN, first.instances.first { it.instanceId == INST_A }.poseMm)
        assertEquals(if (expectedHead == 2L) load.revision else null, store.scenes.revision(SCENE, 2), "no orphan revision")
        store.close()
        receipt(mapOf("scenario" to "SAVE_AT", "point" to point.name, "writerPid" to pid, "deathWaitMs" to waitMs,
            "headAfter" to head, "integrity" to "ok", "checksumOk" to true))
    }

    @Test fun CE12_kill_after_revision_insert_leaves_old_head() = killDuringSave(SavePoint.REVISION_INSERTED, 1)
    @Test fun CE12_kill_after_head_update_before_commit_leaves_old_head() = killDuringSave(SavePoint.HEAD_UPDATED, 1)
    @Test fun CE12_kill_after_draft_replace_before_commit_leaves_old_head() = killDuringSave(SavePoint.DRAFT_REPLACED, 1)
    @Test fun CE12_kill_after_commit_leaves_new_head() = killDuringSave(SavePoint.COMMITTED, 2)

    /** Unplanned moment: a loop of drafts and saves is killed after a seeded delay. */
    @Test fun CE12_seeded_kill_timing_leaves_exactly_one_consistent_head() {
        for (seed in listOf(11L, 22L, 33L, 44L, 55L, 66L)) {
            val name = databaseName("kill-loop")
            seeded(name)
            val delay = 20L + Random(seed).nextInt(400)
            val (pid, connection) = launch(WriterService.SAVE_LOOP, name, null)
            SystemClock.sleep(delay)
            sigkill(pid, connection)

            val store = Seed.open(context, name)
            assertTrue(store.integrityOk(), "integrity seed=$seed")
            val load = assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T))
            val head = load.revision.revision
            assertEquals(null, store.scenes.revision(SCENE, head + 1), "no revision beyond the head, seed=$seed")
            for (revision in 1..head) {
                val row = store.scenes.revision(SCENE, revision)
                assertTrue(row != null, "revision $revision verifies, seed=$seed")
                assertEquals(Seed.ORIGIN, row.instances.first { it.instanceId == INST_A }.poseMm, "locked item fixed in $revision")
            }
            val draft = store.drafts.load(SCENE, T)
            if (draft is DraftLoad.Restored) assertEquals(head, draft.state.baseRevision, "draft base follows the head")
            assertTrue(draft !is DraftLoad.Damaged)
            store.close()
            receipt(mapOf("scenario" to "SAVE_LOOP", "seed" to seed, "delayMs" to delay, "writerPid" to pid, "headAfter" to head, "integrity" to "ok"))
        }
    }

    @Test fun CE15_committed_draft_undo_redo_camera_survive_process_death() {
        val name = databaseName("kill-draft")
        seeded(name)
        val (pid, connection) = launch(WriterService.DRAFT_EDITS, name, null)
        sigkill(pid, connection)
        val store = Seed.open(context, name)
        val restored = assertIs<DraftLoad.Restored>(store.drafts.load(SCENE, T)).state
        assertEquals(WriterService.editedDraft(store), restored, "exact state computed independently in this process")
        assertEquals(Vec3Mm(500, 0, 0), restored.instances.first { it.instanceId == INST_B }.poseMm)
        assertEquals(2, restored.undo.size)
        assertEquals(1, restored.redo.size)
        assertEquals(37, restored.camera.yawDegrees)
        assertEquals(1, store.scenes.head(SCENE), "edits alone never move the head")
        store.close()
        receipt(mapOf("scenario" to "DRAFT_EDITS", "writerPid" to pid, "undo" to restored.undo.size, "redo" to restored.redo.size,
            "yaw" to restored.camera.yawDegrees))
    }

    @Test fun CE06_search_marker_after_process_death_reads_interrupted() {
        val name = databaseName("kill-search")
        seeded(name)
        val (pid, connection) = launch(WriterService.SEARCH_RUNNING, name, null)
        sigkill(pid, connection)
        val store = Seed.open(context, name)
        val load = assertIs<DraftLoad.Restored>(store.drafts.load(SCENE, T))
        assertTrue(load.recoveredInterruptedSearch)
        assertEquals(null, load.state.pendingSearch)
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, load.state.lastSearch!!.status)
        assertEquals(SearchInterruptCause.PROCESS_DEATH, load.state.lastSearch!!.interruptCause)
        assertEquals(null, load.state.lastSearch!!.reason)
        // The conversion itself was persisted: a second open sees a settled result.
        store.close()
        val again = Seed.open(context, name)
        assertEquals(false, assertIs<DraftLoad.Restored>(again.drafts.load(SCENE, T)).recoveredInterruptedSearch)
        again.close()
        receipt(mapOf("scenario" to "SEARCH_RUNNING", "writerPid" to pid, "status" to "SEARCH_INTERRUPTED", "cause" to "PROCESS_DEATH"))
    }

    private fun killDuringMigration(point: String) {
        val name = databaseName("kill-migrate")
        val source = databaseName("kill-source")
        seeded(source)
        val json = Seed.open(context, source).let { s -> SceneJson.encodeRevision(s.scenes.revision(SCENE, 1)!!).also { s.close() } }
        val file = databaseFile(name)
        createV1(file, json)
        val before = hashes(name)
        val (pid, connection) = launch(WriterService.MIGRATE_AT, name, point)
        sigkill(pid, connection)

        val store = assertIs<StoreOpen.Ready>(StoreOpener.open(context, name, 3_000)).store
        assertTrue(store.integrityOk())
        assertEquals(json, SceneJson.encodeRevision(assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T)).revision))
        store.close()
        assertEquals(CargoDatabase.CURRENT_VERSION, SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version })
        val home = File(file.parentFile, "migration-${file.name}")
        val preserved = home.walkTopDown().filter { it.name == file.name && it.parentFile != home.resolve("work") }
            .map { ai.cargo.data.migration.MigrationCoordinator.hashDatabaseFiles(it)[file.name] }.toList()
        assertTrue(before[file.name] in preserved, "a byte-exact pre-migration copy exists")
        receipt(mapOf("scenario" to "MIGRATE_AT", "point" to point, "writerPid" to pid, "integrity" to "ok", "preservedCopies" to preserved.size))
    }

    /** Synthetic v1 file made from the exported fixture schema. */
    private fun createV1(file: File, revisionJson: String) {
        file.parentFile!!.mkdirs()
        val schema = JSONObject(context.assets.open("ai.cargo.data.db.CargoDatabase/1.json").bufferedReader().readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.execSQL(
                "INSERT INTO scene_revision (sceneId, revision, parentRevision, json, checksum, canonicalInputHash) VALUES (?, 1, NULL, ?, ?, 'hash')",
                arrayOf<Any>(SCENE, revisionJson, SceneChecksum.of(revisionJson)),
            )
            db.execSQL("INSERT INTO scene_head (sceneId, headRevision) VALUES (?, 1)", arrayOf<Any>(SCENE))
            db.version = 1
        }
    }

    @Test fun CE14_kill_after_backup_rolls_back_and_redoes() = killDuringMigration("BACKUP_COPIED")
    @Test fun CE14_kill_after_work_migrated_rolls_back_and_redoes() = killDuringMigration("WORK_MIGRATED")
    @Test fun CE14_kill_with_originals_moved_finishes_the_swap() = killDuringMigration("ORIGINALS_MOVED")
    @Test fun CE14_kill_after_install_finishes_cleanly() = killDuringMigration("WORK_INSTALLED")
}
