package ai.cargo.feature.pairing

import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.sync.AckDelivery
import ai.cargo.sync.EndpointProblem
import ai.cargo.sync.SyncStatus

data class StatusLine(val code: String, val kind: CargoStatusKind, val text: String)

/** Each sync state reads differently. "Not delivered" is its own state and is never shown as success. */
object PairingPresenter {
    fun status(s: SyncStatus): StatusLine = when (s) {
        is SyncStatus.Disabled -> StatusLine("DISABLED_${s.problem}", CargoStatusKind.UNVERIFIED, disabled(s.problem))
        SyncStatus.NotPaired -> StatusLine("NOT_PAIRED", CargoStatusKind.UNVERIFIED, "연결된 휴대폰 없음. 차량 단독 기능은 그대로 쓸 수 있습니다.")
        SyncStatus.CredentialLost -> StatusLine("CREDENTIAL_LOST", CargoStatusKind.UNCERTAIN, "연결 정보를 잃었습니다. 저장 데이터는 그대로이며 새 연결이 필요합니다.")
        is SyncStatus.Paired -> StatusLine("PAIRED", CargoStatusKind.CLEAR, "휴대폰 연결 유효")
        is SyncStatus.NetworkUnavailable -> StatusLine("NETWORK_UNAVAILABLE", CargoStatusKind.UNCERTAIN, "릴레이 연결 안 됨. 차량 단독 기능은 계속 쓸 수 있고 휴대폰 초안은 대기합니다.")
        is SyncStatus.Throttled -> StatusLine("THROTTLED", CargoStatusKind.UNCERTAIN, "릴레이가 잠시 응답을 제한했습니다. 자동으로 다시 시도합니다.")
        is SyncStatus.RevokePending -> StatusLine("REVOKE_PENDING", CargoStatusKind.UNCERTAIN, "차량에서 연결을 해제했고 릴레이 통보를 재시도 중입니다.")
        is SyncStatus.Revoked -> StatusLine("REVOKED", CargoStatusKind.UNVERIFIED, "연결이 해제되었습니다. 이후 전송은 거부됩니다.")
        is SyncStatus.Expired -> StatusLine("EXPIRED", CargoStatusKind.STALE, "연결이 만료되었습니다. 새 QR이 필요합니다.")
        is SyncStatus.ProtocolError -> StatusLine("PROTOCOL_ERROR", CargoStatusKind.CONFLICT, "릴레이 응답을 믿을 수 없어 처리를 멈췄습니다(${s.detail}).")
    }

    private fun disabled(problem: EndpointProblem) = when (problem) {
        EndpointProblem.VEHICLE_URL_UNSET, EndpointProblem.PHONE_URL_UNSET -> "공인 HTTPS 서버 미설정: 휴대폰 연결이 비활성화되어 있습니다"
        EndpointProblem.CLEARTEXT_REFUSED -> "암호화되지 않은 주소라 휴대폰 연결을 거부했습니다"
        EndpointProblem.VEHICLE_URL_INVALID, EndpointProblem.PHONE_URL_INVALID -> "서버 주소가 잘못되어 휴대폰 연결이 비활성화되어 있습니다"
    }

    /**
     * An ended link stays visible as what ended it. After a relay cleans up an expired credential it answers like any
     * unknown token, and after a revoke the credential is gone, so later polls alone would blur both into something else.
     */
    fun merge(previous: SyncStatus, next: SyncStatus): SyncStatus = when {
        previous is SyncStatus.Expired && next is SyncStatus.Revoked && previous.pairingId == next.pairingId -> previous
        previous is SyncStatus.Revoked && next is SyncStatus.NotPaired -> previous
        previous is SyncStatus.Expired && next is SyncStatus.NotPaired -> previous
        else -> next
    }

    fun linkEnded(status: SyncStatus) = status is SyncStatus.Expired || status is SyncStatus.Revoked

    /** Null means nothing has been reported as finished. That is not delivered, so it reads as waiting. */
    fun delivery(d: AckDelivery?): String = when (d) {
        null -> "휴대폰 전달: 아직 확인되지 않음"
        AckDelivery.DELIVERED -> "휴대폰 전달: 릴레이가 수신함"
        AckDelivery.UNDELIVERED_REVOKED -> "휴대폰 전달 안 됨: 연결 해제"
        AckDelivery.UNDELIVERED_EXPIRED -> "휴대폰 전달 안 됨: 만료"
        AckDelivery.REJECTED -> "휴대폰 전달 안 됨: 릴레이 거부"
    }
}
