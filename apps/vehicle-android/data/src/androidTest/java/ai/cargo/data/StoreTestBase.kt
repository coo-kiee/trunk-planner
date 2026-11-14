package ai.cargo.data

import ai.cargo.data.migration.MigrationCoordinator
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

/** Each test works on a unique database in this test app's own storage and removes it afterwards. */
open class StoreTestBase {
    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<String>()

    protected fun databaseName(prefix: String): String =
        "$prefix-${UUID.randomUUID()}.db".also(created::add)

    protected fun databaseFile(name: String): File = context.getDatabasePath(name)

    protected fun hashes(name: String): Map<String, String> = MigrationCoordinator.hashDatabaseFiles(databaseFile(name))

    protected fun removeCreated() {
        for (name in created) {
            val file = databaseFile(name)
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
            File(file.parentFile, "migration-${file.name}").deleteRecursively()
        }
    }
}
