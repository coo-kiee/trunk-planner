package ai.cargo.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle

/** Primary is filled with the accent; secondary sits on the surface. Disabled uses an alpha of the same tokens. */
enum class CargoButtonKind { PRIMARY, SECONDARY }

private const val DISABLED_ALPHA = 0.45f

@Composable
fun CargoButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: CargoButtonKind = CargoButtonKind.PRIMARY,
    enabled: Boolean = true,
) {
    val theme = CargoTheme.values
    val base = if (kind == CargoButtonKind.PRIMARY) theme.accent else theme.surface
    val fill = if (enabled) base else base.copy(alpha = DISABLED_ALPHA)
    val ink = if (kind == CargoButtonKind.PRIMARY) theme.background else theme.text
    Box(
        modifier = modifier
            .heightIn(min = theme.spacingLarge * 3)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = theme.spacingLarge, vertical = theme.spacingMedium),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = if (enabled) ink else ink.copy(alpha = DISABLED_ALPHA), fontSize = theme.typeBody))
    }
}
