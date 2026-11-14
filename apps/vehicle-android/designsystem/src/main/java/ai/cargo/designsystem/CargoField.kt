package ai.cargo.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

/** Label and error are always text. An error never relies on color alone. */
@Composable
fun CargoField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val theme = CargoTheme.values
    val body = TextStyle(color = theme.text, fontSize = theme.typeBody)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(theme.spacingSmall)) {
        BasicText(label, style = body)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = body,
            cursorBrush = SolidColor(theme.accent),
            keyboardOptions = keyboardOptions,
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.surface)
                .padding(theme.spacingMedium),
        )
        if (error != null) BasicText(error, style = body.copy(color = theme.conflict))
    }
}
