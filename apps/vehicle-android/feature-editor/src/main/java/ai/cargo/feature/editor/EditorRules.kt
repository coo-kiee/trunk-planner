package ai.cargo.feature.editor

import ai.cargo.contracts.Camera
import ai.cargo.contracts.Instance
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.Vec3Mm
import ai.cargo.geometry.CoordinateView
import ai.cargo.geometry.ItemGeometry
import ai.cargo.geometry.VehicleSide
import ai.cargo.scene.RejectReason
import ai.cargo.scene.SaveRejection
import ai.cargo.scene.SceneCommand
import ai.cargo.scene.SceneContext
import ai.cargo.scene.SceneDraftState
import ai.cargo.scene.itemRef

enum class MoveDirection(val axis: PoseAxis, val sign: Int, val label: String) {
    VEHICLE_LEFT(PoseAxis.X, 1, "차량 왼쪽"),
    VEHICLE_RIGHT(PoseAxis.X, -1, "차량 오른쪽"),
    UP(PoseAxis.Y, 1, "위"),
    DOWN(PoseAxis.Y, -1, "아래"),
    INWARD(PoseAxis.Z, 1, "차량 앞쪽"),
    OUTWARD(PoseAxis.Z, -1, "입구 쪽"),
}

/** Turns what the user pressed into scene commands and wording. No effects and no clock. */
object EditorRules {
    fun nudge(instance: Instance, direction: MoveDirection, stepMm: Long): SceneCommand.Move {
        val d = stepMm * direction.sign
        val p = instance.poseMm
        return SceneCommand.Move(
            instance.instanceId,
            when (direction.axis) {
                PoseAxis.X -> p.copy(x = p.x + d)
                PoseAxis.Y -> p.copy(y = p.y + d)
                PoseAxis.Z -> p.copy(z = p.z + d)
            },
        )
    }

    /** Wording of a move comes from the same sign rule as the frame, so it cannot disagree with the coordinates. */
    fun moveLabel(direction: MoveDirection, stepMm: Long): String = when (direction.axis) {
        PoseAxis.X -> when (CoordinateView.sideOfMove(stepMm * direction.sign)) {
            VehicleSide.LEFT -> "차량 왼쪽으로 ${stepMm}mm 이동"
            VehicleSide.RIGHT -> "차량 오른쪽으로 ${stepMm}mm 이동"
            VehicleSide.CENTER -> "이동 없음"
        }
        PoseAxis.Y -> "${if (direction.sign > 0) "위" else "아래"}로 ${stepMm}mm 이동"
        PoseAxis.Z -> "${if (direction.sign > 0) "차량 앞쪽" else "입구 쪽"}으로 ${stepMm}mm 이동"
    }

    /** Which screen side is vehicle left depends on the view; the vehicle words never change. */
    fun viewHint(yawDegrees: Long): String {
        val normal = ((yawDegrees % 360) + 360) % 360
        return when {
            normal < 90 || normal > 270 -> "입구에서 안쪽을 보는 시점: 차량 왼쪽이 화면 왼쪽입니다"
            else -> "차량 안쪽에서 입구를 보는 시점: 차량 왼쪽이 화면 오른쪽입니다"
        }
    }

    /** Next permitted orientation, so rotation can never leave what the item's handling rule allows. */
    fun nextOrientation(instance: Instance, context: SceneContext): Long? {
        val item = context.item(instance.itemRef) ?: return null
        val allowed = runCatching { ItemGeometry.of(item, context.config).allowedOrientations() }.getOrNull() ?: return null
        if (allowed.isEmpty()) return null
        val current = allowed.indexOf(instance.orientationIndex.toInt())
        return allowed[(current + 1) % allowed.size].toLong()
    }

    fun firstOrientation(itemId: String, revision: Long, context: SceneContext): Long {
        val item = context.item(ai.cargo.scene.ItemRef(itemId, revision)) ?: return 0
        return runCatching { ItemGeometry.of(item, context.config).allowedOrientations().first().toLong() }.getOrDefault(0)
    }

    fun fitCamera(profile: ProfileVersion): Camera {
        val b = profile.bounds
        val target = if (b == null) Vec3Mm(0, 0, 0) else Vec3Mm(b.x / 2, b.y / 3, b.z / 2)
        val span = if (b == null) 2000 else maxOf(b.x, b.y, b.z)
        return Camera(target, yawDegrees = 0, pitchDegrees = 25, distanceMm = span * 5 / 2)
    }

    /** The stored default view. The renderer fits it to each surface; any other stored camera is the user's own. */
    fun isFitCamera(camera: Camera, profile: ProfileVersion?): Boolean = profile != null && camera == fitCamera(profile)

    /** What the view buttons start from, so "멀리" moves away from what is on screen rather than from the stored default. */
    fun cameraOnScreen(camera: Camera, profile: ProfileVersion?, drawnDistanceMm: Long?): Camera =
        if (drawnDistanceMm != null && isFitCamera(camera, profile)) camera.copy(distanceMm = maxOf(camera.distanceMm, drawnDistanceMm)) else camera

    fun newDraft(sceneId: String, profile: ProfileVersion, at: String): SceneDraftState =
        SceneDraftState.newScene(sceneId, profile.id, profile.revision, at).copy(camera = fitCamera(profile))

    fun reject(reason: RejectReason): String = when (reason) {
        RejectReason.LOCKED_REQUIRES_UNLOCK -> "고정된 짐입니다. 먼저 고정을 해제해야 옮길 수 있습니다."
        RejectReason.POSE_OUT_OF_RANGE -> "좌표 범위를 벗어납니다."
        RejectReason.ORIENTATION_OUT_OF_RANGE -> "허용되지 않은 자세입니다."
        RejectReason.INSTANCE_LIMIT -> "물품 수 한도를 넘었습니다."
        RejectReason.REFERENCED_VERSION_MISSING -> "참조하는 물품·공간 버전을 읽을 수 없습니다."
        RejectReason.NOTHING_TO_UNDO -> "되돌릴 작업이 없습니다."
        RejectReason.NOTHING_TO_REDO -> "다시 실행할 작업이 없습니다."
        RejectReason.NOT_VALIDATED -> "현재 배치를 먼저 재검증해야 합니다."
        RejectReason.STALE_VALIDATION_RESULT -> "판정이 현재 배치와 달라 다시 검증해야 합니다."
        RejectReason.NEW_PLACEMENT_INVALID -> "새로 놓은 짐이 공중·지지 부족·회전·적층 조건을 어겨 확정할 수 없습니다."
        RejectReason.ACKNOWLEDGEMENT_REQUIRED -> "표시된 문제를 확인해야 확정할 수 있습니다."
        RejectReason.SEARCH_ALREADY_RUNNING -> "이미 후보를 찾는 중입니다."
        RejectReason.STALE_SEARCH_RESULT -> "배치가 바뀌어 탐색 결과를 버렸습니다."
        RejectReason.UNKNOWN_INSTANCE, RejectReason.DUPLICATE_INSTANCE, RejectReason.NO_VERSION_CHANGE,
        RejectReason.NOT_LOCKED, RejectReason.NOT_UNLOCKED -> "이 동작은 지금 적용되지 않습니다."
    }

    fun refuse(reason: SaveRejection): String = when (reason) {
        SaveRejection.NOT_VALIDATED -> "저장 안 됨: 현재 배치를 먼저 재검증하세요."
        SaveRejection.STALE -> "저장 안 됨: 판정이 현재 입력과 다릅니다."
        SaveRejection.NOT_CONFIRMED -> "저장 안 됨: 먼저 위치를 확정하세요."
        SaveRejection.LOCKED_DIFF -> "저장 안 됨: 명시 해제 없이 고정짐이 달라졌습니다."
        SaveRejection.HEAD_MOVED -> "저장 안 됨: 저장본이 먼저 변경되었습니다."
        SaveRejection.SEARCH_RUNNING -> "저장 안 됨: 후보 탐색이 끝나지 않았습니다."
        SaveRejection.NOT_AVAILABLE -> "저장 안 됨: 판정을 만들 수 없습니다."
        SaveRejection.ACKNOWLEDGEMENT_MISSING -> "저장 안 됨: 표시된 문제를 확인하지 않았습니다."
    }
}
