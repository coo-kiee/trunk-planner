package ai.cargo.feature.pairing

import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundKey
import ai.cargo.sync.AckDelivery
import ai.cargo.sync.DecisionResult
import ai.cargo.sync.InboundView
import ai.cargo.sync.PairingStart
import ai.cargo.sync.SyncCoordinator
import ai.cargo.sync.SyncEngine
import ai.cargo.sync.SyncStatus
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Shown only from this process's memory. A restart loses it on purpose and a new QR must be issued. */
data class QrOffer(val text: String, val expiresAtMs: Long)

/** The outcome of one user decision, kept so that the vehicle revision and delivery stay visible afterward. */
data class DecisionRecord(val key: InboundKey, val name: String, val outcome: String, val delivery: AckDelivery?)

data class PairingUiState(
    val status: SyncStatus = SyncStatus.NotPaired,
    val qr: QrOffer? = null,
    val nowMs: Long = 0,
    val inbox: List<InboundView> = emptyList(),
    val choiceRequired: Map<InboundKey, Pair<Long?, Long>> = emptyMap(),
    val decisions: List<DecisionRecord> = emptyList(),
    val notice: String? = null,
)

class PairingViewModel(
    private val engine: SyncEngine?,
    initialStatus: SyncStatus,
    coordinator: SyncCoordinator?,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
) : ViewModel() {
    private val mutable = MutableStateFlow(PairingUiState(status = initialStatus, nowMs = now()))
    val state: StateFlow<PairingUiState> = mutable.asStateFlow()

    init {
        coordinator?.let { sync -> viewModelScope.launch { sync.status.collect { value -> mutable.update { it.copy(status = PairingPresenter.merge(it.status, value)) } } } }
        viewModelScope.launch(io) {
            while (isActive) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    private fun refresh() {
        val sync = engine ?: return
        val inbox = sync.inbox()
        mutable.update { current ->
            current.copy(
                nowMs = now(),
                inbox = inbox,
                qr = current.qr?.takeIf { it.expiresAtMs > now() },
                decisions = current.decisions.map { it.copy(delivery = sync.deliveryOf(it.key)) },
            )
        }
    }

    fun startPairing() {
        val sync = engine ?: return
        viewModelScope.launch(io) {
            when (val start = sync.startPairing()) {
                is PairingStart.WaitingClaim -> mutable.update {
                    it.copy(status = SyncStatus.NotPaired, qr = QrOffer(start.qrUrl.reveal(), start.claimExpiresAtMs), notice = null)
                }
                PairingStart.PriorRevokePending -> mutable.update { it.copy(notice = "이전 연결 해제를 릴레이에 알리지 못해 새 QR을 만들지 않았습니다. 잠시 뒤 다시 시도합니다.") }
                is PairingStart.Refused -> mutable.update { it.copy(status = start.status, notice = PairingPresenter.status(start.status).text) }
            }
            refresh()
        }
    }

    fun revoke() {
        val sync = engine ?: return
        viewModelScope.launch(io) {
            val status = sync.revoke()
            mutable.update { it.copy(status = status, qr = null, notice = null) }
            refresh()
        }
    }

    fun decide(view: InboundView, decision: PairingChoice) {
        val sync = engine ?: return
        viewModelScope.launch(io) {
            val result = sync.decide(view.key, decision.toDecision())
            mutable.update { current -> apply(current, view, result) }
            refresh()
        }
    }

    private fun apply(state: PairingUiState, view: InboundView, result: DecisionResult): PairingUiState {
        val name = view.draft.payload.name
        fun record(outcome: String, delivery: AckDelivery?) = state.copy(
            choiceRequired = state.choiceRequired - view.key,
            decisions = (state.decisions.filterNot { it.key == view.key } + DecisionRecord(view.key, name, outcome, delivery)),
            notice = null,
        )
        return when (result) {
            is DecisionResult.Accepted -> record("차량 반영: 물품 r${result.revision}", result.delivery)
            is DecisionResult.Kept -> record("차량 기존 버전 유지(휴대폰 초안 미반영)", result.delivery)
            is DecisionResult.ChoiceRequired -> state.copy(choiceRequired = state.choiceRequired + (view.key to (result.baseRevision to result.currentRevision)))
            is DecisionResult.AlreadyDecided -> record("이미 처리됨: ${result.status}${result.itemRevision?.let { " r$it" }.orEmpty()}. 다시 반영하지 않았습니다.", sync().deliveryOf(view.key))
            is DecisionResult.PairingInactive -> state.copy(notice = "연결이 ${result.status} 상태라 수락할 수 없습니다. 초안 내용은 보존됩니다.")
            DecisionResult.Superseded -> state.copy(notice = "더 새로운 휴대폰 초안이 있어 이 초안은 처리하지 않았습니다.")
            DecisionResult.Quarantined -> state.copy(notice = "릴레이 내용이 달라져 격리했습니다. 수락할 수 없습니다.")
            DecisionResult.NotFound -> state.copy(notice = "초안을 찾지 못했습니다.")
        }
    }

    private fun sync() = engine!!

    private companion object { const val REFRESH_MS = 1_000L }
}

/** What the user can pick on the screen. The new item id for a separate item is generated here, not by the store. */
enum class PairingChoice {
    APPLY, OVERWRITE_AFTER_CONFLICT, KEEP, SEPARATE;

    fun toDecision(): InboundDecision = when (this) {
        APPLY -> InboundDecision.ApplyVersion
        OVERWRITE_AFTER_CONFLICT -> InboundDecision.OverwriteAfterConflictShown
        KEEP -> InboundDecision.KeepExisting
        SEPARATE -> InboundDecision.SeparateItem(UUID.randomUUID().toString())
    }
}
