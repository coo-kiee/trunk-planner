package ai.cargo.geometry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateViewTest {
    @Test fun CE21_opening_view_has_plus_x_on_screen_left_and_x0_on_screen_right() {
        val w = 1000L
        assertEquals(ScreenPointMm(1000, 0), CoordinateView.project(0, 0, w, ViewFrame.OPENING_LOOKING_INWARD), "x=0 at screen right")
        assertEquals(ScreenPointMm(0, 0), CoordinateView.project(1000, 0, w, ViewFrame.OPENING_LOOKING_INWARD), "max x at screen left")
        val before = CoordinateView.project(100, 0, w, ViewFrame.OPENING_LOOKING_INWARD)
        val after = CoordinateView.project(300, 0, w, ViewFrame.OPENING_LOOKING_INWARD)
        assertTrue(after.screenX < before.screenX, "moved to vehicle left: goes screen left from the opening")
    }

    @Test fun CE21_opposite_view_mirrors_screen_but_not_vehicle_side() {
        val w = 1000L
        val before = CoordinateView.project(100, 0, w, ViewFrame.CABIN_LOOKING_REAR)
        val after = CoordinateView.project(300, 0, w, ViewFrame.CABIN_LOOKING_REAR)
        assertTrue(after.screenX > before.screenX, "from the cabin the same move goes screen right")
        assertEquals(VehicleSide.LEFT, CoordinateView.sideOfMove(200), "label is vehicle left in both views")
        assertEquals(VehicleSide.RIGHT, CoordinateView.sideOfMove(-1))
        assertEquals(VehicleSide.CENTER, CoordinateView.sideOfMove(0))
    }

    @Test fun CE21_axes_form_the_contract_right_handed_frame() {
        val config = Json.parseToJsonElement(File(System.getProperty("cargo.productRoot")).resolve("packages/contracts/configuration-v1.json").readText()).jsonObject
        val frame = config["coordinateFrame"]!!.jsonPrimitive.content
        assertTrue(frame.contains("+x=vehicle left") && frame.contains("+y=up") && frame.contains("+z=vehicle front/inward") && frame.contains("right-handed"))
        // In a (forward, left, up) right-handed vehicle frame, left x up = forward.
        val left = listOf(0, 1, 0); val up = listOf(0, 0, 1)
        val cross = listOf(left[1] * up[2] - left[2] * up[1], left[2] * up[0] - left[0] * up[2], left[0] * up[1] - left[1] * up[0])
        assertEquals(listOf(1, 0, 0), cross)
    }

    @Test fun CE21_legacy_axis_fixture_values() {
        val fixture = Json.parseToJsonElement(File(System.getProperty("cargo.productRoot")).resolve("packages/contracts/migration-axis-v0-v1.json").readText()).jsonObject
        val old = fixture["old"]!!.jsonObject
        val new = fixture["new"]!!.jsonObject
        assertEquals(old["y"]!!.jsonPrimitive.long, new["z"]!!.jsonPrimitive.long)
        assertEquals(old["z"]!!.jsonPrimitive.long, new["y"]!!.jsonPrimitive.long)
    }
}
