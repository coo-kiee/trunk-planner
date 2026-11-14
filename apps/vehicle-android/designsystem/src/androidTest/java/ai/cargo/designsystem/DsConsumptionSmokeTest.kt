package ai.cargo.designsystem

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rendered proof, not source inspection: each component is drawn with the generated theme and then with one injected
 * value changed. The pixels must follow. This is a DS smoke; product screens have their own consumption check.
 */
@RunWith(AndroidJUnit4::class)
class DsConsumptionSmokeTest {
    @get:Rule val rule = createComposeRule()

    private val probe = Color(0xFFFF00FF)
    private val generated = CargoThemeValues.generated()

    private fun capture(values: CargoThemeValues, content: @Composable () -> Unit): Pair<Set<Int>, Pair<Int, Int>> {
        current.value = values to content
        rule.waitForIdle()
        val image = rule.onNodeWithTag(TAG).captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return pixels.toSet() to (image.width to image.height)
    }

    private val current = mutableStateOf<Pair<CargoThemeValues, @Composable () -> Unit>>(generated to {})

    private fun host() = rule.setContent {
        val (values, content) = current.value
        CargoTheme(values) { Box(Modifier.testTag(TAG).background(Color.Black)) { content() } }
    }

    private fun report(component: String, token: String, field: String, default: Any, injected: Any, observed: String) {
        Log.i("DSSMOKE", """{"component":"$component","token":"$token","field":"$field","default":"$default","injected":"$injected","observed":"$observed"}""")
    }

    private fun assertColorFollows(component: String, token: String, original: Color, mutate: (CargoThemeValues) -> CargoThemeValues, content: @Composable () -> Unit) {
        val before = capture(generated, content).first
        assertTrue(original.toArgb() in before, "$component does not paint generated $token")
        assertFalse(probe.toArgb() in before)
        val after = capture(mutate(generated), content).first
        assertTrue(probe.toArgb() in after, "$component ignored injected $token")
        assertFalse(original.toArgb() in after, "$component still paints the old $token: a literal copy")
        report(component, token, "pixel", Integer.toHexString(original.toArgb()), Integer.toHexString(probe.toArgb()), "followed")
    }

    private fun assertSizeFollows(component: String, token: String, mutate: (CargoThemeValues) -> CargoThemeValues, content: @Composable () -> Unit) {
        val before = capture(generated, content).second
        val after = capture(mutate(generated), content).second
        assertTrue(after != before, "$component size ignored injected $token: $before")
        report(component, token, "size", before, after, "followed")
    }

    @Test fun button_paints_and_measures_from_tokens() {
        host()
        val primary: @Composable () -> Unit = { CargoButton("저장", {}) }
        assertColorFollows("CargoButton", "colorAccent", generated.accent, { it.copy(accent = probe) }, primary)
        assertColorFollows("CargoButton", "colorSurface", generated.surface, { it.copy(surface = probe) }) { CargoButton("취소", {}, kind = CargoButtonKind.SECONDARY) }
        assertSizeFollows("CargoButton", "spacingLarge", { it.copy(spacingLarge = 60.dp) }, primary)
        assertSizeFollows("CargoButton", "typeBody", { it.copy(typeBody = 48.sp) }, primary)
    }

    @Test fun field_paints_and_measures_from_tokens() {
        host()
        val field: @Composable () -> Unit = { Box(Modifier.width(320.dp)) { CargoField("너비(mm)", "1200", {}, error = "범위를 벗어남") } }
        assertColorFollows("CargoField", "colorSurface", generated.surface, { it.copy(surface = probe) }, field)
        assertSizeFollows("CargoField", "spacingMedium", { it.copy(spacingMedium = 44.dp) }, field)
        assertSizeFollows("CargoField", "typeBody", { it.copy(typeBody = 48.sp) }, field)
    }

    @Test fun status_uses_the_semantic_token_of_each_kind_with_a_label() {
        host()
        val cases = listOf(
            Triple(CargoStatusKind.CLEAR, "colorClear", generated.clear) to { v: CargoThemeValues -> v.copy(clear = probe) },
            Triple(CargoStatusKind.CONFLICT, "colorConflict", generated.conflict) to { v: CargoThemeValues -> v.copy(conflict = probe) },
            Triple(CargoStatusKind.UNCERTAIN, "colorUncertain", generated.uncertain) to { v: CargoThemeValues -> v.copy(uncertain = probe) },
            Triple(CargoStatusKind.UNVERIFIED, "colorUnverified", generated.unverified) to { v: CargoThemeValues -> v.copy(unverified = probe) },
            Triple(CargoStatusKind.STALE, "colorStale", generated.stale) to { v: CargoThemeValues -> v.copy(stale = probe) },
        )
        cases.forEach { (case, mutate) ->
            val (kind, token, original) = case
            assertColorFollows("CargoStatus", token, original, mutate) { CargoStatus(kind, kind.name) }
        }
        assertSizeFollows("CargoStatus", "spacingSmall", { it.copy(spacingSmall = 40.dp) }) { CargoStatus(CargoStatusKind.CLEAR, "OK") }
    }

    @Test fun panel_paints_and_measures_from_tokens() {
        host()
        val panel: @Composable () -> Unit = { CargoPanel("트렁크") { CargoStatus(CargoStatusKind.UNVERIFIED, "미검증") } }
        assertColorFollows("CargoPanel", "colorSurface", generated.surface, { it.copy(surface = probe) }, panel)
        assertSizeFollows("CargoPanel", "typeTitle", { it.copy(typeTitle = 72.sp) }, panel)
        assertSizeFollows("CargoPanel", "spacingLarge", { it.copy(spacingLarge = 80.dp) }, panel)
    }

    @Test fun disabled_button_does_not_fire_and_enabled_does() {
        var clicks = 0
        rule.setContent {
            CargoTheme {
                Column {
                    Box(Modifier.testTag("on")) { CargoButton("on", { clicks++ }) }
                    Box(Modifier.testTag("off")) { CargoButton("off", { clicks += 100 }, enabled = false) }
                }
            }
        }
        rule.onNodeWithTag("on").performClick()
        rule.onNodeWithTag("off").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    private companion object { const val TAG = "smoke" }
}
