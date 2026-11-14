package ai.cargo.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** The five judgement meanings. The label parameter is required so that color is never the only signal. */
enum class CargoStatusKind { CLEAR, CONFLICT, UNCERTAIN, UNVERIFIED, STALE }

@Composable
@ReadOnlyComposable
fun CargoStatusKind.color(): Color {
    val theme = CargoTheme.values
    return when (this) {
        CargoStatusKind.CLEAR -> theme.clear
        CargoStatusKind.CONFLICT -> theme.conflict
        CargoStatusKind.UNCERTAIN -> theme.uncertain
        CargoStatusKind.UNVERIFIED -> theme.unverified
        CargoStatusKind.STALE -> theme.stale
    }
}

@Composable
fun CargoStatus(kind: CargoStatusKind, label: String, modifier: Modifier = Modifier) {
    val theme = CargoTheme.values
    Row(
        modifier = modifier.background(theme.surface).padding(theme.spacingSmall),
        horizontalArrangement = Arrangement.spacedBy(theme.spacingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(theme.spacingMedium).background(kind.color()))
        BasicText(label, style = TextStyle(color = theme.text, fontSize = theme.typeBody))
    }
}
