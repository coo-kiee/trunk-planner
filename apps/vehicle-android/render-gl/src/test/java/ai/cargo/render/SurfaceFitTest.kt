package ai.cargo.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceFitTest {
    private val space = RenderAabb(0f, 0f, 0f, 1500f, 900f, 1500f)
    private val cornerItem = RenderInstance("item", listOf(RenderAabb(0f, 0f, 0f, 300f, 200f, 300f)), RenderRole.ITEM)
    private val scene = RenderScene(space, emptyList(), listOf(cornerItem))
    private val default = RenderCamera(750f, 300f, 750f, 0f, 25f, 3750f, fitToSurface = true)

    private fun corners(box: RenderAabb) = listOf(box.minX, box.maxX).flatMap { x ->
        listOf(box.minY, box.maxY).flatMap { y -> listOf(box.minZ, box.maxZ).map { z -> V3(x, y, z) } }
    }

    private fun outside(camera: RenderCamera, box: RenderAabb, w: Int, h: Int) = corners(box).count {
        val p = ViewMath(camera, w, h).project(it)
        !(p.inFront && p.x in 0f..w.toFloat() && p.y in 0f..h.toFloat())
    }

    /** The failure F-02 measured: this default on a 415x1000 surface loses 6 of 8 space corners. */
    @Test fun theStoredDefaultReallyClipsANarrowSurface() {
        assertEquals(6, outside(default.copy(fitToSurface = false), space, 415, 1000))
    }

    @Test fun everyCornerIsInsideOnTheMeasuredAndExtremeSurfaces() {
        for ((w, h) in listOf(415 to 1000, 856 to 1000, 300 to 1000, 1700 to 1185, 1274 to 300, 628 to 855)) {
            val drawn = SurfaceFit.resolve(default, scene, w, h)
            assertEquals(0, outside(drawn, space, w, h), "space on ${w}x$h")
            assertEquals(0, outside(drawn, cornerItem.boxes.single(), w, h), "item on ${w}x$h")
        }
    }

    @Test fun anItemOutsideTheSpaceIsStillShown() {
        val out = RenderInstance("out", listOf(RenderAabb(1400f, 0f, 1400f, 2600f, 500f, 2600f)), RenderRole.CONFLICT)
        val drawn = SurfaceFit.resolve(default, scene.copy(instances = listOf(out)), 415, 1000)
        assertEquals(0, outside(drawn, out.boxes.single(), 415, 1000))
    }

    @Test fun onlyTheDistanceChangesAndOnlyOutward() {
        val drawn = SurfaceFit.resolve(default, scene, 415, 1000)
        assertTrue(drawn.distanceMm > default.distanceMm)
        assertEquals(default.copy(distanceMm = drawn.distanceMm, fitToSurface = false), drawn)
        assertEquals(default.distanceMm, SurfaceFit.resolve(default, RenderScene.EMPTY, 415, 1000).distanceMm)
    }

    @Test fun aWideSurfaceKeepsTheStoredDistance() {
        assertEquals(default.distanceMm, SurfaceFit.resolve(default, scene, 856, 1000).distanceMm)
    }

    @Test fun narrowingAndWideningComeFromTheStoredValueNotThePreviousResult() {
        val wide = SurfaceFit.resolve(default, scene, 856, 1000)
        SurfaceFit.resolve(default, scene, 415, 1000)
        assertEquals(wide, SurfaceFit.resolve(default, scene, 856, 1000))
    }

    @Test fun aChosenCameraIsNeverTouched() {
        val chosen = default.copy(yawDegrees = 15f, distanceMm = 900f, fitToSurface = false)
        assertEquals(chosen, SurfaceFit.resolve(chosen, scene, 415, 1000))
    }

    @Test fun noSurfaceYetLeavesTheStoredDistance() {
        assertEquals(default.distanceMm, SurfaceFit.resolve(default, scene, 0, 0).distanceMm)
        assertFalse(SurfaceFit.resolve(default, scene, 0, 0).fitToSurface)
    }

    @Test fun picksTheItemAtItsPixelOnTheNarrowSurface() {
        val drawn = SurfaceFit.resolve(default, scene, 415, 1000)
        val p = ViewMath(drawn, 415, 1000).project(V3(150f, 100f, 150f))
        assertEquals("item", Picking.pick(scene, drawn, 415, 1000, p.x, p.y))
    }

    @Test fun plusXStaysOnTheScreenLeftAfterFitting() {
        val math = ViewMath(SurfaceFit.resolve(default, scene, 415, 1000), 415, 1000)
        assertTrue(math.project(V3(1500f, 0f, 750f)).x < math.project(V3(0f, 0f, 750f)).x)
    }
}
