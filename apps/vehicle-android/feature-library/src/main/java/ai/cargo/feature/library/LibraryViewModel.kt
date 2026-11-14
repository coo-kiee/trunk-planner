package ai.cargo.feature.library

import ai.cargo.contracts.ItemVersion
import ai.cargo.data.CargoStore
import ai.cargo.data.VersionWrite
import android.database.sqlite.SQLiteException

/** One scene's reference to a library item, shown so the user sees that a new version changed nothing there. */
data class SceneItemUse(val sceneId: String, val sceneRevision: Long, val itemRevision: Long)

data class LibraryEntry(val latest: ItemVersion, val versions: List<ItemVersion>, val uses: List<SceneItemUse>)

sealed interface LibraryMessage {
    data class Saved(val name: String, val revision: Long, val wasNewVersion: Boolean) : LibraryMessage
    data class Conflict(val expected: Long) : LibraryMessage
    data object Invalid : LibraryMessage
    data object StorageFailed : LibraryMessage
}

/** Blocking storage reads and writes. The app runs these off the main thread. */
class LibraryGateway(private val store: CargoStore) {
    fun entries(at: String): List<LibraryEntry> {
        val uses = store.scenes.sceneIds().flatMap { id ->
            val head = store.scenes.head(id) ?: return@flatMap emptyList()
            val revision = store.scenes.revision(id, head) ?: return@flatMap emptyList()
            revision.instances.map { it.itemId to SceneItemUse(id, head, it.itemRevision) }
        }.groupBy({ it.first }, { it.second })
        return store.library.latestItems(at).map { latest ->
            LibraryEntry(
                latest = latest,
                versions = (1..latest.revision).mapNotNull { store.library.item(latest.id, it, at) },
                uses = uses[latest.id].orEmpty().distinct(),
            )
        }
    }

    /** A value the contract or storage refuses comes back as a message, so the form and what was typed both survive. */
    fun save(item: ItemVersion): LibraryMessage = try {
        when (val written = store.library.saveItemVersion(item)) {
            is VersionWrite.Saved -> LibraryMessage.Saved(item.name, written.revision, written.revision > 1)
            is VersionWrite.Conflict -> LibraryMessage.Conflict(written.expectedRevision)
        }
    } catch (_: IllegalArgumentException) {
        LibraryMessage.Invalid
    } catch (_: SQLiteException) {
        LibraryMessage.StorageFailed
    }
}
