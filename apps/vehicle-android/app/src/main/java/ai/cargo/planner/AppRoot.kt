package ai.cargo.planner

import ai.cargo.data.StoreOpen
import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import ai.cargo.feature.editor.EditorNav
import ai.cargo.feature.editor.EditorScreen
import ai.cargo.feature.editor.EditorViewModel
import ai.cargo.feature.editor.JudgementScreen
import ai.cargo.feature.library.LibraryActions
import ai.cargo.feature.library.LibraryScreen
import ai.cargo.feature.loading.LoadingScreen
import ai.cargo.feature.loading.PortPresenter
import ai.cargo.feature.pairing.PairingActions
import ai.cargo.feature.pairing.PairingScreen
import ai.cargo.feature.pairing.PairingViewModel
import ai.cargo.feature.profile.ProfileActions
import ai.cargo.feature.profile.ProfileScreen
import ai.cargo.vehicleport.api.DoorSuggestion
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Composition root of the UI: picks the screen for the route and hands it state, actions and the keypad. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppRoot(vm: AppViewModel, onEditorShown: (EditorViewModel?) -> Unit) {
    val theme = CargoTheme.values
    val open by vm.storeOpen.collectAsState()
    Column(
        Modifier.fillMaxSize().background(theme.background).safeDrawingPadding().padding(theme.spacingMedium)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(theme.spacingMedium),
    ) {
        when (val current = open) {
            null -> Body("저장소 여는 중", Modifier.testTag("app.opening"))
            is StoreOpen.Ready -> ReadyApp(vm, onEditorShown)
            else -> {
                Header(AppRoute.Recovery.title, false) {}
                StoreRecoveryScreen(current, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(title: String, canGoBack: Boolean, back: () -> Unit) {
    val theme = CargoTheme.values
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacingMedium), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
        if (canGoBack) CargoButton("뒤로", back, Modifier.testTag("app.back"), CargoButtonKind.SECONDARY)
        BasicText(title, Modifier.testTag("app.title"), style = TextStyle(color = theme.text, fontSize = theme.typeTitle))
        // Evidence grade of this build's testing is part of what the user is told, on every screen.
        Body("시험 근거: 에뮬레이터 · 실차 미시험", Modifier.testTag("app.grade"))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReadyApp(vm: AppViewModel, onEditorShown: (EditorViewModel?) -> Unit) {
    val stack by vm.stack.collectAsState()
    val route = stack.last()
    val ux by vm.graph.vehicle.uxRestriction.collectAsState()
    val door by vm.graph.vehicle.door.collectAsState()
    val suggestion by vm.doorSuggestion.collectAsState()
    val uxLine = PortPresenter.ux(ux)
    val doorLine = PortPresenter.door(door)
    BackHandler(stack.size > 1) { vm.back() }
    Header(route.title, stack.size > 1, vm::back)
    // Unknown fails closed in the port, so this line also explains why editing is locked when no value has arrived.
    CargoStatus(uxLine.kind, uxLine.text, Modifier.testTag("app.ux"))
    Body(uxLine.code, Modifier.testTag("app.uxCode"))
    DoorOffer(suggestion, vm)
    val content = Modifier.weight(1f).fillMaxWidth()
    when (route) {
        AppRoute.Home -> {
            val home by vm.home.collectAsState()
            HomeScreen(home, doorLine, ux.editingAllowed, vm::navigate, content)
        }
        AppRoute.Profile -> {
            val state by vm.profile.collectAsState()
            ProfileScreen(
                state,
                ProfileActions(vm::useDemoProfile, vm::chooseUnsupported, vm::startMeasure, vm::changeProfileForm, vm::saveMeasure, vm::cancelMeasure, vm::startScene),
                ux.editingAllowed,
                { label, value, onValue, onNext -> NumericKeypad(label, value, onValue, onNext) },
                content,
            )
        }
        AppRoute.Library -> {
            val state by vm.library.collectAsState()
            LibraryScreen(
                state,
                LibraryActions(vm::newItem, vm::newVersion, vm::changeItemForm, vm::saveItemForm, vm::cancelItemForm) { vm.navigate(AppRoute.Pairing) },
                ux.editingAllowed,
                { ref -> vm.graph.sync?.photoFile(ref)?.let { android.graphics.BitmapFactory.decodeFile(it.path) } },
                { label, value, onValue, onNext -> NumericKeypad(label, value, onValue, onNext) },
                content,
            )
        }
        AppRoute.Pairing -> {
            val pairing = pairingViewModel(vm)
            val state by pairing.state.collectAsState()
            PairingScreen(state, PairingActions(pairing::startPairing, pairing::revoke, pairing::decide), ux.qrAllowed, content)
        }
        AppRoute.Recovery -> {
            val rows by vm.quarantine.collectAsState()
            QuarantineScreen(rows, content)
        }
        is AppRoute.Editor -> EditorRoute(vm, route.sceneId, ux.editingAllowed, uxLine.text, onEditorShown, content)
        is AppRoute.Detail -> {
            val editor = editorViewModel(vm, route.sceneId)
            val state by editor.state.collectAsState()
            JudgementScreen(state, editor, ux.editingAllowed, uxLine.text, content)
        }
        is AppRoute.Checklist -> {
            val plan by vm.plan.collectAsState()
            LoadingScreen(plan, doorLine, offeredByDoor = suggestion == null && door.position == ai.cargo.vehicleport.api.DoorPosition.Open, ux.editingAllowed, content)
        }
    }
}

@Composable
private fun EditorRoute(vm: AppViewModel, sceneId: String, allowed: Boolean, text: String, onEditorShown: (EditorViewModel?) -> Unit, modifier: Modifier) {
    val editor = editorViewModel(vm, sceneId)
    // Memory never outranks storage: returning here reads Room again, so a new library version shows up.
    LaunchedEffect(sceneId) {
        onEditorShown(editor)
        editor.reload()
    }
    val state by editor.state.collectAsState()
    EditorScreen(
        state, editor, allowed, text,
        EditorNav(
            openDetail = { vm.navigate(AppRoute.Detail(sceneId)) },
            openLibrary = { vm.navigate(AppRoute.Library) },
            openChecklist = { vm.navigate(AppRoute.Checklist(sceneId)) },
        ),
        { label, value, onValue, onNext -> NumericKeypad(label, value, onValue, onNext) },
        modifier,
    )
}

@Composable
private fun editorViewModel(vm: AppViewModel, sceneId: String): EditorViewModel {
    val graph = vm.graph
    return viewModel(
        key = "editor:$sceneId",
        factory = viewModelFactory {
            initializer { EditorViewModel(graph.store!!, sceneId, graph.editorHooks, graph.io, graph.compute, graph::isoNow, graph::newId) }
        },
    )
}

@Composable
private fun pairingViewModel(vm: AppViewModel): PairingViewModel {
    val graph = vm.graph
    return viewModel(
        key = "pairing",
        factory = viewModelFactory {
            initializer { PairingViewModel(graph.sync, graph.initialSyncStatus(), graph.coordinator, kotlinx.coroutines.Dispatchers.IO, System::currentTimeMillis) }
        },
    )
}

/** A door change offers a screen or a reminder. It never saves, and a dismissed offer has changed nothing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoorOffer(suggestion: DoorSuggestion?, vm: AppViewModel) {
    val theme = CargoTheme.values
    when (suggestion) {
        null -> Unit
        DoorSuggestion.SHOW_LOADING_CHECKLIST -> FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
            CargoStatus(CargoStatusKind.CLEAR, "트렁크가 열렸습니다. 적재 순서를 볼까요? (적재 여부는 감지하지 않습니다)", Modifier.testTag("app.offer.checklist"))
            CargoButton("적재 순서 열기", vm::openOfferedChecklist, Modifier.testTag("app.offer.open"), CargoButtonKind.SECONDARY)
            CargoButton("닫기", vm::dismissSuggestion, Modifier.testTag("app.offer.dismiss"), CargoButtonKind.SECONDARY)
        }
        DoorSuggestion.OFFER_SAVE_CONFIRMATION -> FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
            CargoStatus(CargoStatusKind.UNVERIFIED, "트렁크가 닫혔습니다. 저장할 편집이 있으면 편집 화면에서 직접 저장하세요. 자동으로 저장하지 않았습니다.", Modifier.testTag("app.offer.save"))
            CargoButton("닫기", vm::dismissSuggestion, Modifier.testTag("app.offer.dismiss"), CargoButtonKind.SECONDARY)
        }
    }
}
