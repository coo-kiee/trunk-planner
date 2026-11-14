package ai.cargo.scene

import ai.cargo.contracts.Instance
import ai.cargo.contracts.SceneRevision

enum class SaveRejection {
    NOT_VALIDATED, STALE, NOT_CONFIRMED, LOCKED_DIFF, HEAD_MOVED, SEARCH_RUNNING, NOT_AVAILABLE, ACKNOWLEDGEMENT_MISSING,
}

/** Everything the data layer needs for one atomic save, including the evidence for its second locked-diff check. */
data class PreparedSave(
    val revision: SceneRevision,
    val expectedHead: Long?,
    val lockedChanges: List<LockedChange>,
    val unlockedIds: List<String>,
    val acknowledged: List<JudgementIssue>,
    val nextDraft: SceneDraftState,
)

sealed interface SaveDecision {
    data class Approved(val save: PreparedSave) : SaveDecision
    data class Rejected(val reason: SaveRejection, val instanceIds: List<String> = emptyList(), val staleReasons: List<String> = emptyList()) : SaveDecision
}

/** The save guard. A base-locked instance may differ only through authority granted by an explicit command. */
object LockedDiff {
    fun violations(
        base: List<Instance>,
        draft: List<Instance>,
        changes: List<LockedChange>,
        unlockedIds: List<String>,
    ): List<String> {
        val current = draft.associateBy { it.instanceId }
        return base.filter { it.locked }.filter { before ->
            val after = current[before.instanceId]
            if (after == null) {
                !changes.has(before.instanceId, LockedChangeKind.REMOVAL)
            } else {
                unauthorizedChange(before, after, changes, unlockedIds)
            }
        }.map { it.instanceId }
    }

    /** Each field needs its own authority: permission for one change never covers another made at the same time. */
    private fun unauthorizedChange(before: Instance, after: Instance, changes: List<LockedChange>, unlockedIds: List<String>): Boolean {
        val id = before.instanceId
        val poseChanged = after.poseMm != before.poseMm || after.orientationIndex != before.orientationIndex
        return after.itemId != before.itemId ||
            (poseChanged && !changes.has(id, LockedChangeKind.POSE)) ||
            (after.itemRevision != before.itemRevision && !changes.has(id, LockedChangeKind.ITEM_REVISION)) ||
            (!after.locked && id !in unlockedIds)
    }

    private fun List<LockedChange>.has(id: String, kind: LockedChangeKind) = any { it.instanceId == id && it.kind == kind }
}

/** Why a saved or drafted judgement must not be shown as current. */
object StaleAnalysis {
    /** Empty means the draft's judgement speaks for its current input. */
    fun forDraft(state: SceneDraftState, context: SceneContext): List<String> {
        val hash = SceneValidator.currentHash(state, context)
            ?: return listOf(StaleReason.REFERENCED_VERSION_MISSING)
        val validation = state.validation ?: return (state.pendingStale + StaleReason.NOT_VALIDATED).distinct()
        if (validation.inputHash == hash && state.pendingStale.isEmpty()) return emptyList()
        val reasons = state.pendingStale.toMutableList()
        if (validation.engineVersion != context.config.engineVersion) reasons += StaleReason.ENGINE_VERSION_CHANGED
        if (validation.configurationRevision != context.config.revision) reasons += StaleReason.CONFIGURATION_CHANGED
        if (reasons.isEmpty()) reasons += StaleReason.INPUT_CHANGED
        return reasons.distinct()
    }

    data class RevisionStatus(val staleReasons: List<String>, val newerItems: List<ItemRef>, val newerProfileRevision: Long?)

    /**
     * Status of a saved revision against the current library. A newer library version is only an offer:
     * it stays out of [RevisionStatus.staleReasons] until the user applies it to the scene.
     */
    fun forRevision(revision: SceneRevision, context: SceneContext): RevisionStatus {
        val reasons = mutableListOf<String>()
        if (revision.engineVersion != context.config.engineVersion) reasons += StaleReason.ENGINE_VERSION_CHANGED
        if (revision.configurationRevision != context.config.revision) reasons += StaleReason.CONFIGURATION_CHANGED
        val profile = context.profile(revision.profileId, revision.profileRevision)
        val hash = profile?.let { InputHash.of(revision.instances, it, context) }
        if (hash == null) {
            reasons += StaleReason.REFERENCED_VERSION_MISSING
        } else if (hash != revision.canonicalInputHash && reasons.isEmpty()) {
            reasons += StaleReason.INPUT_CHANGED
        }
        val newerItems = revision.instances.mapNotNull { instance ->
            context.newestItemRevision(instance.itemId)
                ?.takeIf { it > instance.itemRevision }
                ?.let { ItemRef(instance.itemId, it) }
        }.distinct()
        val newerProfile = context.newestProfileRevision(revision.profileId)?.takeIf { it > revision.profileRevision }
        return RevisionStatus(reasons.distinct(), newerItems, newerProfile)
    }

    /**
     * "It fits" may only appear when this holds. It requires a current confirmed judgement of a clear placement
     * with handling OK, and it still does not promote opening or route results, which stay separate axes.
     */
    fun fitsConfirmed(state: SceneDraftState, context: SceneContext): Boolean {
        val validation = state.validation ?: return false
        val confirmation = state.confirmation ?: return false
        return forDraft(state, context).isEmpty() && confirmation.inputHash == validation.inputHash &&
            validation.snapshot.placement == ai.cargo.contracts.PlacementStatus.CLEAR &&
            validation.snapshot.handling == ai.cargo.contracts.HandlingStatus.OK
    }
}

object SavePreparation {
    /** [head] is the stored head at the moment of the request; the data layer repeats the check in its transaction. */
    fun prepare(state: SceneDraftState, head: Long?, context: SceneContext, at: String): SaveDecision {
        if (state.pendingSearch != null) return SaveDecision.Rejected(SaveRejection.SEARCH_RUNNING)
        if (head != state.baseRevision) return SaveDecision.Rejected(SaveRejection.HEAD_MOVED)
        val locked = LockedDiff.violations(state.baseInstances, state.instances, state.lockedChanges, state.unlockedIds)
        if (locked.isNotEmpty()) return SaveDecision.Rejected(SaveRejection.LOCKED_DIFF, locked)
        val validation = state.validation?.takeUnless { it.restoredFromRevision }
            ?: return SaveDecision.Rejected(SaveRejection.NOT_VALIDATED)
        val stale = StaleAnalysis.forDraft(state, context)
        if (stale.isNotEmpty()) return SaveDecision.Rejected(SaveRejection.STALE, staleReasons = stale)
        val confirmation = state.confirmation ?: return SaveDecision.Rejected(SaveRejection.NOT_CONFIRMED)
        if (confirmation.inputHash != validation.inputHash) return SaveDecision.Rejected(SaveRejection.NOT_CONFIRMED)
        val missing = validation.issues - confirmation.acknowledged.toSet()
        if (missing.isNotEmpty()) return SaveDecision.Rejected(SaveRejection.ACKNOWLEDGEMENT_MISSING, missing.map { it.instanceId }.distinct())
        val draft = SceneRevision(
            schemaVersion = 1,
            id = state.sceneId,
            revision = (head ?: 0) + 1,
            createdAt = state.createdAt,
            updatedAt = at,
            parentRevision = head,
            profileId = state.profileId,
            profileRevision = state.profileRevision,
            instances = state.instances,
            engineVersion = context.config.engineVersion,
            configurationRevision = context.config.revision,
            judgementSnapshot = validation.snapshot,
            canonicalInputHash = validation.inputHash,
            checksum = "0".repeat(64),
        )
        val revision = draft.copy(checksum = SceneChecksum.of(draft))
        return SaveDecision.Approved(
            PreparedSave(
                revision = revision,
                expectedHead = head,
                lockedChanges = state.lockedChanges,
                unlockedIds = state.unlockedIds,
                acknowledged = confirmation.acknowledged,
                nextDraft = SceneDraftState.fromRevision(revision, state.camera),
            ),
        )
    }
}
