package ai.cargo.feature.loading

import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.vehicleport.api.DoorAvailability
import ai.cargo.vehicleport.api.DoorPosition
import ai.cargo.vehicleport.api.DoorState
import ai.cargo.vehicleport.api.NotReadyReason
import ai.cargo.vehicleport.api.UnsupportedReason
import ai.cargo.vehicleport.api.UxRestrictionState
import ai.cargo.vehicleport.api.UxUnavailableReason

data class PortLine(val code: String, val kind: CargoStatusKind, val text: String)

/** One distinct line per port state. None of them reads as a working door unless the SDK actually reported it. */
object PortPresenter {
    fun door(state: DoorState): PortLine = when (val a = state.availability) {
        is DoorAvailability.Available -> when (val p = state.position) {
            DoorPosition.Open -> PortLine("AVAILABLE_OPEN", CargoStatusKind.CLEAR, "트렁크 상태: 열림")
            DoorPosition.Closed -> PortLine("AVAILABLE_CLOSED", CargoStatusKind.CLEAR, "트렁크 상태: 닫힘")
            is DoorPosition.Moving -> PortLine("AVAILABLE_MOVING", CargoStatusKind.UNCERTAIN, "트렁크 상태: 움직이는 중(${p.rawValue})")
            DoorPosition.Unknown -> PortLine("AVAILABLE_UNKNOWN", CargoStatusKind.UNVERIFIED, "트렁크 상태: 아직 값 없음")
        }
        is DoorAvailability.NotReady -> PortLine(
            "NOT_READY_${a.reason}", CargoStatusKind.UNCERTAIN,
            when (a.reason) {
                NotReadyReason.NOT_STARTED -> "차량 연결 시작 전"
                NotReadyReason.SERVICE_NOT_CONNECTED -> "차량 서비스 연결 대기 중(${a.attempt}회차)"
                NotReadyReason.CONNECTION_LOST -> "차량 서비스 연결 끊김, 재연결 중(${a.attempt}회차)"
            },
        )
        is DoorAvailability.Unsupported -> PortLine(
            "UNSUPPORTED_${a.reason}", CargoStatusKind.UNVERIFIED,
            when (a.reason) {
                UnsupportedReason.CAPABILITY_ABSENT -> "이 차량은 트렁크 영역을 제공하지 않습니다"
                UnsupportedReason.CAPABILITY_NULL -> "차량이 트렁크 정보를 제공하지 않았습니다(값 없음)"
                UnsupportedReason.AREA_REJECTED -> "차량이 트렁크 영역 요청을 거부했습니다"
                UnsupportedReason.UNEXPECTED_BOUNDS -> "트렁크 값 범위가 달라 열림/닫힘을 구분할 수 없습니다"
            },
        )
        DoorAvailability.PermissionMissing -> PortLine("PERMISSION_MISSING", CargoStatusKind.CONFLICT, "권한 누락: 앱에 차량 문 권한이 없어 재설치가 필요합니다")
        is DoorAvailability.Failed -> PortLine("FAILED_${a.kind}", CargoStatusKind.CONFLICT, "차량 연결 실패: ${a.kind}")
        is DoorAvailability.Releasing -> PortLine("RELEASING", CargoStatusKind.UNVERIFIED, "차량 연결 해제 중")
        DoorAvailability.Released -> PortLine("RELEASED", CargoStatusKind.UNVERIFIED, "차량 연결 해제됨")
    }

    fun ux(state: UxRestrictionState): PortLine = when (state) {
        is UxRestrictionState.Known -> if (state.requiresDistractionOptimization) {
            PortLine("UX_RESTRICTED", CargoStatusKind.UNCERTAIN, "주행 제한 중: 편집과 QR을 숨겼습니다. 초안은 보존됩니다.")
        } else {
            PortLine("UX_ALLOWED", CargoStatusKind.CLEAR, "정차 상태: 편집 가능")
        }
        is UxRestrictionState.Unavailable -> PortLine(
            "UX_UNAVAILABLE_${state.reason}", CargoStatusKind.UNVERIFIED,
            when (state.reason) {
                UxUnavailableReason.NOT_STARTED -> "주행 제한 상태 확인 전: 안전을 위해 편집을 잠급니다"
                UxUnavailableReason.PERMISSION_MISSING -> "주행 제한 권한 누락: 안전을 위해 편집을 잠급니다"
                else -> "주행 제한 상태를 알 수 없습니다(${state.reason}): 안전을 위해 편집을 잠급니다"
            },
        )
    }
}
