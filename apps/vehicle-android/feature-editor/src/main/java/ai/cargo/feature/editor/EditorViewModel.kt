package ai.cargo.feature.editor

import ai.cargo.contracts.Camera
import ai.cargo.contracts.Instance
import ai.cargo.contracts.SearchStatus
import ai.cargo.contracts.Vec3Mm
import ai.cargo.data.CargoStore
import ai.cargo.data.DraftLoad
import ai.cargo.data.SaveFaultHook
import ai.cargo.data.SaveResult
import ai.cargo.data.SceneLoad
import ai.cargo.geometry.SearchBudget
import ai.cargo.scene.ItemRef
import ai.cargo.scene.ReduceResult
import ai.cargo.scene.SaveDecision
import ai.cargo.scene.SavePreparation
import ai.cargo.scene.SceneCommand
import ai.cargo.scene.SceneDraftState
import ai.cargo.scene.SceneReducer
import ai.cargo.scene.SceneSearch
import ai.cargo.scene.SceneValidator
import ai.cargo.scene.SearchRun
import ai.cargo.scene.ValidationOutcome
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Variant-specific seams. The release build passes the product values. */
class EditorHooks(val saveFault: () -> SaveFaultHook, val searchBudget: () -> SearchBudget)

/**
 * Effects only. Scene rules live in SceneReducer and wording in the presenter. Every accepted edit commits the whole
 * draft in order on the storage dispatcher, so a kill at any moment loses at most the command in flight.
 */
class EditorViewModel(
    private val store: CargoStore,
    private val sceneId: String,
    private val hooks: EditorHooks,
    private val io: CoroutineDispatcher,
    private val compute: CoroutineDispatcher,
    private val isoNow: () -> String,
    private val newId: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = mutable.asStateFlow()
    private var validationJob: Job? = null
    private var searchJob: Job? = null
    private val cancelSearch = AtomicBoolean(false)

    init { reload() }

    /** Reads everything back from storage. Called when the screen is entered, so memory never outranks Room. */
    fun reload() {
        viewModelScope.launch {
            val loaded = withContext(io) { load() }
            mutable.update { previous ->
                loaded.copy(
                    selected = previous.selected?.takeIf { id -> loaded.draft?.instances?.any { it.instanceId == id } == true },
                    stepMm = previous.stepMm,
                    restoredDraft = previous.restoredDraft || loaded.restoredDraft,
                )
            }
        }
    }

    private fun load(): EditorUiState {
        val at = isoNow()
        val context = store.library.sceneContext(at)
        val library = store.library.latestItems(at)
        val base = EditorUiState(context = context, library = library)
        val head = store.scenes.load(sceneId, at)
        return when (head) {
            is SceneLoad.RecoveredReadOnly -> base.copy(
                mode = EditorMode.RecoveredReadOnly(head.revision.revision, head.damagedRevision),
                draft = SceneDraftState.fromRevision(head.revision, cameraFor(head.revision.profileId, head.revision.profileRevision, base)),
            )
            is SceneLoad.NoValidRevision -> base.copy(mode = EditorMode.Unavailable("NO_VALID_REVISION", head.damagedRevision))
            is SceneLoad.LegacyView -> base.copy(mode = EditorMode.Unavailable("LEGACY_READ_ONLY", null))
            is SceneLoad.Healthy -> fromDraftOr(base, at) {
                SceneDraftState.fromRevision(head.revision, cameraFor(head.revision.profileId, head.revision.profileRevision, base))
            }
            SceneLoad.NoScene -> fromDraftOr(base, at, null)
        }
    }

    private fun cameraFor(profileId: String, revision: Long, base: EditorUiState): Camera =
        base.context?.profile(profileId, revision)?.let(EditorRules::fitCamera) ?: SceneDraftState.DEFAULT_CAMERA

    private fun fromDraftOr(base: EditorUiState, at: String, fallback: (() -> SceneDraftState)?): EditorUiState =
        when (val draft = store.drafts.load(sceneId, at)) {
            is DraftLoad.Restored -> {
                val isFresh = draft.state.undo.isEmpty() && draft.state.redo.isEmpty() && draft.state.instances == draft.state.baseInstances
                base.copy(mode = EditorMode.Editable, draft = draft.state, restoredDraft = !isFresh)
            }
            DraftLoad.None -> fallback?.let { base.copy(mode = EditorMode.Editable, draft = it()) }
                ?: base.copy(mode = EditorMode.Unavailable("SCENE_MISSING", null))
            DraftLoad.Damaged -> fallback?.let { base.copy(mode = EditorMode.Editable, draft = it(), draftDamaged = true) }
                ?: base.copy(mode = EditorMode.Unavailable("DRAFT_DAMAGED", null))
        }

    private val editable get() = mutable.value.mode == EditorMode.Editable

    /** Applies a command through the pure reducer. A rejection leaves state as it was and shows its reason. */
    private fun dispatch(command: SceneCommand, revalidate: Boolean = true, lastMove: String? = null): Boolean {
        val current = mutable.value
        val draft = current.draft ?: return false
        val context = current.context ?: return false
        if (!editable) return false
        return when (val result = SceneReducer.reduce(draft, command, context, isoNow())) {
            is ReduceResult.Rejected -> {
                mutable.update { it.copy(rejection = EditorRules.reject(result.reason)) }
                false
            }
            is ReduceResult.Accepted -> {
                val changedInput = result.state.instances != draft.instances || result.state.profileRevision != draft.profileRevision
                mutable.update {
                    it.copy(
                        draft = result.state, rejection = null, offer = if (changedInput) null else it.offer,
                        lastMove = lastMove ?: it.lastMove, notAvailable = if (changedInput) null else it.notAvailable,
                        geometry = if (changedInput) null else it.geometry,
                        save = if (changedInput) SaveState.Idle else it.save,
                    )
                }
                commit(result.state)
                if (revalidate && changedInput) validateNow()
                true
            }
        }
    }

    private fun commit(draft: SceneDraftState) {
        viewModelScope.launch(io) { store.drafts.commit(draft, isoNow()) }
    }

    private fun selectedInstance(): Instance? = mutable.value.let { s -> s.draft?.instances?.firstOrNull { it.instanceId == s.selected } }

    fun select(id: String?) = mutable.update { it.copy(selected = id, poseEntry = null, rejection = null) }
    fun setStep(mm: Long) = mutable.update { it.copy(stepMm = mm) }

    fun nudge(direction: MoveDirection) {
        val instance = selectedInstance() ?: return
        val step = mutable.value.stepMm
        dispatch(EditorRules.nudge(instance, direction, step), lastMove = EditorRules.moveLabel(direction, step))
    }

    fun rotate() {
        val instance = selectedInstance() ?: return
        val context = mutable.value.context ?: return
        val next = EditorRules.nextOrientation(instance, context) ?: return
        dispatch(SceneCommand.Rotate(instance.instanceId, next, instance.poseMm))
    }

    fun toggleLock() {
        val instance = selectedInstance() ?: return
        dispatch(if (instance.locked) SceneCommand.Unlock(instance.instanceId) else SceneCommand.Lock(instance.instanceId), revalidate = false)
    }

    fun remove() {
        val instance = selectedInstance() ?: return
        if (dispatch(SceneCommand.Remove(instance.instanceId))) select(null)
    }

    fun undo() { dispatch(SceneCommand.Undo) }
    fun redo() { dispatch(SceneCommand.Redo) }

    fun beginPoseEntry() {
        val p = selectedInstance()?.poseMm ?: return
        mutable.update { it.copy(poseEntry = PoseEntry(mapOf(PoseAxis.X to p.x.toString(), PoseAxis.Y to p.y.toString(), PoseAxis.Z to p.z.toString()))) }
    }

    fun changePoseEntry(entry: PoseEntry?) = mutable.update { it.copy(poseEntry = entry) }

    fun applyPoseEntry() {
        val instance = selectedInstance() ?: return
        val entry = mutable.value.poseEntry ?: return
        fun value(axis: PoseAxis) = entry.values[axis]?.toLongOrNull()
        val x = value(PoseAxis.X); val y = value(PoseAxis.Y); val z = value(PoseAxis.Z)
        if (x == null || y == null || z == null) {
            mutable.update { it.copy(rejection = "좌표를 숫자로 입력하세요.") }
            return
        }
        if (dispatch(SceneCommand.Move(instance.instanceId, Vec3Mm(x, y, z)))) changePoseEntry(null)
    }

    fun setCamera(camera: Camera) { dispatch(SceneCommand.SetCamera(camera), revalidate = false) }

    fun addDirect(item: ItemRef) {
        val context = mutable.value.context ?: return
        val id = newId()
        val orientation = EditorRules.firstOrientation(item.itemId, item.revision, context)
        if (dispatch(SceneCommand.Add(id, item, Vec3Mm(0, 0, 0), orientation))) select(id)
    }

    fun search(item: ItemRef) {
        val token = newId()
        val current = mutable.value
        if (current.searching) return
        cancelSearch.set(false)
        if (!dispatch(SceneCommand.BeginSearch(token, item), revalidate = false)) return
        val begun = mutable.value.draft ?: return
        val context = current.context ?: return
        mutable.update { it.copy(searching = true, offer = null) }
        searchJob = viewModelScope.launch {
            val run = withContext(compute) {
                SceneSearch.run(begun, item, context, hooks.searchBudget()) { cancelSearch.get() }
            }
            finishSearch(token, item, run)
        }
    }

    private fun finishSearch(token: String, item: ItemRef, run: SearchRun) {
        mutable.update { it.copy(searching = false) }
        when (run) {
            is SearchRun.Done -> {
                dispatch(SceneCommand.FinishSearch(token, run.record), revalidate = false)
                val pose = run.outcome.pose
                if (run.record.status == SearchStatus.FOUND_PLACEMENT && pose != null) {
                    val uncertain = run.outcome.details.placementAtCandidate != ai.cargo.contracts.PlacementStatus.CLEAR
                    mutable.update { it.copy(offer = CandidateOffer(item, token, pose, uncertain)) }
                }
            }
            is SearchRun.NotAvailable -> {
                dispatch(SceneCommand.AbortSearch(token), revalidate = false)
                mutable.update { it.copy(notAvailable = NotAvailableNote(run.reason.name, run.detail)) }
            }
        }
    }

    fun cancelSearch() = cancelSearch.set(true)

    /** Searches again with the item of the interrupted run. Nothing about the earlier run is treated as a result. */
    fun resumeSearch() { mutable.value.draft?.lastSearch?.item?.let(::search) }

    fun acceptOffer() {
        val offer = mutable.value.offer ?: return
        val id = newId()
        if (dispatch(SceneCommand.Add(id, offer.item, offer.pose.poseMm, offer.pose.orientationIndex, fromSearchToken = offer.token))) select(id)
    }

    /** Explicit: the pose stays, the judgement becomes stale, and only the user's revalidation clears it. */
    fun applyItemVersion(instanceId: String, revision: Long) {
        dispatch(SceneCommand.ApplyItemVersion(instanceId, revision), revalidate = false)
    }

    fun applyProfileVersion(revision: Long) { dispatch(SceneCommand.ApplyProfileVersion(revision), revalidate = false) }

    fun validateNow() {
        val current = mutable.value
        val draft = current.draft ?: return
        val context = current.context ?: return
        validationJob?.cancel()
        mutable.update { it.copy(validating = true) }
        validationJob = viewModelScope.launch {
            val outcome = withContext(compute) { SceneValidator.validate(draft, context) }
            mutable.update { it.copy(validating = false) }
            when (outcome) {
                is ValidationOutcome.Ready -> {
                    // A result for older input is refused here; the newer edit has its own validation running.
                    val after = mutable.value.draft ?: return@launch
                    val result = SceneReducer.reduce(after, SceneCommand.ApplyValidation(outcome.record), context, isoNow())
                    if (result is ReduceResult.Accepted) {
                        mutable.update { it.copy(draft = result.state, geometry = outcome.geometry, notAvailable = null) }
                        commit(result.state)
                    }
                }
                is ValidationOutcome.NotAvailable -> mutable.update { it.copy(notAvailable = NotAvailableNote(outcome.reason.name, outcome.detail)) }
            }
        }
    }

    /** Places the positions on record. Issues on screen are passed as seen only through the explicit [acknowledge]. */
    fun confirm(acknowledge: Boolean) {
        val draft = mutable.value.draft ?: return
        val validation = draft.validation ?: run {
            mutable.update { it.copy(rejection = "현재 배치를 먼저 재검증해야 합니다.") }
            return
        }
        dispatch(SceneCommand.ConfirmPlacement(validation.inputHash, if (acknowledge) validation.issues else emptyList()), revalidate = false)
    }

    /** The only path to a new revision, and only on the user's press. Success is shown after the commit returns. */
    fun save() {
        val current = mutable.value
        val draft = current.draft ?: return
        val context = current.context ?: return
        if (!editable || current.save == SaveState.Saving) return
        mutable.update { it.copy(save = SaveState.Saving) }
        viewModelScope.launch {
            val at = isoNow()
            val outcome = withContext(io) {
                val decision = SavePreparation.prepare(draft, store.scenes.head(draft.sceneId), context, at)
                if (decision is SaveDecision.Approved) decision to store.scenes.save(decision.save, hooks.saveFault()) else decision to null
            }
            val (decision, result) = outcome
            when {
                decision is SaveDecision.Rejected -> mutable.update { it.copy(save = SaveState.Refused(EditorRules.refuse(decision.reason))) }
                decision is SaveDecision.Approved && result is SaveResult.Saved ->
                    mutable.update { it.copy(save = SaveState.Saved(result.revision), draft = decision.save.nextDraft, geometry = null, restoredDraft = false) }
                else -> mutable.update { it.copy(save = SaveState.Refused(saveFailure(result))) }
            }
        }
    }

    private fun saveFailure(result: SaveResult?) = when (result) {
        is SaveResult.HeadMoved -> "저장 안 됨: 저장본이 먼저 변경되었습니다(r${result.actual})."
        is SaveResult.LockedDiffRefused -> "저장 안 됨: 고정짐 변경이 거부되었습니다."
        is SaveResult.HeadDamaged -> "저장 안 됨: 현재 저장본 r${result.damagedRevision}이 손상되었습니다."
        is SaveResult.Invalid -> "저장 안 됨: 저장 값 검사 실패."
        else -> "저장 안 됨."
    }

    fun adoptRecovery() {
        val mode = mutable.value.mode as? EditorMode.RecoveredReadOnly ?: return
        viewModelScope.launch {
            withContext(io) { store.scenes.adoptRecovery(sceneId, mode.shownRevision, isoNow()) }
            reload()
        }
    }

}
