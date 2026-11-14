package ai.cargo.planner

import ai.cargo.data.StoreOpen
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** V7 when storage itself cannot open. No store exists here, so editing and saving are absent rather than disabled. */
@Composable
internal fun StoreRecoveryScreen(open: StoreOpen, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    Column(modifier.verticalScroll(rememberScrollState()).testTag("v7.screen"), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
        Panel("데이터 복구", Modifier.fillMaxWidth()) {
            when (open) {
                is StoreOpen.FutureSchema -> {
                    CargoStatus(CargoStatusKind.STALE, "새 버전 앱이 저장한 데이터입니다(형식 ${open.view.version}). 읽기만 가능하며 쓰지 않습니다.", Modifier.testTag("v7.future"))
                    Body("앱을 최신 버전으로 업데이트하면 다시 편집할 수 있습니다. 편집과 저장은 비활성화되어 있습니다.", Modifier.testTag("v7.writeBlocked"))
                }
                is StoreOpen.MigrationFailed -> {
                    val failure = open.failure
                    CargoStatus(CargoStatusKind.CONFLICT, "데이터 형식 변환에 실패했습니다. 실패한 결과를 덮어쓰지 않았습니다.", Modifier.testTag("v7.migrationFailed"))
                    Body("원본 변경 없음: ${if (failure.originalUnchanged) "확인됨" else "확인되지 않음"}", Modifier.testTag("v7.originalUnchanged"))
                    Body("사전 사본 일치: ${if (failure.backupMatches) "확인됨" else "확인되지 않음"}", Modifier.testTag("v7.backupMatches"))
                    Body("사전 사본 위치: ${failure.backupDir}", Modifier.testTag("v7.backupDir"))
                    Body("앱을 다시 시작하면 변환을 다시 시도합니다. 해결되지 않으면 업데이트 후 다시 여세요. 지금은 편집과 저장이 비활성화되어 있습니다.", Modifier.testTag("v7.resume"))
                }
                is StoreOpen.OpenFailed -> {
                    CargoStatus(CargoStatusKind.CONFLICT, "저장소를 열 수 없습니다(${open.cause}). 파일은 건드리지 않았습니다.", Modifier.testTag("v7.openFailed"))
                    Body("위치: ${open.path}. 편집과 저장은 비활성화되어 있습니다.", Modifier.testTag("v7.path"))
                }
                is StoreOpen.Ready -> Unit
            }
        }
    }
}

/** V7 for damage found inside a healthy store. Rows are copies; the originals stay in place. */
@Composable
internal fun QuarantineScreen(rows: List<QuarantineRow>, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    Column(modifier.verticalScroll(rememberScrollState()).testTag("v7.screen"), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
        Panel("격리된 기록", Modifier.fillMaxWidth().testTag("v7.quarantine")) {
            Body("검증에 실패한 기록의 사본입니다. 원본을 지우지 않았습니다. 해당 공간은 공간 목록에서 읽기 전용으로 열 수 있습니다.")
            if (rows.isEmpty()) Body("격리된 기록 없음", Modifier.testTag("v7.none"))
            rows.forEachIndexed { index, row ->
                CargoStatus(CargoStatusKind.CONFLICT, "${row.table} · ${row.key} · ${row.reason} · ${row.detectedAt}", Modifier.testTag("v7.row.$index"))
            }
        }
    }
}
