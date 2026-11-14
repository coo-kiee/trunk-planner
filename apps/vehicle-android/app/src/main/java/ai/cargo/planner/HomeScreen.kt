package ai.cargo.planner

import ai.cargo.contracts.ProfileGrade
import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import ai.cargo.feature.loading.PortLine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle

@Composable
internal fun Body(text: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    BasicText(text, modifier, style = TextStyle(color = theme.text, fontSize = theme.typeBody))
}

/** V0: what is saved, how it was measured, and the reminder that this is a plan rather than a sensor reading. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeScreen(state: HomeUiState, door: PortLine, editingAllowed: Boolean, navigate: (AppRoute) -> Unit, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    Column(modifier.verticalScroll(rememberScrollState()).testTag("v0.screen"), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
        Panel("시작", Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
                CargoButton("새 배치(차량 공간 선택)", { navigate(AppRoute.Profile) }, Modifier.testTag("v0.profile"), enabled = editingAllowed)
                CargoButton("물품 보관함", { navigate(AppRoute.Library) }, Modifier.testTag("v0.library"), CargoButtonKind.SECONDARY)
                CargoButton("휴대폰 연결", { navigate(AppRoute.Pairing) }, Modifier.testTag("v0.pairing"), CargoButtonKind.SECONDARY)
            }
            CargoStatus(door.kind, door.text, Modifier.testTag("v0.door"))
            Body("상태 코드: ${door.code}", Modifier.testTag("v0.doorCode"))
        }
        if (state.quarantineCount > 0) {
            Panel("데이터 손상 격리 ${state.quarantineCount}건", Modifier.fillMaxWidth()) {
                CargoStatus(CargoStatusKind.CONFLICT, "손상된 기록을 격리했습니다. 원본은 보존되어 있습니다.", Modifier.testTag("v0.quarantine"))
                CargoButton("복구 화면 열기", { navigate(AppRoute.Recovery) }, Modifier.testTag("v0.recovery"), CargoButtonKind.SECONDARY)
            }
        }
        Panel("내 공간", Modifier.fillMaxWidth().testTag("v0.scenes")) {
            Body("아래는 저장한 계획입니다. 차량 안 짐을 실제로 감지한 결과가 아닙니다.", Modifier.testTag("v0.planNotice"))
            if (state.loaded && state.scenes.isEmpty()) Body("저장된 공간이 없습니다.", Modifier.testTag("v0.empty"))
            state.scenes.forEachIndexed { index, scene -> SceneRow(index, scene, navigate) }
        }
    }
}

@Composable
private fun SceneRow(index: Int, scene: SceneSummary, navigate: (AppRoute) -> Unit) {
    Column(Modifier.testTag("v0.scene.$index"), verticalArrangement = Arrangement.spacedBy(CargoTheme.values.spacingSmall)) {
        val saved = scene.headRevision?.let { "저장본 r$it · ${scene.savedAt}" } ?: "아직 저장 안 됨(초안만 있음)"
        Body("$saved · 짐 ${scene.instanceCount}개", Modifier.testTag("v0.scene.$index.title"))
        scene.profile?.let { profile ->
            val synthetic = profile.grade == ProfileGrade.DEMO_SYNTHETIC
            val text = if (synthetic) "합성 시연용·실제 차량 치수 아님" else "공간 출처: ${profile.provenance}"
            CargoStatus(if (synthetic) CargoStatusKind.UNVERIFIED else CargoStatusKind.CLEAR, text, Modifier.testTag("v0.scene.$index.provenance"))
        }
        when (val health = scene.health) {
            is SceneHealth.RecoveredReadOnly -> CargoStatus(CargoStatusKind.CONFLICT, "저장본 r${health.damaged} 손상: 직전 r${health.shown} 읽기 전용", Modifier.testTag("v0.scene.$index.health"))
            is SceneHealth.NoValid -> CargoStatus(CargoStatusKind.CONFLICT, "저장본 r${health.damaged} 손상: 읽을 수 있는 저장본 없음", Modifier.testTag("v0.scene.$index.health"))
            SceneHealth.Legacy -> CargoStatus(CargoStatusKind.STALE, "이전 형식 저장본: 읽기 전용", Modifier.testTag("v0.scene.$index.health"))
            else -> Unit
        }
        if (scene.hasDraftEdits) Body("저장하지 않은 편집 초안 있음", Modifier.testTag("v0.scene.$index.draft"))
        CargoButton(if (scene.hasDraftEdits) "초안 이어하기" else "열기", { navigate(AppRoute.Editor(scene.sceneId)) }, Modifier.testTag("v0.scene.$index.open"))
    }
}
