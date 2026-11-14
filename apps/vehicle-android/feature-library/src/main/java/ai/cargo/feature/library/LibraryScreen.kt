package ai.cargo.feature.library

import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.ShapeKind
import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoField
import ai.cargo.designsystem.CargoStatus
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.designsystem.CargoTheme
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

data class LibraryUiState(
    val entries: List<LibraryEntry> = emptyList(),
    val form: ItemForm? = null,
    val showErrors: Boolean = false,
    val message: LibraryMessage? = null,
    val pendingInboundCount: Int = 0,
)

class LibraryActions(
    val newItem: () -> Unit,
    val newVersion: (LibraryEntry) -> Unit,
    val changeForm: (ItemForm) -> Unit,
    val save: () -> Unit,
    val cancel: () -> Unit,
    val openPairing: () -> Unit,
)

/** Numeric input the app provides: value in, new value out, next moves to the following field. */
typealias Keypad = @Composable (label: String, value: String, onValue: (String) -> Unit, onNext: () -> Unit) -> Unit

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    editingAllowed: Boolean,
    photoOf: (String?) -> Bitmap?,
    keypad: Keypad,
    modifier: Modifier = Modifier,
) {
    val theme = CargoTheme.values
    Column(
        modifier.verticalScroll(rememberScrollState()).testTag("v2.screen"),
        verticalArrangement = Arrangement.spacedBy(theme.spacingMedium),
    ) {
        val form = state.form
        // A refused save keeps the form on screen, so its reason has to appear above the form, not below it.
        state.message?.takeIf { form != null && editingAllowed }?.let { MessageRow(it) }
        if (!editingAllowed) {
            CargoStatus(CargoStatusKind.UNVERIFIED, "주행 제한 중: 물품 입력을 숨겼습니다. 입력 중이던 내용은 보존됩니다.", Modifier.testTag("v2.restricted"))
        } else if (form != null) {
            ItemFormPanel(form, state.showErrors, actions, keypad)
        }
        state.message?.takeUnless { form != null && editingAllowed }?.let { MessageRow(it) }
        if (state.pendingInboundCount > 0) {
            Panel("휴대폰 수신 초안 ${state.pendingInboundCount}건") {
                CargoButton("수신 초안 검토", actions.openPairing, Modifier.testTag("v2.inbound"), CargoButtonKind.SECONDARY)
            }
        }
        if (form == null && editingAllowed) {
            CargoButton("새 물품 등록", actions.newItem, Modifier.testTag("v2.new"))
        }
        Panel("물품 보관함") {
            if (state.entries.isEmpty()) Body("등록된 물품이 없습니다. 실제 치수를 재서 입력하세요.")
            state.entries.forEachIndexed { index, entry -> EntryRow(index, entry, editingAllowed && form == null, actions, photoOf) }
        }
    }
}

@Composable
private fun MessageRow(message: LibraryMessage) {
    when (message) {
        is LibraryMessage.Saved -> {
            val text = if (message.wasNewVersion) {
                "${message.name} 새 버전 r${message.revision}을 저장했습니다. 기존 공간은 이전 버전을 그대로 참조하며, 공간에서 적용하기 전까지 판정이 바뀌지 않습니다."
            } else {
                "${message.name} r${message.revision}을 저장했습니다."
            }
            CargoStatus(CargoStatusKind.CLEAR, text, Modifier.testTag("v2.message"))
        }
        is LibraryMessage.Conflict -> CargoStatus(CargoStatusKind.CONFLICT, "보관함이 먼저 변경되었습니다(r${message.expected}). 저장하지 않았습니다.", Modifier.testTag("v2.message"))
        LibraryMessage.Invalid -> CargoStatus(CargoStatusKind.CONFLICT, "입력을 확인하세요. 저장하지 않았습니다.", Modifier.testTag("v2.message"))
        LibraryMessage.StorageFailed -> CargoStatus(CargoStatusKind.CONFLICT, "저장소에 쓰지 못했습니다. 입력은 그대로 있으니 다시 시도하세요.", Modifier.testTag("v2.message"))
    }
}

@Composable
private fun EntryRow(index: Int, entry: LibraryEntry, canEdit: Boolean, actions: LibraryActions, photoOf: (String?) -> Bitmap?) {
    val item = entry.latest
    Column(Modifier.testTag("v2.item.$index"), verticalArrangement = Arrangement.spacedBy(CargoTheme.values.spacingSmall)) {
        Body("${item.name} · r${item.revision} · ${ItemLabels.shape(item.shapeKind)}", Modifier.testTag("v2.item.$index.title"))
        Body("${ItemLabels.dimensions(item)} · ${ItemLabels.source(item.measurementSource)}")
        Body("${ItemLabels.rotation(item.rotationRule)} · 적층 ${if (item.stackable) "가능" else "금지"} · 꺼냄 순서 ${item.removalPriority}")
        photoOf(item.photoRef)?.let { bitmap ->
            Image(bitmap.asImageBitmap(), "물품 식별용 사진", Modifier.height(CargoTheme.values.spacingLarge * 4))
            Body("사진은 식별 보조입니다. 치수는 위 확정값만 사용합니다.")
        }
        entry.uses.forEach { use ->
            val note = if (use.itemRevision < item.revision) "새 버전 미적용(이전 판정 유지)" else "최신 버전 사용"
            Body("공간 저장본 r${use.sceneRevision}: 물품 r${use.itemRevision} 참조 · $note", Modifier.testTag("v2.item.$index.use"))
        }
        if (canEdit) {
            CargoButton("치수 변경(새 버전)", { actions.newVersion(entry) }, Modifier.testTag("v2.item.$index.newVersion"), CargoButtonKind.SECONDARY)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemFormPanel(form: ItemForm, showErrors: Boolean, actions: LibraryActions, keypad: Keypad) {
    val theme = CargoTheme.values
    val errors = if (showErrors) ItemFormRules.errors(form) else FormErrors()
    val update = actions.changeForm
    Panel(if (form.editingItemId == null) "새 물품" else "${form.name} 새 버전 r${form.baseRevision + 1}", Modifier.fillMaxWidth().testTag("v2.form")) {
        Body("사진은 물품 식별용입니다. 실제 치수는 직접 재서 입력하고 확인해야 판정에 쓰입니다.")
        CargoField("이름", form.name, { update(form.copy(name = it)) }, Modifier.fillMaxWidth().testTag("v2.name"), errors.name)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
            NAME_PRESETS.forEach { preset ->
                CargoButton(preset, { update(form.copy(name = preset)) }, Modifier.testTag("v2.preset.$preset"), CargoButtonKind.SECONDARY)
            }
        }
        Options("형상", ShapeKind.entries, form.shape, ItemLabels::shape, "v2.shape") { update(form.copy(shape = it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingMedium), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) { form.visibleFields.forEach { field ->
            CargoField(
                label = field.label,
                value = form.text(field),
                onValueChange = { update(form.copy(numbers = form.numbers + (field to it.filter(Char::isDigit)))) },
                modifier = Modifier.widthIn(min = 200.dp, max = 260.dp).testTag("v2.field.${field.name}")
                    .onFocusEvent { if (it.hasFocus && form.focused != field) update(form.copy(focused = field)) },
                error = errors.fields[field],
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        } }
        val focused = form.focused.takeIf { it in form.visibleFields } ?: form.visibleFields.first()
        keypad(
            focused.label,
            form.text(focused),
            { value -> update(form.copy(numbers = form.numbers + (focused to value))) },
            { update(form.copy(focused = form.visibleFields.let { it[(it.indexOf(focused) + 1) % it.size] })) },
        )
        Options("측정 출처", listOf(MeasurementSource.USER_TAPE, MeasurementSource.REPEATED_TAPE, MeasurementSource.DEMO_SYNTHETIC), form.source, ItemLabels::source, "v2.source") {
            update(form.copy(source = it))
        }
        Options("회전 조건", RotationRule.entries, form.rotation, ItemLabels::rotation, "v2.rotation") { update(form.copy(rotation = it)) }
        Options("적층", listOf(true, false), form.stackable, { if (it) "위에 다른 짐 적층 가능" else "위에 적층 금지" }, "v2.stack") {
            update(form.copy(stackable = it))
        }
        CargoButton(
            if (form.confirmed) "실제 치수 확인함 ✓" else "실제 치수를 재서 확인했습니다",
            { update(form.copy(confirmed = !form.confirmed)) },
            Modifier.testTag("v2.confirm"),
            if (form.confirmed) CargoButtonKind.PRIMARY else CargoButtonKind.SECONDARY,
        )
        if (errors.unconfirmed) CargoStatus(CargoStatusKind.UNVERIFIED, "치수 확인 전에는 저장할 수 없습니다.", Modifier.testTag("v2.unconfirmed"))
        Row(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
            CargoButton("보관함에 저장", actions.save, Modifier.testTag("v2.save"))
            CargoButton("취소", actions.cancel, Modifier.testTag("v2.cancel"), CargoButtonKind.SECONDARY)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Options(title: String, options: List<T>, selected: T, label: (T) -> String, tag: String, onSelect: (T) -> Unit) {
    val theme = CargoTheme.values
    Body(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
        options.forEachIndexed { index, option ->
            CargoButton(
                if (option == selected) "✓ ${label(option)}" else label(option),
                { onSelect(option) },
                Modifier.testTag("$tag.$index"),
                if (option == selected) CargoButtonKind.PRIMARY else CargoButtonKind.SECONDARY,
            )
        }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    BasicText(text, modifier, style = TextStyle(color = theme.text, fontSize = theme.typeBody))
}

private val NAME_PRESETS = listOf("캐리어", "유모차", "골프백", "접이식 의자", "박스")
