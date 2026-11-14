package ai.cargo.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewMathTest {
    private fun camera(yaw: Float) = RenderCamera(500f, 200f, 500f, yaw, 20f, 3000f)
    private val w = 1000
    private val h = 800

    @Test fun plusXGoesToScreenLeftFromTheOpening() {
        val math = ViewMath(camera(0f), w, h)
        assertTrue(math.project(V3(800f, 0f, 500f)).x < math.project(V3(200f, 0f, 500f)).x)
    }

    @Test fun plusXGoesToScreenRightFromTheCabin() {
        val math = ViewMath(camera(180f), w, h)
        assertTrue(math.project(V3(800f, 0f, 500f)).x > math.project(V3(200f, 0f, 500f)).x)
    }

    @Test fun nearestBoxWinsAPick() {
        val near = RenderInstance("near", listOf(RenderAabb(400f, 0f, 100f, 600f, 400f, 300f)), RenderRole.ITEM)
        val far = RenderInstance("far", listOf(RenderAabb(400f, 0f, 700f, 600f, 400f, 900f)), RenderRole.ITEM)
        val scene = RenderScene(null, emptyList(), listOf(far, near))
        val cam = RenderCamera(500f, 200f, 500f, 0f, 0f, 3000f)
        val p = ViewMath(cam, w, h).project(V3(500f, 200f, 200f))
        assertEquals("near", Picking.pick(scene, cam, w, h, p.x, p.y))
    }

    @Test fun missReturnsNull() {
        val box = RenderInstance("a", listOf(RenderAabb(0f, 0f, 0f, 10f, 10f, 10f)), RenderRole.ITEM)
        assertEquals(null, Picking.pick(RenderScene(null, emptyList(), listOf(box)), camera(0f), w, h, 2f, 2f))
    }

    @Test fun projectionAndRayAgree() {
        val math = ViewMath(camera(30f), w, h)
        val target = V3(300f, 100f, 700f)
        val pixel = math.project(target)
        val (origin, direction) = math.ray(pixel.x, pixel.y)
        val along = (target - origin).dot(direction)
        val closest = origin + direction * along
        assertTrue((closest - target).dot(closest - target) < 1f)
    }
}
