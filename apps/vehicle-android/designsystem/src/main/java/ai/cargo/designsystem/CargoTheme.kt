package ai.cargo.designsystem

import ai.cargo.tokens.CargoTokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compose view of the generated tokens. Components read this, so one injected value reaches every consumer. */
@Immutable
data class CargoThemeValues(
    val background: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val clear: Color,
    val conflict: Color,
    val uncertain: Color,
    val unverified: Color,
    val stale: Color,
    val selected: Color,
    val item: Color,
    val spacingSmall: Dp,
    val spacingMedium: Dp,
    val spacingLarge: Dp,
    val typeBody: TextUnit,
    val typeTitle: TextUnit,
) {
    companion object {
        /** Every value comes from the generated object; no literal is repeated here. */
        fun generated() = CargoThemeValues(
            background = Color(CargoTokens.colorBackground),
            surface = Color(CargoTokens.colorSurface),
            text = Color(CargoTokens.colorText),
            accent = Color(CargoTokens.colorAccent),
            clear = Color(CargoTokens.colorClear),
            conflict = Color(CargoTokens.colorConflict),
            uncertain = Color(CargoTokens.colorUncertain),
            unverified = Color(CargoTokens.colorUnverified),
            stale = Color(CargoTokens.colorStale),
            selected = Color(CargoTokens.colorSelected),
            item = Color(CargoTokens.colorItem),
            spacingSmall = CargoTokens.spacingSmall.dp,
            spacingMedium = CargoTokens.spacingMedium.dp,
            spacingLarge = CargoTokens.spacingLarge.dp,
            typeBody = CargoTokens.typeBody.sp,
            typeTitle = CargoTokens.typeTitle.sp,
        )
    }
}

private val LocalCargoTheme = staticCompositionLocalOf { CargoThemeValues.generated() }

object CargoTheme {
    val values: CargoThemeValues
        @Composable @ReadOnlyComposable get() = LocalCargoTheme.current
}

@Composable
fun CargoTheme(values: CargoThemeValues = CargoThemeValues.generated(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCargoTheme provides values, content = content)
}
