package ai.cargo.feature.loading

import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import ai.cargo.geometry.RemovalVerdict
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle

/** Screen state stays local: a tick here is a personal reminder and never a record that cargo was loaded. */
@Composable
fun LoadingScreen(plan: LoadingPlan?, door: PortLine, offeredByDoor: Boolean, interactive: Boolean, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    val checked = remember { mutableStateListOf<String>() }
    Column(modifier.verticalScroll(rememberScrollState()).testTag("v6.screen"), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
        Panel("차량 연동", Modifier.fillMaxWidth()) {
            CargoStatus(door.kind, door.text, Modifier.testTag("v6.door"))
            if (offeredByDoor) Body("트렁크가 열려 이 목록을 제안했습니다. 실제 적재 여부는 앱이 알 수 없습니다.", Modifier.testTag("v6.doorOffer"))
            Body("이 목록은 저장된 계획입니다. 짐 고정·중량·주행 안전은 판정하지 않습니다.", Modifier.testTag("v6.notAssessed"))
        }
        if (plan == null || !plan.available) {
            CargoStatus(CargoStatusKind.UNVERIFIED, "저장된 배치가 없거나 참조 버전을 읽을 수 없어 순서를 만들 수 없습니다.", Modifier.testTag("v6.unavailable"))
            return@Column
        }
        OrderPanel("적재 순서 (저장본 r${plan.sceneRevision})", "load", plan.loadOrder, checked, interactive)
        OrderPanel("꺼냄 순서", "unload", plan.unloadOrder, null, interactive)
    }
}

@Composable
private fun OrderPanel(title: String, tag: String, rows: List<ChecklistRow>, checked: MutableList<String>?, interactive: Boolean) {
    Panel(title, Modifier.fillMaxWidth().testTag("v6.$tag")) {
        rows.forEachIndexed { index, row ->
            Column(Modifier.testTag("v6.$tag.$index"), verticalArrangement = Arrangement.spacedBy(CargoTheme.values.spacingSmall)) {
                Body("${index + 1}. ${row.name} · ${row.dimensions}${if (row.locked) " · 고정" else ""}", Modifier.testTag("v6.$tag.$index.title"))
                Body("위치: ${row.poseMm}")
                RemovalLine(row)
                // Ticks stay in memory while driving and the buttons return at a stop.
                if (checked != null && interactive) {
                    val done = row.instanceId in checked
                    CargoButton(
                        if (done) "✓ 확인함" else "확인",
                        { if (done) checked.remove(row.instanceId) else checked.add(row.instanceId) },
                        Modifier.testTag("v6.$tag.$index.check"),
                        if (done) CargoButtonKind.PRIMARY else CargoButtonKind.SECONDARY,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemovalLine(row: ChecklistRow) {
    when (row.removal) {
        RemovalVerdict.BLOCKED_IN_CHECKED_CLASS -> CargoStatus(CargoStatusKind.UNCERTAIN, "꺼낼 때 ${row.blockedBy.joinToString()} 먼저 이동 필요 가능(직선 경로 기준 경고)", Modifier.testTag("v6.removal"))
        RemovalVerdict.ERROR_DECIDES -> CargoStatus(CargoStatusKind.UNCERTAIN, "오차에 따라 ${row.blockedBy.joinToString()}이(가) 가릴 수 있음", Modifier.testTag("v6.removal"))
        RemovalVerdict.CLEAR_IN_CHECKED_CLASS -> Body("직선 꺼냄 경로에서 나중 짐에 가리지 않음(모델 기준)")
        null -> Body("꺼냄 경고를 계산하지 못했습니다")
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    BasicText(text, modifier, style = TextStyle(color = theme.text, fontSize = theme.typeBody))
}
