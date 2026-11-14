package ai.cargo.render

import ai.cargo.designsystem.CargoThemeValues
import androidx.compose.ui.graphics.Color

/** One RGBA per drawn meaning. It has no default values and can only be built from theme tokens. */
data class RenderPalette(
    val clear: Rgba,
    val floor: Rgba,
    val wire: Rgba,
    val obstacle: Rgba,
    val item: Rgba,
    val selected: Rgba,
    val conflict: Rgba,
    val unverified: Rgba,
) {
    fun of(role: RenderRole): Rgba = when (role) {
        RenderRole.ITEM -> item
        RenderRole.SELECTED -> selected
        RenderRole.CONFLICT -> conflict
        RenderRole.UNVERIFIED -> unverified
    }

    companion object {
        private const val OBSTACLE_TEXT_SHARE = 0.35f

        fun from(theme: CargoThemeValues) = RenderPalette(
            clear = theme.background.rgba(),
            floor = theme.surface.rgba(),
            wire = theme.text.rgba(),
            obstacle = theme.surface.rgba().mix(theme.text.rgba(), OBSTACLE_TEXT_SHARE),
            item = theme.item.rgba(),
            selected = theme.selected.rgba(),
            conflict = theme.conflict.rgba(),
            unverified = theme.unverified.rgba(),
        )
    }
}

data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float) {
    fun mix(other: Rgba, share: Float) = Rgba(
        r + (other.r - r) * share, g + (other.g - g) * share, b + (other.b - b) * share, a,
    )

    fun scaled(light: Float) = Rgba(r * light, g * light, b * light, a)

    /** Bytes as glReadPixels would return them, for evidence output. */
    fun hex(): String = listOf(r, g, b).joinToString("", "#") { "%02X".format((it * 255 + 0.5f).toInt().coerceIn(0, 255)) }
}

private fun Color.rgba() = Rgba(red, green, blue, alpha)
