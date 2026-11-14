package ai.cargo.feature.editor

import ai.cargo.contracts.Camera
import ai.cargo.contracts.Instance
import ai.cargo.contracts.ItemVersion
import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoField
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import ai.cargo.scene.ItemRef
import ai.cargo.scene.itemRef
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonRow(content: @Composable () -> Unit) {
    val theme = CargoTheme.values
    FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) { content() }
}

/** Everything placed in the draft, with a list route to selection that does not need the 3D view. */
@Composable
internal fun InstancesPanel(state: EditorUiState, vm: EditorViewModel) {
    val draft = state.draft ?: return
    val context = state.context ?: return
    Panel("배치된 짐 ${draft.instances.size}개", Modifier.fillMaxWidth().testTag("v4.instances")) {
        draft.instances.forEachIndexed { index, instance ->
            val item = context.item(instance.itemRef)
            val pose = instance.poseMm
            CargoButton(
                "${if (instance.instanceId == state.selected) "✓ " else ""}${item?.name ?: "읽을 수 없는 물품"} r${instance.itemRevision}${if (instance.locked) " · 고정" else ""} · x${pose.x} y${pose.y} z${pose.z}",
                { vm.select(instance.instanceId) },
                Modifier.testTag("v4.instance.$index"),
                if (instance.instanceId == state.selected) CargoButtonKind.PRIMARY else CargoButtonKind.SECONDARY,
            )
        }
    }
}

@Composable
internal fun SelectedPanel(state: EditorUiState, vm: EditorViewModel, keypad: Keypad) {
    val instance = state.draft?.instances?.firstOrNull { it.instanceId == state.selected } ?: return
    val context = state.context ?: return
    Panel("선택한 짐", Modifier.fillMaxWidth().testTag("v4.selected")) {
        Line("좌표 x ${instance.poseMm.x} · y ${instance.poseMm.y} · z ${instance.poseMm.z} · 자세 ${instance.orientationIndex}", Modifier.testTag("v4.selected.pose"))
        Line(if (instance.locked) "고정됨: 후보 탐색과 재계산이 이 짐을 옮기지 않습니다. 고정은 실제 결박이나 안전 보증이 아닙니다." else "고정 안 됨", Modifier.testTag("v4.selected.lock"))
        state.lastMove?.let { Line(it, Modifier.testTag("v4.lastMove")) }
        ButtonRow {
            listOf(10L, 50L, 100L).forEach { step ->
                CargoButton(
                    if (step == state.stepMm) "✓ ${step}mm" else "${step}mm",
                    { vm.setStep(step) },
                    Modifier.testTag("v4.step.$step"),
                    if (step == state.stepMm) CargoButtonKind.PRIMARY else CargoButtonKind.SECONDARY,
                )
            }
        }
        ButtonRow {
            MoveDirection.entries.forEach { direction ->
                CargoButton(direction.label, { vm.nudge(direction) }, Modifier.testTag("v4.move.${direction.name}"), CargoButtonKind.SECONDARY, enabled = !instance.locked)
            }
        }
        ButtonRow {
            CargoButton("회전", vm::rotate, Modifier.testTag("v4.rotate"), CargoButtonKind.SECONDARY, enabled = !instance.locked)
            CargoButton(if (instance.locked) "고정 해제" else "고정", vm::toggleLock, Modifier.testTag("v4.lock"), CargoButtonKind.SECONDARY)
            CargoButton("좌표 입력", vm::beginPoseEntry, Modifier.testTag("v4.poseEntry"), CargoButtonKind.SECONDARY, enabled = !instance.locked)
            CargoButton("삭제", vm::remove, Modifier.testTag("v4.remove"), CargoButtonKind.SECONDARY, enabled = !instance.locked)
        }
        state.poseEntry?.let { PoseEntryForm(it, vm, keypad) }
        NewerVersionOffer(instance, context.newestItemRevision(instance.itemId), vm)
    }
}

@Composable
private fun NewerVersionOffer(instance: Instance, newest: Long?, vm: EditorViewModel) {
    if (newest == null || newest <= instance.itemRevision) return
    CargoStatus(CargoStatusKind.UNCERTAIN, "보관함에 새 버전 r$newest 있음. 이 공간은 r${instance.itemRevision}을 유지 중이며 판정은 바뀌지 않았습니다.", Modifier.testTag("v4.newerItem"))
    CargoButton("새 버전 r$newest 적용", { vm.applyItemVersion(instance.instanceId, newest) }, Modifier.testTag("v4.applyItem"), CargoButtonKind.SECONDARY)
}

@Composable
private fun PoseEntryForm(entry: PoseEntry, vm: EditorViewModel, keypad: Keypad) {
    PoseAxis.entries.forEach { axis ->
        CargoField(
            label = axis.label,
            value = entry.values[axis].orEmpty(),
            onValueChange = { vm.changePoseEntry(entry.copy(values = entry.values + (axis to it))) },
            modifier = Modifier.fillMaxWidth().testTag("v4.pose.${axis.name}")
                .onFocusEvent { if (it.hasFocus && entry.focused != axis) vm.changePoseEntry(entry.copy(focused = axis)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    keypad(
        entry.focused.label,
        entry.values[entry.focused].orEmpty(),
        { vm.changePoseEntry(entry.copy(values = entry.values + (entry.focused to it))) },
        { vm.changePoseEntry(entry.copy(focused = PoseAxis.entries[(entry.focused.ordinal + 1) % PoseAxis.entries.size])) },
    )
    ButtonRow {
        CargoButton("이 좌표로 이동", vm::applyPoseEntry, Modifier.testTag("v4.pose.apply"))
        CargoButton("취소", { vm.changePoseEntry(null) }, Modifier.testTag("v4.pose.cancel"), CargoButtonKind.SECONDARY)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AddItemPanel(state: EditorUiState, vm: EditorViewModel, openLibrary: () -> Unit) {
    Panel("짐 추가", Modifier.fillMaxWidth().testTag("v4.add")) {
        if (state.library.isEmpty()) Line("보관함이 비어 있습니다.", Modifier.testTag("v4.add.empty"))
        Line("후보 찾기는 새 짐의 자리만 찾으며 놓인 짐을 옮기지 않습니다.")
        state.library.forEachIndexed { index, item -> AddRow(index, item, state, vm) }
        CargoButton("보관함 열기", openLibrary, Modifier.testTag("v4.openLibrary"), CargoButtonKind.SECONDARY)
        state.offer?.let { offer ->
            val p = offer.pose.poseMm
            // The result appears below the button that asked for it, so it is brought on screen rather than missed.
            val request = remember { BringIntoViewRequester() }
            LaunchedEffect(offer) { request.bringIntoView() }
            CargoStatus(
                if (offer.uncertain) CargoStatusKind.UNCERTAIN else CargoStatusKind.CLEAR,
                "후보 x ${p.x} · y ${p.y} · z ${p.z} · 자세 ${offer.pose.orientationIndex}${if (offer.uncertain) " (오차에 따라 불확실)" else ""}",
                Modifier.testTag("v4.offer"),
            )
            CargoButton("이 후보로 놓기", vm::acceptOffer, Modifier.bringIntoViewRequester(request).testTag("v4.offer.accept"))
        }
        if (state.searching) {
            Line("후보 찾는 중", Modifier.testTag("v4.searching"))
            CargoButton("탐색 취소", vm::cancelSearch, Modifier.testTag("v4.searchCancel"), CargoButtonKind.SECONDARY)
        }
        if (state.draft?.lastSearch?.status == ai.cargo.contracts.SearchStatus.SEARCH_INTERRUPTED && !state.searching) {
            CargoButton("탐색 이어하기", vm::resumeSearch, Modifier.testTag("v4.searchResume"))
        }
    }
}

@Composable
private fun AddRow(index: Int, item: ItemVersion, state: EditorUiState, vm: EditorViewModel) {
    Column(Modifier.testTag("v4.add.$index"), verticalArrangement = Arrangement.spacedBy(CargoTheme.values.spacingSmall)) {
        val d = item.measuredDimensionsMm
        Line("${item.name} r${item.revision} · ${d.x}×${d.y}×${d.z} mm")
        ButtonRow {
            val ref = ItemRef(item.id, item.revision)
            CargoButton("후보 찾기", { vm.search(ref) }, Modifier.testTag("v4.add.$index.search"), enabled = !state.searching)
            CargoButton("입구 모서리에 직접 놓기", { vm.addDirect(ref) }, Modifier.testTag("v4.add.$index.direct"), CargoButtonKind.SECONDARY)
        }
    }
}

/** Buttons give the same camera as gestures, so the view stays usable with no drag and is testable step by step. */
@Composable
internal fun CameraPanel(state: EditorUiState, vm: EditorViewModel, drawnDistance: Long?) {
    val stored = state.draft?.camera ?: return
    val profile = state.profile
    val camera = EditorRules.cameraOnScreen(stored, profile, drawnDistance)
    fun shift(yaw: Long = 0, pitch: Long = 0, distancePercent: Long = 100): Camera = camera.copy(
        yawDegrees = camera.yawDegrees + yaw,
        pitchDegrees = (camera.pitchDegrees + pitch).coerceIn(-5, 85),
        distanceMm = (camera.distanceMm * distancePercent / 100).coerceIn(300, 20_000),
    )
    Panel("시점", Modifier.fillMaxWidth().testTag("v4.camera")) {
        Line(EditorRules.viewHint(camera.yawDegrees), Modifier.testTag("v4.viewHint"))
        Line("yaw ${camera.yawDegrees}° · pitch ${camera.pitchDegrees}° · 거리 ${camera.distanceMm}mm", Modifier.testTag("v4.camera.values"))
        ButtonRow {
            CargoButton("왼쪽 회전", { vm.setCamera(shift(yaw = -15)) }, Modifier.testTag("v4.cam.yawMinus"), CargoButtonKind.SECONDARY)
            CargoButton("오른쪽 회전", { vm.setCamera(shift(yaw = 15)) }, Modifier.testTag("v4.cam.yawPlus"), CargoButtonKind.SECONDARY)
            CargoButton("위에서", { vm.setCamera(shift(pitch = 10)) }, Modifier.testTag("v4.cam.pitchPlus"), CargoButtonKind.SECONDARY)
            CargoButton("낮게", { vm.setCamera(shift(pitch = -10)) }, Modifier.testTag("v4.cam.pitchMinus"), CargoButtonKind.SECONDARY)
            CargoButton("가까이", { vm.setCamera(shift(distancePercent = 85)) }, Modifier.testTag("v4.cam.closer"), CargoButtonKind.SECONDARY)
            CargoButton("멀리", { vm.setCamera(shift(distancePercent = 115)) }, Modifier.testTag("v4.cam.farther"), CargoButtonKind.SECONDARY)
            CargoButton("입구 시점", { vm.setCamera(stored.copy(yawDegrees = 0)) }, Modifier.testTag("v4.cam.opening"), CargoButtonKind.SECONDARY)
            CargoButton("차량 안쪽 시점", { vm.setCamera(camera.copy(yawDegrees = 180)) }, Modifier.testTag("v4.cam.cabin"), CargoButtonKind.SECONDARY)
            if (profile != null && !EditorRules.isFitCamera(stored, profile)) {
                CargoButton("전체 보기", { vm.setCamera(EditorRules.fitCamera(profile)) }, Modifier.testTag("v4.cam.fit"), CargoButtonKind.SECONDARY)
            }
        }
    }
}
