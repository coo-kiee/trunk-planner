package ai.cargo.planner

import ai.cargo.designsystem.CargoButton
import ai.cargo.designsystem.CargoButtonKind
import ai.cargo.designsystem.CargoTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle

object KeypadRules {
    private const val MAX_DIGITS = 6

    /** Digits and edit keys only. Leading zeros collapse so "007" cannot read differently from 7. */
    fun apply(value: String, key: String): String = when (key) {
        "back" -> value.dropLast(1)
        "clear" -> ""
        else -> if (value.length >= MAX_DIGITS) value else (value + key).trimStart('0').ifEmpty { "0" }
    }
}

/** The in-app number pad works without any system keyboard, which is not reliable on every vehicle image. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NumericKeypad(label: String, value: String, onValue: (String) -> Unit, onNext: () -> Unit) {
    val theme = CargoTheme.values
    Panel("숫자 입력: $label", Modifier.fillMaxWidth().testTag("keypad")) {
        BasicText(value.ifEmpty { "(비어 있음)" }, Modifier.testTag("keypad.value"), style = TextStyle(color = theme.text, fontSize = theme.typeTitle))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall), verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
            (1..9).forEach { digit -> Key(digit.toString(), "keypad.$digit") { onValue(KeypadRules.apply(value, digit.toString())) } }
            Key("0", "keypad.0") { onValue(KeypadRules.apply(value, "0")) }
            Key("지움", "keypad.back") { onValue(KeypadRules.apply(value, "back")) }
            Key("전체 지움", "keypad.clear") { onValue(KeypadRules.apply(value, "clear")) }
            CargoButton("다음 칸", onNext, Modifier.testTag("keypad.next"))
        }
    }
}

@Composable
private fun Key(label: String, tag: String, onClick: () -> Unit) {
    CargoButton(label, onClick, Modifier.testTag(tag), CargoButtonKind.SECONDARY)
}
