package ai.cargo.feature.editor

import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import ai.cargo.contracts.Camera
import ai.cargo.contracts.Vec3Mm
import ai.cargo.render.CargoSceneView
import ai.cargo.render.RenderCamera
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

typealias Keypad = @Composable (label: String, value: String, onValue: (String) -> Unit, onNext: () -> Unit) -> Unit

class EditorNav(val openDetail: () -> Unit, val openLibrary: () -> Unit, val openChecklist: () -> Unit)

private val WIDE_MIN = 760.dp
private val NARROW_VIEW_HEIGHT = 300.dp

/** V4. While editing is not allowed there is no editing node in the tree and no 3D surface, not just disabled ones. */
@Composable
fun EditorScreen(state: EditorUiState, vm: EditorViewModel, editingAllowed: Boolean, restrictionText: String, nav: EditorNav, keypad: Keypad, modifier: Modifier = Modifier) {
    when (val mode = state.mode) {
        EditorMode.Loading -> Line("불러오는 중", modifier.testTag("v4.loading"))
        is EditorMode.Unavailable -> UnavailablePanel(mode, modifier)
        else -> if (!editingAllowed) RestrictedPanel(state, restrictionText, modifier) else Layout(state, vm, nav, keypad, modifier)
    }
}

@Composable
private fun UnavailablePanel(mode: EditorMode.Unavailable, modifier: Modifier) {
    Panel("이 공간을 열 수 없습니다", modifier.testTag("v4.unavailable")) {
        CargoStatus(CargoStatusKind.CONFLICT, "복구 필요: ${mode.code}${mode.damagedRevision?.let { " (손상 r$it)" }.orEmpty()}", Modifier.testTag("v4.unavailable.code"))
        Line("원본은 그대로 보존되어 있습니다. 편집과 저장은 비활성화됩니다.")
    }
}

/** Nothing about the draft changes here. Only a summary remains so the driver knows the work is safe. */
@Composable
private fun RestrictedPanel(state: EditorUiState, text: String, modifier: Modifier) {
    Panel("주행 제한", modifier.testTag("v4.restricted")) {
        CargoStatus(CargoStatusKind.UNVERIFIED, text, Modifier.testTag("v4.restricted.status"))
        Line("편집 내용은 초안으로 저장되어 있으며 정차하면 같은 상태로 돌아옵니다.")
        Line("초안의 짐 ${state.draft?.instances?.size ?: 0}개", Modifier.testTag("v4.restricted.count"))
    }
}

@Composable
private fun Layout(state: EditorUiState, vm: EditorViewModel, nav: EditorNav, keypad: Keypad, modifier: Modifier) {
    val theme = CargoTheme.values
    var drawnDistance by remember { mutableStateOf<Long?>(null) }
    val onDrawn: (RenderCamera) -> Unit = { drawnDistance = it.distanceMm.toLong() }
    BoxWithConstraints(modifier.fillMaxSize().testTag("v4.screen")) {
        if (maxWidth >= WIDE_MIN) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
                SceneArea(state, vm, onDrawn, Modifier.weight(1.1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
                    Panels(state, vm, nav, keypad, drawnDistance)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
                SceneArea(state, vm, onDrawn, Modifier.fillMaxWidth().height(NARROW_VIEW_HEIGHT))
                Panels(state, vm, nav, keypad, drawnDistance)
            }
        }
    }
}

@Composable
private fun SceneArea(state: EditorUiState, vm: EditorViewModel, onDrawn: (RenderCamera) -> Unit, modifier: Modifier) {
    val camera = SceneRenderMapper.camera(state) ?: return
    // An interop view has no semantics node of its own, so the tag sits on a box around it.
    Box(modifier.testTag("v4.gl")) {
        CargoSceneView(
            scene = SceneRenderMapper.scene(state),
            camera = camera,
            renderAllowed = true,
            onPick = vm::select,
            onCameraCommit = { vm.setCamera(it.toContract()) },
            modifier = Modifier.fillMaxSize(),
            onCameraDrawn = onDrawn,
        )
    }
}

private fun RenderCamera.toContract() = Camera(
    Vec3Mm(targetX.toLong(), targetY.toLong(), targetZ.toLong()),
    yawDegrees.toLong(), pitchDegrees.toLong(), distanceMm.toLong(),
)

@Composable
private fun Panels(state: EditorUiState, vm: EditorViewModel, nav: EditorNav, keypad: Keypad, drawnDistance: Long?) {
    val readOnly = state.mode as? EditorMode.RecoveredReadOnly
    ProvenanceRow(state, "v4")
    Notices(state)
    if (readOnly != null) {
        RecoveryPanel(readOnly, vm)
    } else {
        InstancesPanel(state, vm)
        SelectedPanel(state, vm, keypad)
        AddItemPanel(state, vm, nav.openLibrary)
    }
    JudgementPanel(state, vm, nav, readOnly == null)
    if (readOnly == null) {
        HistoryPanel(state, vm)
        CameraPanel(state, vm, drawnDistance)
    }
}

@Composable
private fun RecoveryPanel(mode: EditorMode.RecoveredReadOnly, vm: EditorViewModel) {
    Panel("읽기 전용 복구", Modifier.fillMaxWidth().testTag("v4.readOnly")) {
        CargoStatus(CargoStatusKind.CONFLICT, "저장본 r${mode.damagedRevision} 손상. 원본은 보존하고 직전 정상 r${mode.shownRevision}을 읽기 전용으로 표시합니다.", Modifier.testTag("v4.readOnly.status"))
        CargoButton("r${mode.shownRevision} 내용으로 새 저장본 만들기", vm::adoptRecovery, Modifier.testTag("v4.adopt"), CargoButtonKind.SECONDARY)
    }
}

@Composable
private fun Notices(state: EditorUiState) {
    state.rejection?.let { CargoStatus(CargoStatusKind.UNCERTAIN, it, Modifier.testTag("v4.rejection")) }
    if (state.restoredDraft) Line("이전 편집 초안을 복원했습니다.", Modifier.testTag("v4.restoredDraft"))
    if (state.draftDamaged) CargoStatus(CargoStatusKind.UNCERTAIN, "초안이 손상되어 격리하고 마지막 저장본에서 시작했습니다.", Modifier.testTag("v4.draftDamaged"))
    // Read from the draft: any earlier read may have converted the marker, and this stays true until a new search.
    if (state.draft?.lastSearch?.interruptCause == ai.cargo.scene.SearchInterruptCause.PROCESS_DEATH) CargoStatus(CargoStatusKind.UNCERTAIN, "앱 종료로 후보 탐색이 중단되었습니다. 불가 판정이 아닙니다.", Modifier.testTag("v4.interrupted"))
    state.notAvailable?.let { CargoStatus(CargoStatusKind.UNVERIFIED, "판정 불가: ${it.code}", Modifier.testTag("v4.notAvailable")) }
}

@Composable
private fun JudgementPanel(state: EditorUiState, vm: EditorViewModel, nav: EditorNav, editable: Boolean) {
    val board = JudgementPresenter.board(state)
    val draft = state.draft
    val validation = draft?.validation
    val current = validation != null && !validation.restoredFromRevision && board.staleReasons.isEmpty()
    Panel("판정(항목별로 따로 봅니다)", Modifier.fillMaxWidth().testTag("v4.judgement")) {
        AxisRows(board, "v4")
        board.clearanceText?.let { Line(it, Modifier.testTag("v4.clearance")) }
        board.issues.forEachIndexed { index, text -> CargoStatus(CargoStatusKind.CONFLICT, text, Modifier.testTag("v4.issue.$index")) }
        ButtonRow {
            CargoButton("판정 상세", nav.openDetail, Modifier.testTag("v4.detail"), CargoButtonKind.SECONDARY)
            if (editable) CargoButton(if (state.validating) "검증 중" else "재검증", vm::validateNow, Modifier.testTag("v4.validate"), CargoButtonKind.SECONDARY, enabled = !state.validating)
            CargoButton("적재 순서", nav.openChecklist, Modifier.testTag("v4.checklist"), CargoButtonKind.SECONDARY, enabled = draft?.baseRevision != null)
        }
        if (editable) SaveRow(state, vm, current, board.issues.isNotEmpty())
    }
}

/** Confirming a position and saving stay two separate presses. */
@Composable
private fun SaveRow(state: EditorUiState, vm: EditorViewModel, current: Boolean, hasIssues: Boolean) {
    val draft = state.draft ?: return
    val confirmed = draft.confirmation != null
    ButtonRow {
        CargoButton(
            when {
                confirmed -> "✓ 위치 확정됨"
                hasIssues -> "표시된 문제를 확인하고 위치 확정"
                else -> "위치 확정"
            },
            { vm.confirm(acknowledge = hasIssues) },
            Modifier.testTag("v4.confirm"),
            enabled = current && !confirmed,
        )
        CargoButton(
            if (state.save == SaveState.Saving) "저장 중" else "저장",
            vm::save,
            Modifier.testTag("v4.save"),
            enabled = confirmed && state.save != SaveState.Saving,
        )
    }
    when (val save = state.save) {
        is SaveState.Saved -> CargoStatus(CargoStatusKind.CLEAR, "저장 완료: 저장본 r${save.revision}", Modifier.testTag("v4.saved"))
        is SaveState.Refused -> CargoStatus(CargoStatusKind.CONFLICT, save.message, Modifier.testTag("v4.saveRefused"))
        SaveState.Saving -> Line("저장 중입니다. 완료 전에는 저장된 것으로 표시하지 않습니다.", Modifier.testTag("v4.saving"))
        SaveState.Idle -> Unit
    }
    if (state.context?.let { c -> c.newestProfileRevision(draft.profileId)?.takeIf { it > draft.profileRevision } } != null) {
        val newest = state.context.newestProfileRevision(draft.profileId)!!
        CargoStatus(CargoStatusKind.UNCERTAIN, "공간 새 버전 r$newest 있음. 이 배치는 r${draft.profileRevision} 유지 중입니다.", Modifier.testTag("v4.newerProfile"))
        CargoButton("공간 새 버전 적용", { vm.applyProfileVersion(newest) }, Modifier.testTag("v4.applyProfile"), CargoButtonKind.SECONDARY)
    }
}

@Composable
private fun HistoryPanel(state: EditorUiState, vm: EditorViewModel) {
    val draft = state.draft ?: return
    Panel("되돌리기", Modifier.fillMaxWidth().testTag("v4.history")) {
        Line("되돌리기 ${draft.undo.size}단계 · 다시 실행 ${draft.redo.size}단계", Modifier.testTag("v4.history.counts"))
        ButtonRow {
            CargoButton("되돌리기", vm::undo, Modifier.testTag("v4.undo"), CargoButtonKind.SECONDARY, enabled = draft.undo.isNotEmpty())
            CargoButton("다시 실행", vm::redo, Modifier.testTag("v4.redo"), CargoButtonKind.SECONDARY, enabled = draft.redo.isNotEmpty())
        }
    }
}
