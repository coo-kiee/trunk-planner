package ai.cargo.data

import ai.cargo.data.db.CargoDatabase
import ai.cargo.data.migration.MigrationCoordinator
import ai.cargo.data.migration.MigrationFaultHook
import ai.cargo.data.migration.MigrationResult
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import java.io.File

/** Writable access to local data. It exists only after every open-time check has passed. */
class CargoStore internal constructor(private val database: CargoDatabase) {
    private val dao = database.dao()
    val quarantine = Quarantine(dao)
    val library = LibraryRepository(database, dao, quarantine)
    val scenes = SceneRepository(database, dao, quarantine)
    val drafts = DraftRepository(database, dao, quarantine)
    val inbound = InboundRepository(database, dao)

    fun integrityOk(): Boolean = database.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
        cursor.moveToFirst() && cursor.getString(0) == "ok"
    }

    fun close() = database.close()
}

/** Read-only look at a database written by a newer app. Room is never opened and nothing here can write. */
class FutureSchemaView internal constructor(private val file: File, val version: Int) {
    /** Best effort, since the newer layout is unknown. Null means this app cannot read it, which is not damage. */
    fun headRevisionJson(sceneId: String): String? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, MigrationCoordinator.RAW_ERROR_HANDLER).use { db ->
            db.rawQuery(
                "SELECT r.json FROM scene_revision r JOIN scene_head h ON r.sceneId = h.sceneId AND r.revision = h.headRevision WHERE h.sceneId = ?",
                arrayOf(sceneId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }
    }.getOrNull()
}

sealed interface StoreOpen {
    data class Ready(val store: CargoStore) : StoreOpen

    /** Written by a newer version. All writes are refused because no writable store exists. */
    data class FutureSchema(val view: FutureSchemaView) : StoreOpen

    /** Migration did not complete. The original and pre-migration copy are kept; there is no writable store. */
    data class MigrationFailed(val failure: MigrationResult.Failed) : StoreOpen

    /** SQLite cannot use the file. The bytes stay untouched for recovery. */
    data class OpenFailed(val cause: String, val path: String) : StoreOpen
}

object StoreOpener {
    /**
     * Checks in order: finish an interrupted migration, inspect the schema through a raw read-only connection, then
     * migrate on a copy if older. Room opens the real file only once all of those are clear.
     */
    fun open(
        context: Context,
        name: String,
        nowMs: Long,
        migrations: Array<Migration> = CargoDatabase.MIGRATIONS,
        faults: MigrationFaultHook = MigrationFaultHook.NONE,
    ): StoreOpen {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val coordinator = MigrationCoordinator(context, file, migrations, faults)
        try {
            coordinator.recover()
        } catch (error: Exception) {
            return StoreOpen.OpenFailed("Interrupted migration could not be recovered: ${error.message}", file.path)
        }
        if (!file.exists() && coordinator.hasPreservedCopies()) {
            return StoreOpen.OpenFailed("Database file is missing but migration copies exist; not creating an empty one", file.path)
        }
        if (file.exists()) {
            val version = try {
                rawUserVersion(file)
            } catch (error: Exception) {
                return StoreOpen.OpenFailed(error.javaClass.simpleName, file.path)
            }
            when {
                version > CargoDatabase.CURRENT_VERSION -> return StoreOpen.FutureSchema(FutureSchemaView(file, version))
                // Version 0 with no tables is a creation that never finished; with tables it is not this app's file.
                version == 0 -> if (rawHasTables(file)) return StoreOpen.OpenFailed("Existing file carries no schema version", file.path)
                version < CargoDatabase.CURRENT_VERSION -> {
                    val result = coordinator.migrate(version, nowMs)
                    if (result is MigrationResult.Failed) return StoreOpen.MigrationFailed(result)
                }
            }
        }
        return openRoom(context, file, migrations)
    }

    private fun openRoom(context: Context, file: File, migrations: Array<Migration>): StoreOpen {
        val database = CargoDatabase.build(context, file.path, migrations)
        return try {
            database.openHelper.writableDatabase
            StoreOpen.Ready(CargoStore(database))
        } catch (error: Exception) {
            runCatching { database.close() }
            StoreOpen.OpenFailed(error.javaClass.simpleName, file.path)
        }
    }

    private fun rawHasTables(file: File): Boolean =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, MigrationCoordinator.RAW_ERROR_HANDLER).use { db ->
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' LIMIT 1", null).use { it.moveToFirst() }
        }

    private fun rawUserVersion(file: File): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, MigrationCoordinator.RAW_ERROR_HANDLER).use { db ->
            db.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "quick_check failed" }
            }
            db.version
        }
}
