package ai.cargo.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

@Composable
fun CargoPanel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = CargoTheme.values
    Column(
        modifier = modifier.background(theme.surface).padding(theme.spacingLarge),
        verticalArrangement = Arrangement.spacedBy(theme.spacingMedium),
    ) {
        BasicText(title, style = TextStyle(color = theme.text, fontSize = theme.typeTitle))
        content()
    }
}
