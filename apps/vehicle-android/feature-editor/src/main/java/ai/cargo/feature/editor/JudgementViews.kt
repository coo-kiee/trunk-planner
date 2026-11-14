package ai.cargo.feature.editor

import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle

@Composable
internal fun Line(text: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    BasicText(text, modifier, style = TextStyle(color = theme.text, fontSize = theme.typeBody))
}

/** Axis rows are shared by V4 and V5 so the two screens cannot word a result differently. */
@Composable
internal fun AxisRows(board: JudgementBoard, screen: String) {
    board.rows.forEach { row ->
        Column(Modifier.testTag("$screen.axis.${row.id}")) {
            CargoStatus(row.kind, row.text, Modifier.testTag("$screen.axis.${row.id}.status"))
            Line(row.code, Modifier.testTag("$screen.axis.${row.id}.code"))
        }
    }
    if (board.staleReasons.isNotEmpty()) {
        CargoStatus(
            CargoStatusKind.STALE,
            "판정 만료: " + board.staleReasons.joinToString { JudgementPresenter.staleText(it) } + ". 재검증 전에는 들어감으로 표시하지 않습니다.",
            Modifier.testTag("$screen.stale"),
        )
    }
    if (board.restoredRecord) Line("저장 시 판정 기록입니다. 현재 값으로 다시 확인하려면 재검증하세요.", Modifier.testTag("$screen.restoredRecord"))
    if (board.fitsConfirmed) {
        CargoStatus(CargoStatusKind.CLEAR, "확정된 내부 배치: 들어감(모델 기준). 입구·경로는 위 항목을 따로 보세요.", Modifier.testTag("$screen.fits"))
    }
}

@Composable
internal fun ProvenanceRow(state: EditorUiState, screen: String) {
    JudgementPresenter.provenance(state.profile)?.let { row ->
        CargoStatus(row.kind, row.text, Modifier.testTag("$screen.provenance"))
    }
}

/** V5: every axis with its numbers, per item detail and the reasons. There is no overall result on this screen. */
@Composable
fun JudgementScreen(state: EditorUiState, vm: EditorViewModel, editingAllowed: Boolean, restrictionText: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    val board = JudgementPresenter.board(state)
    Column(modifier.verticalScroll(rememberScrollState()).testTag("v5.screen"), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
        ProvenanceRow(state, "v5")
        Panel("판정 상세", Modifier.fillMaxWidth().testTag("v5.axes")) {
            AxisRows(board, "v5")
            board.clearanceText?.let { Line(it, Modifier.testTag("v5.clearance")) }
            // Reading stays available while driving. Anything that starts work on the draft waits until editing is allowed.
            if (!editingAllowed) CargoStatus(CargoStatusKind.UNVERIFIED, restrictionText, Modifier.testTag("v5.restricted"))
            val canAct = editingAllowed && state.mode == EditorMode.Editable
            if (canAct) {
                CargoButton("재검증", vm::validateNow, Modifier.testTag("v5.validate"), CargoButtonKind.SECONDARY)
            }
            if (board.rows.any { it.id == "search" && it.code == "SEARCH_INTERRUPTED" } && canAct) {
                CargoButton("탐색 이어하기", vm::resumeSearch, Modifier.testTag("v5.searchResume"))
            }
        }
        if (board.issues.isNotEmpty()) {
            Panel("문제 목록", Modifier.fillMaxWidth().testTag("v5.issues")) {
                board.issues.forEachIndexed { index, text -> CargoStatus(CargoStatusKind.CONFLICT, text, Modifier.testTag("v5.issue.$index")) }
            }
        }
        if (board.conflictLocations.isNotEmpty()) {
            Panel("충돌 위치", Modifier.fillMaxWidth().testTag("v5.conflicts")) { board.conflictLocations.forEach { Line(it) } }
        }
        if (board.instances.isNotEmpty()) {
            Panel("물품별 상세", Modifier.fillMaxWidth().testTag("v5.details")) {
                board.instances.forEachIndexed { index, detail ->
                    Line(detail.name, Modifier.testTag("v5.detail.$index"))
                    detail.lines.forEach { Line(it) }
                }
            }
        }
    }
}
