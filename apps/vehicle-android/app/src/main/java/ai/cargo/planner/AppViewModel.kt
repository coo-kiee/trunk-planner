package ai.cargo.planner

import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.data.CargoStore
import ai.cargo.data.DraftLoad
import ai.cargo.data.SceneLoad
import ai.cargo.data.StoreOpen
import ai.cargo.feature.editor.EditorRules
import ai.cargo.feature.library.ItemForm
import ai.cargo.feature.library.ItemFormRules
import ai.cargo.feature.library.LibraryEntry
import ai.cargo.feature.library.LibraryGateway
import ai.cargo.feature.library.LibraryMessage
import ai.cargo.feature.library.LibraryUiState
import ai.cargo.feature.loading.LoadingPlan
import ai.cargo.feature.loading.LoadingPlanner
import ai.cargo.feature.profile.ProfileForm
import ai.cargo.feature.profile.ProfileRules
import ai.cargo.feature.profile.ProfileUiState
import ai.cargo.vehicleport.api.DoorSuggestion
import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SceneHealth {
    data object Ok : SceneHealth
    data class RecoveredReadOnly(val shown: Long, val damaged: Long) : SceneHealth
    data class NoValid(val damaged: Long) : SceneHealth
    data object Legacy : SceneHealth
    data object DraftOnly : SceneHealth
}

data class SceneSummary(
    val sceneId: String,
    val headRevision: Long?,
    val savedAt: String?,
    val instanceCount: Int,
    val profile: ProfileVersion?,
    val hasDraftEdits: Boolean,
    val health: SceneHealth,
)

data class HomeUiState(val scenes: List<SceneSummary> = emptyList(), val quarantineCount: Int = 0, val loaded: Boolean = false)

data class QuarantineRow(val table: String, val key: String, val reason: String, val detectedAt: String)

/** Routing plus the effects of V0, V1, V2 and V6. V3, V4 and V5 have their own view models. */
class AppViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val graph = (application as CargoApp).graph
    private val io get() = graph.io

    private val routes = MutableStateFlow(RouteStack.decode(saved[ROUTE_KEY]))
    val stack: StateFlow<List<AppRoute>> = routes.asStateFlow()
    private val opened = MutableStateFlow<StoreOpen?>(null)
    val storeOpen: StateFlow<StoreOpen?> = opened.asStateFlow()
    private val homeState = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = homeState.asStateFlow()
    private val libraryState = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = libraryState.asStateFlow()
    private val profileState = MutableStateFlow(ProfileUiState())
    val profile: StateFlow<ProfileUiState> = profileState.asStateFlow()
    private val checklist = MutableStateFlow<LoadingPlan?>(null)
    val plan: StateFlow<LoadingPlan?> = checklist.asStateFlow()
    private val quarantineRows = MutableStateFlow<List<QuarantineRow>>(emptyList())
    val quarantine: StateFlow<List<QuarantineRow>> = quarantineRows.asStateFlow()
    private val suggestion = MutableStateFlow<DoorSuggestion?>(null)
    val doorSuggestion: StateFlow<DoorSuggestion?> = suggestion.asStateFlow()
    private var lastSceneId: String? = null

    init {
        viewModelScope.launch {
            val open = withContext(io) { graph.storeOpen }
            opened.value = open
            if (open is StoreOpen.Ready) refresh(routes.value.last())
        }
        viewModelScope.launch { graph.vehicle.doorSuggestions.collect { suggestion.value = it } }
        graph.vehicle.start()
    }

    override fun onCleared() {
        graph.vehicle.release()
        super.onCleared()
    }

    fun navigate(route: AppRoute) {
        setStack(RouteStack.push(routes.value, route))
        refresh(route)
    }

    fun back() {
        setStack(RouteStack.pop(routes.value))
        refresh(routes.value.last())
    }

    private fun setStack(next: List<AppRoute>) {
        routes.value = next
        saved[ROUTE_KEY] = RouteStack.encode(next)
    }

    private fun refresh(route: AppRoute) {
        when (route) {
            AppRoute.Home -> refreshHome()
            AppRoute.Library -> refreshLibrary()
            AppRoute.Profile -> refreshProfiles()
            AppRoute.Recovery -> refreshQuarantine()
            is AppRoute.Checklist -> refreshPlan(route.sceneId)
            is AppRoute.Editor -> lastSceneId = route.sceneId
            else -> Unit
        }
    }

    private fun withStore(block: suspend (CargoStore) -> Unit) {
        viewModelScope.launch { withContext(io) { graph.store?.let { block(it) } } }
    }

    private fun refreshHome() = withStore { store ->
        val at = graph.isoNow()
        val saved = store.scenes.sceneIds()
        val unsaved = graph.index.unsavedScenes().filterNot { it in saved }
        val rows = (saved + unsaved).map { summary(store, it, at) }
        homeState.value = HomeUiState(rows, store.quarantine.entries().size, true)
    }

    private fun summary(store: CargoStore, id: String, at: String): SceneSummary {
        val load = store.scenes.load(id, at)
        val draft = (store.drafts.load(id, at) as? DraftLoad.Restored)?.state
        fun profileOf(pid: String, rev: Long) = store.library.profile(pid, rev, at)
        val revision = when (load) {
            is SceneLoad.Healthy -> load.revision
            is SceneLoad.RecoveredReadOnly -> load.revision
            else -> null
        }
        val health = when (load) {
            is SceneLoad.Healthy -> SceneHealth.Ok
            is SceneLoad.RecoveredReadOnly -> SceneHealth.RecoveredReadOnly(load.revision.revision, load.damagedRevision)
            is SceneLoad.NoValidRevision -> SceneHealth.NoValid(load.damagedRevision)
            is SceneLoad.LegacyView -> SceneHealth.Legacy
            SceneLoad.NoScene -> SceneHealth.DraftOnly
        }
        val edited = draft != null && (draft.undo.isNotEmpty() || draft.redo.isNotEmpty() || draft.instances != draft.baseInstances)
        return SceneSummary(
            sceneId = id,
            headRevision = revision?.revision,
            savedAt = revision?.updatedAt,
            instanceCount = revision?.instances?.size ?: draft?.instances?.size ?: 0,
            profile = revision?.let { profileOf(it.profileId, it.profileRevision) } ?: draft?.let { profileOf(it.profileId, it.profileRevision) },
            hasDraftEdits = edited,
            health = health,
        )
    }

    private fun refreshQuarantine() = withStore { store ->
        quarantineRows.value = store.quarantine.entries().map { QuarantineRow(it.tableName, it.rowKey, it.reason, it.detectedAt) }
    }

    private fun refreshPlan(sceneId: String) {
        lastSceneId = sceneId
        checklist.value = null
        withStore { store ->
            val at = graph.isoNow()
            val revision = (store.scenes.load(sceneId, at) as? SceneLoad.Healthy)?.revision
            checklist.value = revision?.let { LoadingPlanner.plan(it, store.library.sceneContext(at)) }
        }
    }

    // ---- V2 ----

    private fun gateway(store: CargoStore) = LibraryGateway(store)

    private fun refreshLibrary() = withStore { store ->
        val entries = gateway(store).entries(graph.isoNow())
        val inbound = runCatching { graph.sync?.inbox()?.size ?: 0 }.getOrDefault(0)
        libraryState.update { it.copy(entries = entries, pendingInboundCount = inbound) }
    }

    fun newItem() = libraryState.update { it.copy(form = ItemForm(), showErrors = false, message = null) }
    fun newVersion(entry: LibraryEntry) = libraryState.update { it.copy(form = ItemFormRules.fromVersion(entry.latest), showErrors = false, message = null) }
    fun changeItemForm(form: ItemForm) = libraryState.update { it.copy(form = form) }
    fun cancelItemForm() = libraryState.update { it.copy(form = null, showErrors = false) }

    fun saveItemForm() {
        val form = libraryState.value.form ?: return
        val version = ItemFormRules.toVersion(form, graph::newId, graph.isoNow())
        if (version == null) {
            libraryState.update { it.copy(showErrors = true, message = LibraryMessage.Invalid) }
            return
        }
        withStore { store ->
            val message = gateway(store).save(version)
            libraryState.update { it.copy(form = if (message is LibraryMessage.Saved) null else it.form, showErrors = false, message = message) }
            refreshLibrary()
        }
    }

    // ---- V1 ----

    private fun refreshProfiles() = withStore { store ->
        val at = graph.isoNow()
        val context = store.library.sceneContext(at)
        val profiles = graph.index.profiles().mapNotNull { id -> context.newestProfileRevision(id)?.let { context.profile(id, it) } }
        profileState.update { it.copy(profiles = profiles, unsupportedChosen = profiles.any { p -> p.grade == ProfileGrade.UNSUPPORTED }) }
    }

    /** A value the contract or storage refuses leaves the form and what was typed in place, with a visible reason. */
    private fun saveProfile(build: (String, String) -> ProfileVersion) = withStore { store ->
        val id = graph.newId()
        val written = try {
            store.library.saveProfileVersion(build(id, graph.isoNow()))
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SQLiteException) {
            false
        }
        if (written) {
            graph.index.addProfile(id)
            profileState.update { it.copy(form = null, showErrors = false, saveFailed = false) }
            refreshProfiles()
        } else {
            profileState.update { it.copy(showErrors = true, saveFailed = true) }
        }
    }

    fun useDemoProfile() = saveProfile { id, at -> ProfileRules.demoSynthetic(id, 1, at) }
    fun chooseUnsupported() {
        if (profileState.value.unsupportedChosen) return
        saveProfile { id, at -> ProfileRules.unsupported(id, 1, at) }
    }
    fun startMeasure() = profileState.update { it.copy(form = ProfileForm(), showErrors = false, saveFailed = false) }
    fun changeProfileForm(form: ProfileForm) = profileState.update { it.copy(form = form) }
    fun cancelMeasure() = profileState.update { it.copy(form = null, showErrors = false, saveFailed = false) }

    fun saveMeasure() {
        val form = profileState.value.form ?: return
        if (ProfileRules.check(form).grade == null) {
            profileState.update { it.copy(showErrors = true) }
            return
        }
        saveProfile { id, at -> ProfileRules.measured(form, id, 1, at)!! }
    }

    /** Draft commit comes first, then the index, then the screen: a kill in between leaves nothing half visible. */
    fun startScene(profile: ProfileVersion) = withStore { store ->
        val id = graph.newId()
        store.drafts.commit(EditorRules.newDraft(id, profile, graph.isoNow()), graph.isoNow())
        graph.index.addUnsaved(id)
        withContext(kotlinx.coroutines.Dispatchers.Main) { navigate(AppRoute.Editor(id)) }
    }

    // ---- Door offers only open a screen or show a reminder. They never save anything. ----

    fun dismissSuggestion() { suggestion.value = null }
    fun openOfferedChecklist() {
        val scene = lastSceneId ?: homeState.value.scenes.firstOrNull { it.headRevision != null }?.sceneId
        suggestion.value = null
        if (scene != null) navigate(AppRoute.Checklist(scene))
    }

    private companion object { const val ROUTE_KEY = "routes" }
}
