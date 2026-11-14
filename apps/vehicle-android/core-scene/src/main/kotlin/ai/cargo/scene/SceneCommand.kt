package ai.cargo.scene

import ai.cargo.contracts.Camera
import ai.cargo.contracts.Vec3Mm

/** Explicit user intentions. Nothing else can change a draft; identifiers and times arrive as arguments. */
sealed interface SceneCommand {
    data class Add(
        val instanceId: String,
        val item: ItemRef,
        val poseMm: Vec3Mm,
        val orientationIndex: Long,
        val locked: Boolean = false,
        val fromSearchToken: String? = null,
    ) : SceneCommand

    data class Move(val instanceId: String, val poseMm: Vec3Mm) : SceneCommand
    data class Rotate(val instanceId: String, val orientationIndex: Long, val poseMm: Vec3Mm) : SceneCommand
    data class Remove(val instanceId: String) : SceneCommand
    data class Lock(val instanceId: String) : SceneCommand
    data class Unlock(val instanceId: String) : SceneCommand

    /** New library versions never reach a scene unless the user applies them here. */
    data class ApplyItemVersion(val instanceId: String, val revision: Long) : SceneCommand
    data class ApplyProfileVersion(val revision: Long) : SceneCommand

    data class ApplyValidation(val record: ValidationRecord) : SceneCommand
    data class ConfirmPlacement(val inputHash: String, val acknowledged: List<JudgementIssue> = emptyList()) : SceneCommand

    data class BeginSearch(val token: String, val item: ItemRef) : SceneCommand
    data class FinishSearch(val token: String, val record: SearchRecord) : SceneCommand
    data class AbortSearch(val token: String) : SceneCommand

    data class SetCamera(val camera: Camera) : SceneCommand
    data object Undo : SceneCommand
    data object Redo : SceneCommand
}

enum class RejectReason {
    UNKNOWN_INSTANCE,
    DUPLICATE_INSTANCE,
    INSTANCE_LIMIT,
    REFERENCED_VERSION_MISSING,
    LOCKED_REQUIRES_UNLOCK,
    POSE_OUT_OF_RANGE,
    ORIENTATION_OUT_OF_RANGE,
    NO_VERSION_CHANGE,
    NOTHING_TO_UNDO,
    NOTHING_TO_REDO,
    NOT_VALIDATED,
    STALE_VALIDATION_RESULT,
    NEW_PLACEMENT_INVALID,
    ACKNOWLEDGEMENT_REQUIRED,
    SEARCH_ALREADY_RUNNING,
    STALE_SEARCH_RESULT,
    NOT_LOCKED,
    NOT_UNLOCKED,
}

sealed interface ReduceResult {
    data class Accepted(val state: SceneDraftState) : ReduceResult

    /** The input state stays the authority; a rejected command changes nothing. */
    data class Rejected(val reason: RejectReason, val instanceIds: List<String> = emptyList()) : ReduceResult
}
