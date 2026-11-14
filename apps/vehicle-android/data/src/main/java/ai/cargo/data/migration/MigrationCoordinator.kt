package ai.cargo.data.migration

import ai.cargo.data.db.CargoDatabase
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Test seam at each crash window. The product passes [NONE]. */
fun interface MigrationFaultHook {
    fun reached(step: MigrationPoint)

    companion object { val NONE = MigrationFaultHook { } }
}

enum class MigrationPoint { BACKUP_COPIED, WORK_MIGRATED, ORIGINALS_MOVED, WORK_INSTALLED }

sealed interface MigrationResult {
    data object Migrated : MigrationResult

    /** The original and its pre-migration copy are both intact and their hashes were re-read to prove it. */
    data class Failed(val cause: String, val backupDir: String, val originalUnchanged: Boolean, val backupMatches: Boolean) : MigrationResult
}

/**
 * Forward-only migration on a separate copy. The original is never opened for writing: a backup is taken with its
 * WAL and SHM, a work copy is migrated and verified, and only then does the work copy replace the original. Every
 * step is journaled, so a process death at any point resumes or rolls back on the next open.
 */
class MigrationCoordinator(
    private val context: Context,
    private val databaseFile: File,
    private val migrations: Array<Migration> = CargoDatabase.MIGRATIONS,
    private val faults: MigrationFaultHook = MigrationFaultHook.NONE,
) {
    private val home = File(databaseFile.parentFile, "migration-${databaseFile.name}")
    private val workDir = File(home, "work")
    private val journal = MigrationJournal(File(home, "journal.json"))

    /**
     * True when an earlier migration kept copies here. With no database file beside them, the database was moved or
     * lost, and creating a new empty one would make the user's scenes look gone.
     */
    fun hasPreservedCopies(): Boolean =
        home.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith(BACKUP_PREFIX) }

    /** Completes or rolls back whatever a dead process left behind. Original bytes survive either way. */
    fun recover() {
        val record = journal.read() ?: return
        when (record.step) {
            MigrationStep.BACKUP_STARTED -> {
                File(record.backupDir).deleteRecursively()
                workDir.deleteRecursively()
                journal.clear()
            }
            MigrationStep.BACKUP_DONE, MigrationStep.WORK_MIGRATING, MigrationStep.WORK_VERIFIED -> {
                // The original was never touched at these steps, so discarding the work copy is a full rollback.
                workDir.deleteRecursively()
                journal.clear()
            }
            MigrationStep.SWAP_STARTED -> {
                finishSwap(record)
                journal.clear()
            }
            MigrationStep.SWAP_DONE -> journal.clear()
        }
    }

    fun migrate(fromVersion: Int, nowMs: Long): MigrationResult {
        val backupDir = File(home, "$BACKUP_PREFIX$fromVersion-$nowMs")
        var record = MigrationRecord(MigrationStep.BACKUP_STARTED, fromVersion, CargoDatabase.CURRENT_VERSION, backupDir.path)
        journal.write(record)
        var hashes = emptyMap<String, String>()
        return try {
            hashes = copyDatabaseFiles(databaseFile, backupDir)
            record = record.copy(step = MigrationStep.BACKUP_DONE, originalHashes = hashes)
            journal.write(record)
            faults.reached(MigrationPoint.BACKUP_COPIED)

            workDir.deleteRecursively()
            copyDatabaseFiles(backupDir.resolve(databaseFile.name), workDir)
            record = record.copy(step = MigrationStep.WORK_MIGRATING)
            journal.write(record)
            migrateAndVerifyWorkCopy()
            record = record.copy(step = MigrationStep.WORK_VERIFIED)
            journal.write(record)
            faults.reached(MigrationPoint.WORK_MIGRATED)

            record = record.copy(step = MigrationStep.SWAP_STARTED)
            journal.write(record)
            finishSwap(record)
            journal.write(record.copy(step = MigrationStep.SWAP_DONE))
            journal.clear()
            MigrationResult.Migrated
        } catch (error: Exception) {
            if (record.step == MigrationStep.SWAP_STARTED) return failedDuringSwap(record, error)
            // Before the swap the original was never touched, so dropping the work copy is a complete rollback.
            workDir.deleteRecursively()
            journal.clear()
            failure(error, record, hashes)
        }
    }

    /**
     * The original may already be aside, so the journal and verified work copy can only be dropped once the original
     * is back in place. If it cannot be put back they stay, and the next open finishes the swap from them.
     */
    private fun failedDuringSwap(record: MigrationRecord, error: Exception): MigrationResult {
        val workFile = workDir.resolve(databaseFile.name)
        if (databaseFile.isFile && !workFile.exists()) {
            // The migrated database is already installed. Only bookkeeping failed; the next open tidies the journal.
            workDir.deleteRecursively()
            runCatching { journal.clear() }
            return MigrationResult.Migrated
        }
        val restored = runCatching { restoreOriginals(record) }.isSuccess
        if (restored) {
            workDir.deleteRecursively()
            journal.clear()
        }
        return failure(error, record, record.originalHashes)
    }

    /** Companions go first, so the database never reappears without its WAL. A repeated call moves what remains. */
    private fun restoreOriginals(record: MigrationRecord) {
        val aside = replacedDir(record)
        val names = (databaseCompanions(databaseFile) + databaseFile).map { it.name }
        for (name in names) {
            val file = aside.resolve(name)
            if (file.exists()) check(file.renameTo(File(databaseFile.parentFile, name))) { "Could not restore $name" }
        }
        check(databaseFile.isFile) { "Original database is not back in place" }
    }

    private fun failure(error: Exception, record: MigrationRecord, hashes: Map<String, String>) = MigrationResult.Failed(
        cause = error.javaClass.simpleName + ": " + error.message,
        backupDir = record.backupDir,
        originalUnchanged = hashes.isNotEmpty() && databaseFile.isFile && hashes == hashDatabaseFiles(databaseFile),
        backupMatches = hashes.isNotEmpty() && hashes == hashDatabaseFiles(File(record.backupDir).resolve(databaseFile.name)),
    )

    /** Per attempt, so a later migration can never restore originals left by an earlier one. */
    private fun replacedDir(record: MigrationRecord) = File(record.backupDir, "replaced")

    private fun migrateAndVerifyWorkCopy() {
        val workFile = workDir.resolve(databaseFile.name)
        val revisionsBefore = rawCount(workFile, "scene_revision")
        val database = CargoDatabase.build(context, workFile.path, migrations)
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Integrity check failed on work copy" }
            }
            check(sqlite.version == CargoDatabase.CURRENT_VERSION) { "Work copy has version ${sqlite.version}" }
            check(database.dao().revisionCount() == revisionsBefore) { "Revision count changed during migration" }
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } finally {
            database.close()
        }
        databaseCompanions(workFile).forEach { it.delete() }
    }

    /**
     * Safe to repeat. Originals move aside before the verified work copy takes their name, because a WAL left beside
     * a replaced database would be applied to the wrong file. If the work copy is gone, the swap already finished.
     */
    private fun finishSwap(record: MigrationRecord) {
        val workFile = workDir.resolve(databaseFile.name)
        if (workFile.isFile) {
            val aside = replacedDir(record).apply { mkdirs() }
            for (file in listOf(databaseFile) + databaseCompanions(databaseFile)) {
                if (file.exists()) check(file.renameTo(aside.resolve(file.name))) { "Could not move ${file.name}" }
            }
            faults.reached(MigrationPoint.ORIGINALS_MOVED)
            check(workFile.renameTo(databaseFile)) { "Could not install migrated database" }
            faults.reached(MigrationPoint.WORK_INSTALLED)
        } else if (!databaseFile.exists()) {
            // Neither copy where expected: restore from the verified backup instead of inventing a database.
            copyDatabaseFiles(File(record.backupDir).resolve(databaseFile.name), databaseFile.parentFile!!)
        }
        workDir.deleteRecursively()
    }

    private fun rawCount(file: File, table: String): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, RAW_ERROR_HANDLER).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    companion object {
        private const val BACKUP_PREFIX = "backup-v"

        /** Never deletes anything: the framework default would remove a damaged file. */
        internal val RAW_ERROR_HANDLER = android.database.DatabaseErrorHandler { }

        internal fun databaseCompanions(file: File): List<File> =
            listOf(File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))

        /** Copies the database with its companions, flushed to disk, while no connection is open on them. */
        internal fun copyDatabaseFiles(database: File, targetDir: File): Map<String, String> {
            targetDir.mkdirs()
            for (file in listOf(database) + databaseCompanions(database)) {
                if (!file.exists()) continue
                val target = targetDir.resolve(file.name)
                FileInputStream(file).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            return hashDatabaseFiles(targetDir.resolve(database.name))
        }

        fun hashDatabaseFiles(database: File): Map<String, String> =
            (listOf(database) + databaseCompanions(database)).filter { it.isFile }.associate { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                file.name to digest.digest().joinToString("") { "%02x".format(it) }
            }
    }
}
