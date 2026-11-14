package ai.cargo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

@Database(
    version = CargoDatabase.CURRENT_VERSION,
    entities = [
        ItemEntity::class, ItemVersionEntity::class, ProfileEntity::class, ProfileVersionEntity::class,
        SceneEntity::class, SceneRevisionEntity::class, SceneHeadEntity::class, EditDraftEntity::class,
        InboundDraftEntity::class, PairingEntity::class, QuarantineEntity::class,
    ],
    exportSchema = true,
)
abstract class CargoDatabase : RoomDatabase() {
    abstract fun dao(): CargoDao

    companion object {
        const val CURRENT_VERSION = 2

        /** Forward-only. No destructive fallback of any kind is configured anywhere in this module. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scene_revision ADD COLUMN acknowledgedIssuesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE scene_revision ADD COLUMN frameProvenance TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quarantine (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "tableName TEXT NOT NULL, rowKey TEXT NOT NULL, storedJson TEXT NOT NULL, " +
                        "storedChecksum TEXT NOT NULL, reason TEXT NOT NULL, detectedAt TEXT NOT NULL)",
                )
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context, path: String, migrations: Array<Migration> = MIGRATIONS): CargoDatabase =
            Room.databaseBuilder(context.applicationContext, CargoDatabase::class.java, path)
                .addMigrations(*migrations)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(PreservingOpenHelperFactory())
                .addCallback(ImmutabilityTriggers)
                .build()
    }
}

/** Immutable tables refuse UPDATE and DELETE at the SQLite level, independent of any application code path. */
internal object ImmutabilityTriggers : RoomDatabase.Callback() {
    private val tables = listOf("scene_revision", "item_version", "profile_version")

    override fun onOpen(db: SupportSQLiteDatabase) {
        for (table in tables) {
            for (operation in listOf("UPDATE", "DELETE")) {
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS immutable_${table}_${operation.lowercase()} " +
                        "BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'immutable row'); END",
                )
            }
        }
    }
}

/**
 * The framework's default corruption handler deletes the database file. Here corruption surfaces as an error
 * instead, so the original bytes remain available for a recovery screen and later analysis.
 */
internal class PreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val preserving = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(PreservingCallback(configuration.callback))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(preserving)
    }
}

private class PreservingCallback(
    private val delegate: SupportSQLiteOpenHelper.Callback,
) : SupportSQLiteOpenHelper.Callback(delegate.version) {
    override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)
    override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onUpgrade(db, oldVersion, newVersion)
    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onDowngrade(db, oldVersion, newVersion)
    override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

    override fun onCorruption(db: SupportSQLiteDatabase) {
        // Deliberately empty: the file is kept and the failing call reports the error to the store opener.
    }
}
