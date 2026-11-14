package ai.cargo.data

import ai.cargo.data.db.CargoDao
import ai.cargo.data.db.CargoDatabase
import ai.cargo.data.db.EditDraftEntity
import ai.cargo.scene.SceneDraftState
import ai.cargo.scene.SceneJson
import ai.cargo.scene.SceneReducer

sealed interface DraftLoad {
    data object None : DraftLoad

    /** [recoveredInterruptedSearch] says a search marker from a dead process was converted. */
    data class Restored(val state: SceneDraftState, val recoveredInterruptedSearch: Boolean) : DraftLoad

    /** The stored draft failed verification. It is quarantined, kept in place, and work continues from the head. */
    data object Damaged : DraftLoad
}

internal fun encodeDraft(state: SceneDraftState, at: String): EditDraftEntity {
    val json = SceneJson.json.encodeToString(SceneDraftState.serializer(), state)
    return EditDraftEntity(state.sceneId, json, SceneJson.sha256Hex(json), at)
}

/** Every accepted edit commits its whole draft, undo, redo and camera state as one write. */
class DraftRepository internal constructor(
    private val db: CargoDatabase,
    private val dao: CargoDao,
    private val quarantine: Quarantine,
) {
    fun commit(state: SceneDraftState, at: String) {
        db.runInTransaction { dao.upsertDraft(encodeDraft(state, at)) }
    }

    fun load(sceneId: String, at: String): DraftLoad {
        val row = dao.draft(sceneId) ?: return DraftLoad.None
        val state = if (SceneJson.sha256Hex(row.stateJson) == row.checksum) {
            runCatching { SceneJson.json.decodeFromString(SceneDraftState.serializer(), row.stateJson) }.getOrNull()
        } else {
            null
        }
        if (state == null || state.schemaVersion > SceneDraftState.CURRENT_SCHEMA) {
            quarantine.record("edit_draft", sceneId, row.stateJson, row.checksum, "CHECKSUM_OR_SCHEMA_FAILED", at)
            return DraftLoad.Damaged
        }
        val recovered = SceneReducer.recoverAfterRestore(state)
        if (recovered !== state) commit(recovered, at)
        return DraftLoad.Restored(recovered, recovered !== state)
    }

    fun discard(sceneId: String) {
        db.runInTransaction { dao.deleteDraft(sceneId) }
    }
}
