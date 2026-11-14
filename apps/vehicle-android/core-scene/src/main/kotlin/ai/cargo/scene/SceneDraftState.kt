package ai.cargo.scene

import ai.cargo.contracts.Camera
import ai.cargo.contracts.EditDraft
import ai.cargo.contracts.Instance
import ai.cargo.contracts.JudgementSnapshot
import ai.cargo.contracts.SceneRevision
import ai.cargo.contracts.SearchResult
import ai.cargo.contracts.SearchStatus
import ai.cargo.contracts.UncertifiedReason
import kotlinx.serialization.Serializable

/** Stable stale reason codes kept in judgement snapshots; the UI maps them to wording. */
object StaleReason {
    const val ITEM_VERSION_APPLIED = "ITEM_VERSION_APPLIED"
    const val PROFILE_VERSION_APPLIED = "PROFILE_VERSION_APPLIED"
    const val ENGINE_VERSION_CHANGED = "ENGINE_VERSION_CHANGED"
    const val CONFIGURATION_CHANGED = "CONFIGURATION_CHANGED"
    const val INPUT_CHANGED = "INPUT_CHANGED"
    const val NOT_VALIDATED = "NOT_VALIDATED"
    const val REFERENCED_VERSION_MISSING = "REFERENCED_VERSION_MISSING"
    const val LEGACY_AXIS_UPCAST = "LEGACY_AXIS_UPCAST"
}

@Serializable
enum class LockedChangeKind { POSE, ITEM_REVISION, REMOVAL }

/** Authority an explicit command granted to change a base-locked instance. Restored with undo/redo snapshots. */
@Serializable
data class LockedChange(val instanceId: String, val kind: LockedChangeKind)

@Serializable
enum class IssueKind { PLACEMENT_CONFLICT, ROTATION_NOT_ALLOWED, AIRBORNE, INSUFFICIENT_SUPPORT, ON_NON_STACKABLE }

@Serializable
data class JudgementIssue(val kind: IssueKind, val instanceId: String)

/** A judgement bound to the exact input it was computed from. */
@Serializable
data class ValidationRecord(
    val inputHash: String,
    val engineVersion: String,
    val configurationRevision: Long,
    val snapshot: JudgementSnapshot,
    val issues: List<JudgementIssue>,
    /** True for a judgement read back from a saved revision: readable as history, not confirmable until revalidated. */
    val restoredFromRevision: Boolean = false,
)

@Serializable
data class Confirmation(val inputHash: String, val acknowledged: List<JudgementIssue>, val confirmedAt: String)

@Serializable
enum class SearchInterruptCause { BUDGET, CANCELLED, PROCESS_DEATH }

@Serializable
data class PendingSearch(val token: String, val item: ItemRef, val fingerprint: String)

@Serializable
data class SearchRecord(
    val status: SearchStatus,
    val reason: UncertifiedReason? = null,
    val item: ItemRef,
    val fingerprint: String,
    val interruptCause: SearchInterruptCause? = null,
    /** Set when the found candidate was added, so moving that instance keeps the search result applicable. */
    val subjectInstanceId: String? = null,
) {
    val wire: SearchResult get() = SearchResult(status, reason)
}

@Serializable
data class CommandEvent(val seq: Long, val kind: String, val instanceId: String? = null, val at: String)

/** Exactly what undo and redo restore: the arrangement plus the command authority that produced it. */
@Serializable
data class HistorySnapshot(
    val instances: List<Instance>,
    val profileRevision: Long,
    val unlockedIds: List<String>,
    val lockedChanges: List<LockedChange>,
    val pendingStale: List<String>,
    val lastSearch: SearchRecord?,
)

/**
 * Local draft. The wire [EditDraft] is only a projection of it: provenance, applied revisions, confirmation
 * and the pending search marker exist here and survive process recreation through the data layer.
 */
@Serializable
data class SceneDraftState(
    val schemaVersion: Long = CURRENT_SCHEMA,
    val sceneId: String,
    val profileId: String,
    val baseRevision: Long?,
    val baseInstances: List<Instance>,
    val createdAt: String,
    val instances: List<Instance>,
    val profileRevision: Long,
    val camera: Camera,
    val undo: List<HistorySnapshot> = emptyList(),
    val redo: List<HistorySnapshot> = emptyList(),
    val unlockedIds: List<String> = emptyList(),
    val lockedChanges: List<LockedChange> = emptyList(),
    val pendingStale: List<String> = emptyList(),
    val validation: ValidationRecord? = null,
    val confirmation: Confirmation? = null,
    val pendingSearch: PendingSearch? = null,
    val lastSearch: SearchRecord? = null,
    val events: List<CommandEvent> = emptyList(),
    val nextEventSeq: Long = 1,
) {
    fun snapshot() = HistorySnapshot(instances, profileRevision, unlockedIds, lockedChanges, pendingStale, lastSearch)

    /** Wire projection; null until the scene has a saved base revision the wire format can name. */
    fun toWire(): EditDraft? = baseRevision?.let { base ->
        EditDraft(
            schemaVersion = 1,
            sceneId = sceneId,
            baseRevision = base,
            instances = instances,
            undo = undo.map { it.instances },
            redo = redo.map { it.instances },
            camera = camera,
        )
    }

    companion object {
        const val CURRENT_SCHEMA = 1L
        const val HISTORY_LIMIT = 100
        const val EVENT_LIMIT = 500

        val DEFAULT_CAMERA = Camera(
            target = ai.cargo.contracts.Vec3Mm(0, 0, 0),
            yawDegrees = 0, pitchDegrees = 20, distanceMm = 3000,
        )

        fun newScene(sceneId: String, profileId: String, profileRevision: Long, createdAt: String) = SceneDraftState(
            sceneId = sceneId, profileId = profileId, baseRevision = null, baseInstances = emptyList(),
            createdAt = createdAt, instances = emptyList(), profileRevision = profileRevision, camera = DEFAULT_CAMERA,
        )

        /** Fresh draft over a saved revision. Undo starts here, so a saved revision can never be edited through it. */
        fun fromRevision(revision: SceneRevision, camera: Camera = DEFAULT_CAMERA): SceneDraftState = SceneDraftState(
            sceneId = revision.id,
            profileId = revision.profileId,
            baseRevision = revision.revision,
            baseInstances = revision.instances,
            createdAt = revision.createdAt,
            instances = revision.instances,
            profileRevision = revision.profileRevision,
            camera = camera,
            validation = ValidationRecord(
                inputHash = revision.canonicalInputHash,
                engineVersion = revision.engineVersion,
                configurationRevision = revision.configurationRevision,
                snapshot = revision.judgementSnapshot,
                issues = emptyList(),
                restoredFromRevision = true,
            ),
        )
    }
}
