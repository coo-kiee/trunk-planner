package ai.cargo.data.migration

import ai.cargo.scene.SceneJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/** Steps recorded durably before the work they announce. */
@Serializable
enum class MigrationStep { BACKUP_STARTED, BACKUP_DONE, WORK_MIGRATING, WORK_VERIFIED, SWAP_STARTED, SWAP_DONE }

@Serializable
data class MigrationRecord(
    val step: MigrationStep,
    val fromVersion: Int,
    val toVersion: Int,
    val backupDir: String,
    val originalHashes: Map<String, String> = emptyMap(),
    val failure: String? = null,
)

/** Atomic, fsynced record of migration progress, read on every open before SQLite is touched. */
internal class MigrationJournal(private val file: File) {
    fun read(): MigrationRecord? {
        if (!file.isFile) return null
        return runCatching { SceneJson.json.decodeFromString(MigrationRecord.serializer(), file.readText()) }.getOrNull()
    }

    fun write(record: MigrationRecord) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { out ->
            out.write(SceneJson.json.encodeToString(MigrationRecord.serializer(), record).toByteArray())
            out.fd.sync()
        }
        check(temporary.renameTo(file)) { "Journal rename failed" }
    }

    fun clear() {
        file.delete()
    }
}
