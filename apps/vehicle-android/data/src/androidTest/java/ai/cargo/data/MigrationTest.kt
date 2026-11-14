package ai.cargo.data

import ai.cargo.data.Seed.SCENE
import ai.cargo.data.Seed.T
import ai.cargo.data.db.CargoDatabase
import ai.cargo.data.migration.MigrationFaultHook
import ai.cargo.data.migration.MigrationPoint
import ai.cargo.scene.LegacyUpcast
import ai.cargo.scene.SceneChecksum
import ai.cargo.scene.SceneJson
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Version 1 here is a synthetic schema fixture, never a released product version. */
@RunWith(AndroidJUnit4::class)
class MigrationTest : StoreTestBase() {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), CargoDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun cleanup() = removeCreated()

    private val validJson by lazy {
        // A checksummed revision in the current JSON format, produced by the real save path on a scratch store.
        val store = Seed.open(context, databaseName("source"))
        Seed.firstRevision(store)
        val json = SceneJson.encodeRevision(store.scenes.revision(SCENE, 1)!!)
        store.close()
        json
    }

    private fun fixtureV1(name: String, revisionJson: String = validJson) {
        helper.createDatabase(name, 1).use { db ->
            val checksum = runCatching { SceneChecksum.of(revisionJson) }.getOrDefault("")
            db.execSQL(
                "INSERT INTO scene_revision (sceneId, revision, parentRevision, json, checksum, canonicalInputHash) VALUES (?, 1, NULL, ?, ?, 'hash')",
                arrayOf<Any>(SCENE, revisionJson, checksum),
            )
            db.execSQL("INSERT INTO scene_head (sceneId, headRevision) VALUES (?, 1)", arrayOf<Any>(SCENE))
            db.execSQL("INSERT INTO scene (id, profileId, createdAt) VALUES (?, ?, ?)", arrayOf<Any>(SCENE, Seed.PROFILE, T))
        }
    }

    private fun backupDir(name: String) = databaseFile(name).parentFile!!.resolve("migration-${databaseFile(name).name}")

    @Test fun migration_test_helper_validates_schema_1_to_2() {
        val name = databaseName("helper")
        fixtureV1(name)
        helper.runMigrationsAndValidate(name, 2, true, CargoDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT acknowledgedIssuesJson, frameProvenance FROM scene_revision").use { c ->
                c.moveToFirst()
                assertEquals("[]", c.getString(0))
                assertTrue(c.isNull(1))
            }
        }
    }

    @Test fun CE14_coordinator_keeps_a_backup_and_original_json_through_success() {
        val name = databaseName("success")
        fixtureV1(name)
        val before = hashes(name)
        val store = assertIs<StoreOpen.Ready>(StoreOpener.open(context, name, 123)).store
        val loaded = assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T))
        assertEquals(validJson, SceneJson.encodeRevision(loaded.revision), "stored JSON is byte-identical")
        assertTrue(store.integrityOk())
        store.close()

        val backup = backupDir(name).resolve("backup-v1-123")
        assertEquals(before, ai.cargo.data.migration.MigrationCoordinator.hashDatabaseFiles(backup.resolve(databaseFile(name).name)))
        val replaced = backup.resolve("replaced")
        assertEquals(before, ai.cargo.data.migration.MigrationCoordinator.hashDatabaseFiles(replaced.resolve(databaseFile(name).name)))
        assertEquals(CargoDatabase.CURRENT_VERSION, SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version })
    }

    /** Commits that exist only in the WAL must reach both the backup and the migrated database. */
    @Test fun CE14_backup_and_migration_include_commits_still_in_the_wal() {
        val source = databaseName("walsource")
        val copy = databaseName("walcopy")
        val v1 = helper.createDatabase(source, 1)
        v1.query("PRAGMA journal_mode=WAL").use { it.moveToFirst() }
        v1.query("PRAGMA wal_autocheckpoint=0").use { it.moveToFirst() }
        v1.execSQL(
            "INSERT INTO scene_revision (sceneId, revision, parentRevision, json, checksum, canonicalInputHash) VALUES (?, 1, NULL, ?, ?, 'hash')",
            arrayOf<Any>(SCENE, validJson, SceneChecksum.of(validJson)),
        )
        v1.execSQL("INSERT INTO scene_head (sceneId, headRevision) VALUES (?, 1)", arrayOf<Any>(SCENE))
        val wal = java.io.File(databaseFile(source).path + "-wal")
        assertTrue(wal.length() > 0, "the commit is in the WAL")
        // Copy while the connection stays open, so the set is what a process death would leave behind.
        databaseFile(source).copyTo(databaseFile(copy))
        wal.copyTo(java.io.File(databaseFile(copy).path + "-wal"))
        v1.close()

        val store = assertIs<StoreOpen.Ready>(StoreOpener.open(context, copy, 700)).store
        assertEquals(validJson, SceneJson.encodeRevision(assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T)).revision))
        store.close()
        val backup = backupDir(copy).resolve("backup-v1-700").resolve(databaseFile(copy).name)
        assertTrue(java.io.File(backup.path + "-wal").length() > 0, "backup keeps the WAL file")
        SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM scene_revision", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test fun CE14_failure_keeps_original_and_copy_and_a_later_fix_still_succeeds() {
        val name = databaseName("failure")
        fixtureV1(name)
        val before = hashes(name)
        val broken: Array<Migration> = arrayOf(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scene_revision ADD COLUMN half_done TEXT")
                error("simulated migration failure")
            }
        })
        val failed = assertIs<StoreOpen.MigrationFailed>(StoreOpener.open(context, name, 200, migrations = broken))
        assertTrue(failed.failure.originalUnchanged, "original hash re-read after failure")
        assertTrue(failed.failure.backupMatches, "backup hash re-read after failure")
        assertEquals(before, hashes(name))
        assertEquals(1, SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version })

        // The earlier backup stays, a retry with a correct migration works, and the data round-trips forward.
        val store = assertIs<StoreOpen.Ready>(StoreOpener.open(context, name, 300)).store
        assertEquals(validJson, SceneJson.encodeRevision(assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T)).revision))
        store.close()
        assertTrue(backupDir(name).resolve("backup-v1-200").isDirectory)
        assertTrue(backupDir(name).resolve("backup-v1-300").isDirectory)
    }

    private fun version(name: String) =
        SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    private fun assertHeadPreserved(name: String, nowMs: Long) {
        val store = assertIs<StoreOpen.Ready>(StoreOpener.open(context, name, nowMs)).store
        assertEquals(1, store.scenes.head(SCENE))
        assertEquals(validJson, SceneJson.encodeRevision(assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T)).revision))
        assertTrue(store.integrityOk())
        store.close()
        assertEquals(CargoDatabase.CURRENT_VERSION, version(name))
    }

    /** The independent review's counterexample: an exception after the originals moved aside. */
    @Test fun CE14_exception_after_originals_moved_restores_them_and_reopen_keeps_the_head() {
        val name = databaseName("swapfail")
        fixtureV1(name)
        val before = hashes(name)
        val failed = assertIs<StoreOpen.MigrationFailed>(
            StoreOpener.open(context, name, 800, faults = MigrationFaultHook { if (it == MigrationPoint.ORIGINALS_MOVED) error("swap failure") }),
        )
        assertTrue(failed.failure.originalUnchanged, "original is back at its path with the same bytes")
        assertTrue(failed.failure.backupMatches)
        assertEquals(before, hashes(name))
        assertEquals(1, version(name))
        assertTrue(!backupDir(name).resolve("journal.json").exists(), "rollback finished")
        assertHeadPreserved(name, 900)
    }

    /** Rename really fails: the target path is a non-empty directory, so the original cannot return yet either. */
    @Test fun CE14_rename_failure_keeps_journal_and_work_and_never_creates_an_empty_database() {
        val name = databaseName("renamefail")
        fixtureV1(name)
        val file = databaseFile(name)
        val blocker = java.io.File(file, "blocker")
        val failed = assertIs<StoreOpen.MigrationFailed>(
            StoreOpener.open(context, name, 800, faults = MigrationFaultHook {
                if (it == MigrationPoint.ORIGINALS_MOVED) {
                    file.mkdirs()
                    blocker.writeText("x")
                }
            }),
        )
        assertTrue(!failed.failure.originalUnchanged, "the report does not claim the original is back")
        assertTrue(failed.failure.backupMatches)
        assertTrue(backupDir(name).resolve("journal.json").isFile, "journal stays for the next open")
        assertTrue(backupDir(name).resolve("work").resolve(file.name).isFile, "verified work copy stays")

        // While the obstacle persists, opening is a typed failure, not a new empty store.
        assertIs<StoreOpen.OpenFailed>(StoreOpener.open(context, name, 850))
        assertTrue(file.isDirectory)

        // Once it clears, the next open finishes the swap and the head is intact.
        blocker.delete()
        file.delete()
        assertHeadPreserved(name, 900)
    }

    @Test fun CE14_exception_after_install_is_a_finished_migration() {
        val name = databaseName("afterinstall")
        fixtureV1(name)
        val opened = StoreOpener.open(context, name, 800, faults = MigrationFaultHook { if (it == MigrationPoint.WORK_INSTALLED) error("late failure") })
        val store = assertIs<StoreOpen.Ready>(opened).store
        assertEquals(1, store.scenes.head(SCENE))
        store.close()
        assertHeadPreserved(name, 900)
    }

    @Test fun CE14_exception_before_the_swap_leaves_the_original_untouched() {
        for (point in listOf(MigrationPoint.BACKUP_COPIED, MigrationPoint.WORK_MIGRATED)) {
            val name = databaseName("preswap")
            fixtureV1(name)
            val before = hashes(name)
            val failed = assertIs<StoreOpen.MigrationFailed>(
                StoreOpener.open(context, name, 800, faults = MigrationFaultHook { if (it == point) error("failure at $point") }),
            )
            assertTrue(failed.failure.originalUnchanged && failed.failure.backupMatches, "$point")
            assertEquals(before, hashes(name), "$point")
            assertHeadPreserved(name, 900)
        }
    }

    /** A second safety net: however the file went missing, its copies mean this is not a first run. */
    @Test fun CE14_missing_database_with_preserved_copies_is_not_recreated_empty() {
        val name = databaseName("missing")
        fixtureV1(name)
        assertHeadPreserved(name, 800)
        val file = databaseFile(name)
        listOf(file, java.io.File(file.path + "-wal"), java.io.File(file.path + "-shm")).forEach { it.delete() }
        assertIs<StoreOpen.OpenFailed>(StoreOpener.open(context, name, 900))
        assertTrue(!file.exists(), "no empty database was created")
    }

    @Test fun CE14_invalid_migration_result_is_failure_not_silent_success() {
        val name = databaseName("dropper")
        fixtureV1(name)
        val before = hashes(name)
        val lossy: Array<Migration> = arrayOf(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                CargoDatabase.MIGRATION_1_2.migrate(db)
                db.execSQL("DELETE FROM scene_revision")
            }
        })
        val failed = assertIs<StoreOpen.MigrationFailed>(StoreOpener.open(context, name, 400, migrations = lossy))
        assertTrue(failed.failure.cause.contains("Revision count"), failed.failure.cause)
        assertEquals(before, hashes(name))
    }

    @Test fun CE14_legacy_scene_without_frame_provenance_stays_read_only_and_unchanged() {
        val name = databaseName("legacy")
        val unsigned = """{"schemaVersion":0,"id":"$SCENE","revision":1,"instances":[{"instanceId":"${Seed.INST_A}","poseMm":{"x":12,"y":56,"z":34}}],"checksum":"0"}"""
        val checksum = SceneChecksum.of(unsigned)
        val legacy = unsigned.replace("\"checksum\":\"0\"", "\"checksum\":\"$checksum\"")
        fixtureV1(name, legacy)
        val store = assertIs<StoreOpen.Ready>(StoreOpener.open(context, name, 500)).store
        val view = assertIs<SceneLoad.LegacyView>(store.scenes.load(SCENE, T))
        assertEquals(LegacyUpcast.Result.ReadOnly("FRAME_PROVENANCE_MISSING"), view.upcast)
        store.close()
        val stored = SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER immutable_scene_revision_update")
            db.execSQL("UPDATE scene_revision SET frameProvenance = ?", arrayOf(LegacyUpcast.FRAME_OPENING_RIGHT_V0))
            db.rawQuery("SELECT json FROM scene_revision", null).use { c -> c.moveToFirst(); c.getString(0) }
        }
        assertEquals(legacy, stored, "upcast never rewrites stored JSON")
        val reopened = Seed.open(context, name)
        val upcast = assertIs<LegacyUpcast.Result.Upcast>(assertIs<SceneLoad.LegacyView>(reopened.scenes.load(SCENE, T)).upcast)
        val pose = upcast.poses.single()
        assertEquals(listOf(12L, 34L, 56L), listOf(pose.x, pose.y, pose.z), "fixture migration-axis-v0-v1")
        assertNotNull(upcast.staleReasons.singleOrNull())
        reopened.close()
    }
}
