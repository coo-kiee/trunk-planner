package ai.cargo.scene

import ai.cargo.contracts.Instance
import ai.cargo.contracts.Vec3Mm
import ai.cargo.geometry.Orientations
import ai.cargo.scene.SceneCommand.*

/** Pure transition function. Input changes always drop validation and confirmation; history never mutates in place. */
object SceneReducer {
    fun reduce(state: SceneDraftState, command: SceneCommand, context: SceneContext, at: String): ReduceResult = when (command) {
        is Add -> add(state, command, context, at)
        is Move -> move(state, command.instanceId, command.poseMm, null, context, at, "MOVE")
        is Rotate -> move(state, command.instanceId, command.poseMm, command.orientationIndex, context, at, "ROTATE")
        is Remove -> remove(state, command, at)
        is Lock -> lock(state, command, at)
        is Unlock -> unlock(state, command, at)
        is ApplyItemVersion -> applyItem(state, command, context, at)
        is ApplyProfileVersion -> applyProfile(state, command, context, at)
        is ApplyValidation -> applyValidation(state, command, context, at)
        is ConfirmPlacement -> confirm(state, command, context, at)
        is BeginSearch -> beginSearch(state, command, context, at)
        is FinishSearch -> finishSearch(state, command, context, at)
        is AbortSearch -> abortSearch(state, command)
        is SetCamera -> ReduceResult.Accepted(state.copy(camera = command.camera))
        Undo -> restore(state, fromUndo = true, at)
        Redo -> restore(state, fromUndo = false, at)
    }

    /**
     * A marker left behind means the process died during a search. Called when a draft is reopened:
     * the search reads as interrupted, never as impossible, and no pose comes out of it.
     */
    fun recoverAfterRestore(state: SceneDraftState): SceneDraftState {
        val pending = state.pendingSearch ?: return state
        return state.copy(
            pendingSearch = null,
            lastSearch = SearchRecord(
                status = ai.cargo.contracts.SearchStatus.SEARCH_INTERRUPTED,
                item = pending.item,
                fingerprint = pending.fingerprint,
                interruptCause = SearchInterruptCause.PROCESS_DEATH,
            ),
            validation = null,
            confirmation = null,
        )
    }

    private fun add(state: SceneDraftState, c: Add, context: SceneContext, at: String): ReduceResult {
        if (state.instances.any { it.instanceId == c.instanceId }) return reject(RejectReason.DUPLICATE_INSTANCE, c.instanceId)
        if (state.instances.size >= context.config.maxInstances) return reject(RejectReason.INSTANCE_LIMIT, c.instanceId)
        if (context.item(c.item) == null) return reject(RejectReason.REFERENCED_VERSION_MISSING, c.instanceId)
        poseProblem(c.poseMm, context)?.let { return reject(it, c.instanceId) }
        if (!Orientations.isValidIndex(c.orientationIndex)) return reject(RejectReason.ORIENTATION_OUT_OF_RANGE, c.instanceId)
        val instance = Instance(c.instanceId, c.item.itemId, c.item.revision, c.poseMm, c.orientationIndex, c.locked)
        val search = state.lastSearch?.takeIf { c.fromSearchToken != null && it.item == c.item }
            ?.copy(subjectInstanceId = c.instanceId) ?: state.lastSearch
        return edit(state, at, "ADD", c.instanceId) { it.copy(instances = it.instances + instance, lastSearch = search) }
    }

    private fun move(
        state: SceneDraftState,
        id: String,
        pose: Vec3Mm,
        orientation: Long?,
        context: SceneContext,
        at: String,
        kind: String,
    ): ReduceResult {
        val current = state.instances.find { it.instanceId == id } ?: return reject(RejectReason.UNKNOWN_INSTANCE, id)
        lockedBlock(state, current)?.let { return it }
        poseProblem(pose, context)?.let { return reject(it, id) }
        if (orientation != null && !Orientations.isValidIndex(orientation)) return reject(RejectReason.ORIENTATION_OUT_OF_RANGE, id)
        val moved = current.copy(poseMm = pose, orientationIndex = orientation ?: current.orientationIndex)
        return edit(state, at, kind, id) {
            it.copy(
                instances = it.instances.replacing(moved),
                lockedChanges = it.lockedChanges.grantedFor(state, id, LockedChangeKind.POSE),
            )
        }
    }

    private fun remove(state: SceneDraftState, c: Remove, at: String): ReduceResult {
        val current = state.instances.find { it.instanceId == c.instanceId } ?: return reject(RejectReason.UNKNOWN_INSTANCE, c.instanceId)
        lockedBlock(state, current)?.let { return it }
        return edit(state, at, "REMOVE", c.instanceId) {
            it.copy(
                instances = it.instances.filterNot { i -> i.instanceId == c.instanceId },
                lockedChanges = it.lockedChanges.grantedFor(state, c.instanceId, LockedChangeKind.REMOVAL),
            )
        }
    }

    /** Locked flags are not judgement inputs, so locking keeps a valid judgement but still enters undo history. */
    private fun lock(state: SceneDraftState, c: Lock, at: String): ReduceResult {
        val current = state.instances.find { it.instanceId == c.instanceId } ?: return reject(RejectReason.UNKNOWN_INSTANCE, c.instanceId)
        if (current.locked) return reject(RejectReason.NOT_UNLOCKED, c.instanceId)
        return historyEdit(state, at, "LOCK", c.instanceId) {
            it.copy(instances = it.instances.replacing(current.copy(locked = true)), unlockedIds = it.unlockedIds - c.instanceId)
        }
    }

    private fun unlock(state: SceneDraftState, c: Unlock, at: String): ReduceResult {
        val current = state.instances.find { it.instanceId == c.instanceId } ?: return reject(RejectReason.UNKNOWN_INSTANCE, c.instanceId)
        if (!current.locked) return reject(RejectReason.NOT_LOCKED, c.instanceId)
        return historyEdit(state, at, "UNLOCK", c.instanceId) {
            it.copy(instances = it.instances.replacing(current.copy(locked = false)), unlockedIds = (it.unlockedIds + c.instanceId).distinct())
        }
    }

    /** Explicit apply: the pose stays, the judgement becomes stale, and only revalidation can clear it. */
    private fun applyItem(state: SceneDraftState, c: ApplyItemVersion, context: SceneContext, at: String): ReduceResult {
        val current = state.instances.find { it.instanceId == c.instanceId } ?: return reject(RejectReason.UNKNOWN_INSTANCE, c.instanceId)
        if (current.itemRevision == c.revision) return reject(RejectReason.NO_VERSION_CHANGE, c.instanceId)
        if (context.item(ItemRef(current.itemId, c.revision)) == null) return reject(RejectReason.REFERENCED_VERSION_MISSING, c.instanceId)
        return edit(state, at, "APPLY_ITEM_VERSION", c.instanceId) {
            it.copy(
                instances = it.instances.replacing(current.copy(itemRevision = c.revision)),
                lockedChanges = it.lockedChanges.grantedFor(state, c.instanceId, LockedChangeKind.ITEM_REVISION),
                pendingStale = (it.pendingStale + StaleReason.ITEM_VERSION_APPLIED).distinct(),
            )
        }
    }

    private fun applyProfile(state: SceneDraftState, c: ApplyProfileVersion, context: SceneContext, at: String): ReduceResult {
        if (state.profileRevision == c.revision) return reject(RejectReason.NO_VERSION_CHANGE)
        if (context.profile(state.profileId, c.revision) == null) return reject(RejectReason.REFERENCED_VERSION_MISSING)
        return edit(state, at, "APPLY_PROFILE_VERSION", null) {
            it.copy(profileRevision = c.revision, pendingStale = (it.pendingStale + StaleReason.PROFILE_VERSION_APPLIED).distinct())
        }
    }

    /** A validation computed against older input arrives too late and must not attach to the current draft. */
    private fun applyValidation(state: SceneDraftState, c: ApplyValidation, context: SceneContext, at: String): ReduceResult {
        val hash = SceneValidator.currentHash(state, context) ?: return reject(RejectReason.REFERENCED_VERSION_MISSING)
        if (c.record.inputHash != hash) return reject(RejectReason.STALE_VALIDATION_RESULT)
        return ReduceResult.Accepted(
            state.copy(validation = c.record, confirmation = null, pendingStale = emptyList()).logged(at, "VALIDATE", null),
        )
    }

    private fun confirm(state: SceneDraftState, c: ConfirmPlacement, context: SceneContext, at: String): ReduceResult {
        val hash = SceneValidator.currentHash(state, context) ?: return reject(RejectReason.REFERENCED_VERSION_MISSING)
        val validation = state.validation?.takeUnless { it.restoredFromRevision } ?: return reject(RejectReason.NOT_VALIDATED)
        if (validation.inputHash != hash || c.inputHash != hash || state.pendingStale.isNotEmpty()) {
            return reject(RejectReason.STALE_VALIDATION_RESULT)
        }
        // A new or moved pose cannot be confirmed airborne, badly supported, wrongly rotated or on a non-stackable item.
        val changed = SceneValidator.changedFromBase(state).map { it.instanceId }.toSet()
        val invalid = validation.issues.filter { it.kind != IssueKind.PLACEMENT_CONFLICT && it.instanceId in changed }
        if (invalid.isNotEmpty()) return ReduceResult.Rejected(RejectReason.NEW_PLACEMENT_INVALID, invalid.map { it.instanceId }.distinct())
        val missing = validation.issues - c.acknowledged.toSet()
        if (missing.isNotEmpty()) return ReduceResult.Rejected(RejectReason.ACKNOWLEDGEMENT_REQUIRED, missing.map { it.instanceId }.distinct())
        return ReduceResult.Accepted(
            state.copy(confirmation = Confirmation(hash, c.acknowledged, at)).logged(at, "CONFIRM", null),
        )
    }

    private fun beginSearch(state: SceneDraftState, c: BeginSearch, context: SceneContext, at: String): ReduceResult {
        if (state.pendingSearch != null) return reject(RejectReason.SEARCH_ALREADY_RUNNING)
        val profile = context.profile(state.profileId, state.profileRevision) ?: return reject(RejectReason.REFERENCED_VERSION_MISSING)
        val fingerprint = InputHash.searchFingerprint(state.instances, profile, c.item, context)
            ?: return reject(RejectReason.REFERENCED_VERSION_MISSING)
        return ReduceResult.Accepted(
            state.copy(pendingSearch = PendingSearch(c.token, c.item, fingerprint)).logged(at, "SEARCH_START", null),
        )
    }

    private fun finishSearch(state: SceneDraftState, c: FinishSearch, context: SceneContext, at: String): ReduceResult {
        val pending = state.pendingSearch
        if (pending == null || pending.token != c.token) return reject(RejectReason.STALE_SEARCH_RESULT)
        val profile = context.profile(state.profileId, state.profileRevision) ?: return reject(RejectReason.REFERENCED_VERSION_MISSING)
        val current = InputHash.searchFingerprint(state.instances, profile, pending.item, context)
        if (current != c.record.fingerprint || current != pending.fingerprint) {
            return ReduceResult.Accepted(state.copy(pendingSearch = null).logged(at, "SEARCH_RESULT_DISCARDED", null))
        }
        return ReduceResult.Accepted(
            state.copy(pendingSearch = null, lastSearch = c.record, validation = null, confirmation = null).logged(at, "SEARCH_FINISH", null),
        )
    }

    private fun abortSearch(state: SceneDraftState, c: AbortSearch): ReduceResult {
        if (state.pendingSearch?.token != c.token) return reject(RejectReason.STALE_SEARCH_RESULT)
        return ReduceResult.Accepted(state.copy(pendingSearch = null))
    }

    /** Undo and redo restore authority together with the arrangement, then require revalidation. */
    private fun restore(state: SceneDraftState, fromUndo: Boolean, at: String): ReduceResult {
        val source = if (fromUndo) state.undo else state.redo
        val entry = source.lastOrNull() ?: return reject(if (fromUndo) RejectReason.NOTHING_TO_UNDO else RejectReason.NOTHING_TO_REDO)
        val current = state.snapshot()
        val restored = state.copy(
            instances = entry.instances,
            profileRevision = entry.profileRevision,
            unlockedIds = entry.unlockedIds,
            lockedChanges = entry.lockedChanges,
            pendingStale = entry.pendingStale,
            lastSearch = entry.lastSearch,
            undo = if (fromUndo) state.undo.dropLast(1) else (state.undo + current).takeLast(SceneDraftState.HISTORY_LIMIT),
            redo = if (fromUndo) (state.redo + current).takeLast(SceneDraftState.HISTORY_LIMIT) else state.redo.dropLast(1),
            validation = null,
            confirmation = null,
        )
        return ReduceResult.Accepted(restored.logged(at, if (fromUndo) "UNDO" else "REDO", null))
    }

    /** A judgement-relevant edit: records history, clears redo and invalidates validation and confirmation. */
    private fun edit(state: SceneDraftState, at: String, kind: String, id: String?, change: (SceneDraftState) -> SceneDraftState): ReduceResult {
        val base = state.copy(validation = null, confirmation = null)
        return historyEdit(base, at, kind, id, state.snapshot(), change)
    }

    private fun historyEdit(
        state: SceneDraftState,
        at: String,
        kind: String,
        id: String?,
        before: HistorySnapshot = state.snapshot(),
        change: (SceneDraftState) -> SceneDraftState,
    ): ReduceResult {
        val next = change(state).copy(
            undo = (state.undo + before).takeLast(SceneDraftState.HISTORY_LIMIT),
            redo = emptyList(),
        )
        return ReduceResult.Accepted(next.logged(at, kind, id))
    }

    /** Move, rotate or remove of a locked instance requires a prior explicit Unlock of it in this draft. */
    private fun lockedBlock(state: SceneDraftState, current: Instance): ReduceResult? {
        val baseLocked = state.baseInstances.any { it.instanceId == current.instanceId && it.locked }
        val unlocked = current.instanceId in state.unlockedIds
        return if (current.locked || (baseLocked && !unlocked)) reject(RejectReason.LOCKED_REQUIRES_UNLOCK, current.instanceId) else null
    }

    private fun poseProblem(pose: Vec3Mm, context: SceneContext): RejectReason? {
        val limit = context.config.coordinateAbsMaxMm
        val ok = kotlin.math.abs(pose.x) <= limit && kotlin.math.abs(pose.y) <= limit && kotlin.math.abs(pose.z) <= limit
        return if (ok) null else RejectReason.POSE_OUT_OF_RANGE
    }

    private fun List<LockedChange>.grantedFor(state: SceneDraftState, id: String, kind: LockedChangeKind): List<LockedChange> =
        if (state.baseInstances.any { it.instanceId == id && it.locked }) (this + LockedChange(id, kind)).distinct() else this

    private fun List<Instance>.replacing(updated: Instance) = map { if (it.instanceId == updated.instanceId) updated else it }

    private fun SceneDraftState.logged(at: String, kind: String, id: String?) = copy(
        events = (events + CommandEvent(nextEventSeq, kind, id, at)).takeLast(SceneDraftState.EVENT_LIMIT),
        nextEventSeq = nextEventSeq + 1,
    )

    private fun reject(reason: RejectReason, vararg ids: String) = ReduceResult.Rejected(reason, ids.toList())
}
