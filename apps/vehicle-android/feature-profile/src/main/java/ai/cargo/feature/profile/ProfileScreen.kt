package ai.cargo.feature.profile

import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoField
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

data class ProfileUiState(
    val profiles: List<ProfileVersion> = emptyList(),
    val form: ProfileForm? = null,
    val showErrors: Boolean = false,
    val unsupportedChosen: Boolean = false,
    val saveFailed: Boolean = false,
)

class ProfileActions(
    val useDemo: () -> Unit,
    val chooseUnsupported: () -> Unit,
    val startMeasure: () -> Unit,
    val changeForm: (ProfileForm) -> Unit,
    val saveMeasure: () -> Unit,
    val cancelMeasure: () -> Unit,
    val startScene: (ProfileVersion) -> Unit,
)

typealias Keypad = @Composable (label: String, value: String, onValue: (String) -> Unit, onNext: () -> Unit) -> Unit

@Composable
fun ProfileScreen(state: ProfileUiState, actions: ProfileActions, editingAllowed: Boolean, keypad: Keypad, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    Column(modifier.verticalScroll(rememberScrollState()).testTag("v1.screen"), verticalArrangement = Arrangement.spacedBy(theme.spacingMedium)) {
        val form = state.form
        when {
            !editingAllowed -> CargoStatus(CargoStatusKind.UNVERIFIED, "주행 제한 중: 측정 입력을 숨겼습니다. 입력 내용은 보존됩니다.", Modifier.testTag("v1.restricted"))
            form != null -> MeasureForm(form, state.showErrors, state.saveFailed, actions, keypad)
            else -> Choices(actions)
        }
        if (state.unsupportedChosen) UnsupportedNotice(editingAllowed, actions)
        Panel("차량 공간") {
            if (state.profiles.isEmpty()) Body("저장된 차량 공간이 없습니다.")
            state.profiles.forEachIndexed { index, profile -> ProfileRow(index, profile, editingAllowed, actions) }
        }
    }
}

@Composable
private fun Choices(actions: ProfileActions) {
    Panel("차량 공간 설정", Modifier.fillMaxWidth()) {
        Body("이 앱은 차량 트렁크 형상 데이터를 제공받지 않습니다. 직접 측정하거나 합성 시연 공간을 쓰세요.")
        CargoButton("직접 측정해 입력", actions.startMeasure, Modifier.testTag("v1.measure"))
        CargoButton("합성 시연 공간 만들기", actions.useDemo, Modifier.testTag("v1.demo"), CargoButtonKind.SECONDARY)
        CargoButton("내 차량 데이터가 없습니다", actions.chooseUnsupported, Modifier.testTag("v1.unsupported"), CargoButtonKind.SECONDARY)
    }
}

@Composable
private fun UnsupportedNotice(editingAllowed: Boolean, actions: ProfileActions) {
    Panel("미지원 차량", Modifier.fillMaxWidth().testTag("v1.unsupportedNotice")) {
        CargoStatus(CargoStatusKind.UNVERIFIED, "미지원 차량: 판정을 제공하지 않습니다", Modifier.testTag("v1.unsupportedStatus"))
        Body("줄자로 공간 폭·높이·깊이와 입구를 재서 입력하면 그 측정값 기준으로 확인할 수 있습니다.")
        if (editingAllowed) CargoButton("직접 측정 시작", actions.startMeasure, Modifier.testTag("v1.unsupportedMeasure"))
    }
}

@Composable
private fun ProfileRow(index: Int, profile: ProfileVersion, editingAllowed: Boolean, actions: ProfileActions) {
    Column(Modifier.testTag("v1.row.$index"), verticalArrangement = Arrangement.spacedBy(CargoTheme.values.spacingSmall)) {
        val kind = if (profile.grade == ProfileGrade.DEMO_SYNTHETIC || profile.grade == ProfileGrade.UNSUPPORTED) CargoStatusKind.UNVERIFIED else CargoStatusKind.CLEAR
        CargoStatus(kind, ProfileLabels.grade(profile.grade), Modifier.testTag("v1.row.$index.grade"))
        profile.bounds?.let { Body("공간 ${it.x}×${it.y}×${it.z} mm ±${profile.errorMm} · 장애물 ${profile.obstacles.size}개 · r${profile.revision}") }
        Body(profile.opening?.let { "입구 ${it.x}×${it.y} mm" } ?: "입구 미측정", Modifier.testTag("v1.row.$index.opening"))
        Body("출처: ${profile.provenance}${profile.sourceURL?.let { " · $it" }.orEmpty()}")
        if (profile.grade != ProfileGrade.UNSUPPORTED && editingAllowed) {
            CargoButton("이 공간으로 새 배치 시작", { actions.startScene(profile) }, Modifier.testTag("v1.row.$index.start"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeasureForm(form: ProfileForm, showErrors: Boolean, saveFailed: Boolean, actions: ProfileActions, keypad: Keypad) {
    val theme = CargoTheme.values
    val errors = if (showErrors) ProfileRules.check(form).errors else emptyMap()
    Panel("직접 측정", Modifier.fillMaxWidth().testTag("v1.form")) {
        if (saveFailed) CargoStatus(CargoStatusKind.CONFLICT, "저장하지 못했습니다. 입력은 그대로 있으니 값을 확인해 다시 시도하세요.", Modifier.testTag("v1.saveFailed"))
        Body("사람이 잰 값만 씁니다. 2차 측정까지 입력하면 반복 실측으로 표시하고 두 값의 차이를 오차에 반영합니다.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingMedium), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) { SpaceField.entries.forEach { field ->
            CargoField(
                label = field.label,
                value = form.text(field),
                onValueChange = { actions.changeForm(form.copy(numbers = form.numbers + (field to it.filter(Char::isDigit)))) },
                modifier = Modifier.widthIn(min = 200.dp, max = 300.dp).testTag("v1.field.${field.name}")
                    .onFocusEvent { if (it.hasFocus && form.focused != field) actions.changeForm(form.copy(focused = field)) },
                error = errors[field],
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        } }
        keypad(
            form.focused.label,
            form.text(form.focused),
            { actions.changeForm(form.copy(numbers = form.numbers + (form.focused to it))) },
            { actions.changeForm(form.copy(focused = SpaceField.entries[(form.focused.ordinal + 1) % SpaceField.entries.size])) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
            CargoButton("측정값 저장", actions.saveMeasure, Modifier.testTag("v1.save"))
            CargoButton("취소", actions.cancelMeasure, Modifier.testTag("v1.cancel"), CargoButtonKind.SECONDARY)
        }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    BasicText(text, modifier, style = TextStyle(color = theme.text, fontSize = theme.typeBody))
}
