package ai.cargo.feature.editor

import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.ProfileVersion
import ai.cargo.geometry.CandidatePose
import ai.cargo.geometry.SceneGeometry
import ai.cargo.scene.ItemRef
import ai.cargo.scene.SceneContext
import ai.cargo.scene.SceneDraftState

sealed interface EditorMode {
    data object Loading : EditorMode
    data object Editable : EditorMode

    /** Head failed verification. The shown revision is the newest good one and nothing can be edited or saved. */
    data class RecoveredReadOnly(val shownRevision: Long, val damagedRevision: Long) : EditorMode

    /** No valid revision or an unreadable legacy frame. There is no scene to draw. */
    data class Unavailable(val code: String, val damagedRevision: Long?) : EditorMode
}

/** A pose the search found. It is only an offer until the user places it. */
data class CandidateOffer(val item: ItemRef, val token: String, val pose: CandidatePose, val uncertain: Boolean)

data class NotAvailableNote(val code: String, val detail: String)

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Saved(val revision: Long) : SaveState
    data class Refused(val message: String) : SaveState
}

enum class PoseAxis(val label: String) { X("X 차량 왼쪽 방향 (mm)"), Y("Y 위쪽 (mm)"), Z("Z 차량 앞쪽 (mm)") }

data class PoseEntry(val values: Map<PoseAxis, String>, val focused: PoseAxis = PoseAxis.X)

data class EditorUiState(
    val mode: EditorMode = EditorMode.Loading,
    val draft: SceneDraftState? = null,
    val context: SceneContext? = null,
    val library: List<ItemVersion> = emptyList(),
    val selected: String? = null,
    val stepMm: Long = 50,
    val geometry: SceneGeometry? = null,
    val notAvailable: NotAvailableNote? = null,
    val validating: Boolean = false,
    val searching: Boolean = false,
    val offer: CandidateOffer? = null,
    val lastMove: String? = null,
    val rejection: String? = null,
    val save: SaveState = SaveState.Idle,
    val restoredDraft: Boolean = false,
    val draftDamaged: Boolean = false,
    val poseEntry: PoseEntry? = null,
) {
    val profile: ProfileVersion? get() = draft?.let { context?.profile(it.profileId, it.profileRevision) }
}
