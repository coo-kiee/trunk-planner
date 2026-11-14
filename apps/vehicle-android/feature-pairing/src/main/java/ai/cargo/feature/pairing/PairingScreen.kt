package ai.cargo.feature.pairing

import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import ai.cargo.sync.InboundView
import ai.cargo.sync.SyncStatus
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

class PairingActions(val start: () -> Unit, val revoke: () -> Unit, val decide: (InboundView, PairingChoice) -> Unit)

@Composable
fun PairingScreen(state: PairingUiState, actions: PairingActions, qrAllowed: Boolean, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    val line = PairingPresenter.status(state.status)
    val enabled = state.status !is SyncStatus.Disabled
    Column(modifier.verticalScroll(rememberScrollState()).testTag("v3.screen"), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
        Panel("휴대폰 연결", Modifier.fillMaxWidth()) {
            CargoStatus(line.kind, line.text, Modifier.testTag("v3.status"))
            Body("상태 코드: ${line.code}", Modifier.testTag("v3.statusCode"))
            state.notice?.let { CargoStatus(CargoStatusKind.UNCERTAIN, it, Modifier.testTag("v3.notice")) }
            when {
                !enabled -> Body("이 앱 설치본은 휴대폰 연결 없이 차량 단독으로 동작합니다.", Modifier.testTag("v3.disabledNote"))
                !qrAllowed -> CargoStatus(CargoStatusKind.UNVERIFIED, "주행 제한 중: QR을 숨겼습니다.", Modifier.testTag("v3.qrHidden"))
                else -> QrBlock(state, actions)
            }
        }
        InboxPanel(state, actions, qrAllowed)
    }
}

@Composable
private fun QrBlock(state: PairingUiState, actions: PairingActions) {
    val qr = state.qr
    if (qr != null) {
        val bitmap = remember(qr.text) { QrEncoder.encode(qr.text, QR_PX) }
        Image(bitmap.asImageBitmap(), "휴대폰 연결 QR", Modifier.size(QR_PX.dp).testTag("v3.qr"))
        val left = ((qr.expiresAtMs - state.nowMs) / 1000).coerceAtLeast(0)
        Body("QR 만료까지 ${left}초. 재시작하면 다시 표시되지 않으며 새로 만들어야 합니다.", Modifier.testTag("v3.qrExpiry"))
    } else {
        Body("QR을 만들면 휴대폰으로 스캔해 연결합니다. QR에는 연결 주소만 들어 있고 치수·사진은 들어 있지 않습니다.")
    }
    CargoButton(if (qr == null) "QR 만들기" else "새 QR 만들기", actions.start, Modifier.testTag("v3.start"))
    if (state.status is SyncStatus.Paired || state.status is SyncStatus.NetworkUnavailable) {
        CargoButton("연결 해제", actions.revoke, Modifier.testTag("v3.revoke"), CargoButtonKind.SECONDARY)
    }
}

@Composable
private fun InboxPanel(state: PairingUiState, actions: PairingActions, allowed: Boolean) {
    Panel("수신 초안", Modifier.fillMaxWidth().testTag("v3.inbox")) {
        Body("휴대폰이 보낸 초안은 여기서 고르기 전에는 보관함에 들어가지 않습니다.")
        if (state.inbox.isEmpty()) Body("대기 중인 초안 없음", Modifier.testTag("v3.inboxEmpty"))
        state.inbox.forEachIndexed { index, view ->
            InboundRow(index, view, state.choiceRequired[view.key], actions, allowed, PairingPresenter.linkEnded(state.status))
        }
        state.decisions.forEachIndexed { index, record ->
            Column(Modifier.testTag("v3.decision.$index")) {
                Body("${record.name}: ${record.outcome}", Modifier.testTag("v3.decision.$index.outcome"))
                Body(PairingPresenter.delivery(record.delivery), Modifier.testTag("v3.decision.$index.delivery"))
            }
        }
    }
}

@Composable
private fun InboundRow(index: Int, view: InboundView, choice: Pair<Long?, Long>?, actions: PairingActions, allowed: Boolean, linkEnded: Boolean) {
    val draft = view.draft
    val item = draft.payload
    val d = item.measuredDimensionsMm
    Column(Modifier.testTag("v3.draft.$index"), verticalArrangement = Arrangement.spacedBy(CargoTheme.values.spacingSmall)) {
        Body("${item.name} · ${d.x}×${d.y}×${d.z} mm ±${item.errorMm} · 초안 ${draft.clientRevision}회차", Modifier.testTag("v3.draft.$index.title"))
        Body("휴대폰 기준: ${draft.baseItemRevision?.let { "물품 r$it" } ?: "새 물품"} · 아직 차량에 반영되지 않음", Modifier.testTag("v3.draft.$index.pending"))
        view.conflict?.let { Body("차량 현재 버전: r${it.authoritativeRevision}", Modifier.testTag("v3.draft.$index.vehicleRevision")) }
        when {
            view.quarantined -> CargoStatus(CargoStatusKind.CONFLICT, "릴레이 내용이 달라져 격리됨: 수락할 수 없습니다", Modifier.testTag("v3.draft.$index.quarantined"))
            !view.pairingActive || linkEnded -> CargoStatus(CargoStatusKind.STALE, "연결 만료·해제됨: 수락할 수 없으며 내용은 보존합니다", Modifier.testTag("v3.draft.$index.inactive"))
            !allowed -> CargoStatus(CargoStatusKind.UNVERIFIED, "주행 제한 중: 정차 후 선택하세요", Modifier.testTag("v3.draft.$index.restricted"))
            choice != null -> ChoiceButtons(index, view, choice, actions)
            else -> {
                CargoButton("차량에 반영", { actions.decide(view, PairingChoice.APPLY) }, Modifier.testTag("v3.draft.$index.apply"))
                CargoButton("반영하지 않음", { actions.decide(view, PairingChoice.KEEP) }, Modifier.testTag("v3.draft.$index.keep"), CargoButtonKind.SECONDARY)
            }
        }
    }
}

@Composable
private fun ChoiceButtons(index: Int, view: InboundView, choice: Pair<Long?, Long>, actions: PairingActions) {
    CargoStatus(
        CargoStatusKind.UNCERTAIN,
        "양쪽 수정 충돌: 휴대폰은 ${choice.first?.let { "r$it" } ?: "새 물품"} 기준, 차량은 r${choice.second}입니다. 직접 선택하세요.",
        Modifier.testTag("v3.draft.$index.conflict"),
    )
    CargoButton("새 버전으로 적용", { actions.decide(view, PairingChoice.OVERWRITE_AFTER_CONFLICT) }, Modifier.testTag("v3.draft.$index.overwrite"))
    CargoButton("차량 기존 유지", { actions.decide(view, PairingChoice.KEEP) }, Modifier.testTag("v3.draft.$index.keepExisting"), CargoButtonKind.SECONDARY)
    CargoButton("별도 물품으로 저장", { actions.decide(view, PairingChoice.SEPARATE) }, Modifier.testTag("v3.draft.$index.separate"), CargoButtonKind.SECONDARY)
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    BasicText(text, modifier, style = TextStyle(color = theme.text, fontSize = theme.typeBody))
}

private const val QR_PX = 300
