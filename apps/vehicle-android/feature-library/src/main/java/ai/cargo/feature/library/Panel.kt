package ai.cargo.feature.library

import ai.cargo.designsystem.CargoPanel
import ai.cargo.designsystem.CargoTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Controls paint on the page color inside a panel, so they stay distinct from the panel's own surface. */
@Composable
internal fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val theme = CargoTheme.values
    CargoPanel(title, modifier) {
        CargoTheme(theme.copy(surface = theme.background)) {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(theme.spacingMedium)) { content() }
        }
    }
}
