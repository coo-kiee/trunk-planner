package ai.cargo.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun normalized(): V3 = this * (1f / sqrt(dot(this)))
}

/** Pixel with the origin at the top left, plus whether it lies in front of the camera. */
data class ScreenPoint(val x: Float, val y: Float, val inFront: Boolean)

/** Column-major matrices and the projection for one camera. Pure, so its direction rules are unit tested. */
class ViewMath(camera: RenderCamera, val width: Int, val height: Int) {
    private val target = V3(camera.targetX, camera.targetY, camera.targetZ)
    val eye: V3
    internal val forward: V3
    internal val right: V3
    internal val up: V3
    internal val tanHalfFov = tan((FOV_DEGREES / 2) * PI.toFloat() / 180f)
    internal val aspect = width.toFloat() / height.coerceAtLeast(1)
    private val near = (camera.distanceMm * 0.01f).coerceAtLeast(10f)
    private val far = camera.distanceMm * 8f + 20_000f

    init {
        val yaw = camera.yawDegrees * PI.toFloat() / 180f
        val pitch = camera.pitchDegrees * PI.toFloat() / 180f
        // Yaw 0 stands behind the opening at -z and looks toward +z.
        val offset = V3(sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch)) * camera.distanceMm
        eye = target + offset
        forward = (target - eye).normalized()
        right = forward.cross(WORLD_UP).normalized()
        up = right.cross(forward)
    }

    fun viewProjection(): FloatArray = multiply(projection(), view())

    /** Right-handed lookAt: looking along +z with +y up, +x falls on the screen left. */
    private fun view() = floatArrayOf(
        right.x, up.x, -forward.x, 0f,
        right.y, up.y, -forward.y, 0f,
        right.z, up.z, -forward.z, 0f,
        -right.dot(eye), -up.dot(eye), forward.dot(eye), 1f,
    )

    private fun projection(): FloatArray {
        val f = 1f / tanHalfFov
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, 2f * far * near / (near - far), 0f,
        )
    }

    fun project(p: V3): ScreenPoint {
        val rel = p - eye
        val depth = rel.dot(forward)
        val ndcX = rel.dot(right) / (depth * tanHalfFov * aspect)
        val ndcY = rel.dot(up) / (depth * tanHalfFov)
        return ScreenPoint((ndcX + 1) / 2 * width, (1 - ndcY) / 2 * height, depth > 0)
    }

    /** Ray through a pixel, as origin and unit direction. */
    fun ray(px: Float, py: Float): Pair<V3, V3> {
        val ndcX = px / width * 2 - 1
        val ndcY = 1 - py / height * 2
        val direction = (forward + right * (ndcX * tanHalfFov * aspect) + up * (ndcY * tanHalfFov)).normalized()
        return eye to direction
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0..3) for (r in 0..3) {
            out[c * 4 + r] = (0..3).fold(0f) { sum, k -> sum + a[k * 4 + r] * b[c * 4 + k] }
        }
        return out
    }

    companion object {
        const val FOV_DEGREES = 45f
        private val WORLD_UP = V3(0f, 1f, 0f)
    }
}

/**
 * Turns a default camera into the one drawn on this surface. Only the distance changes and only outward, and only the
 * drawn copy: the camera stored in the draft keeps its value, so a window change is never an edit and the view follows
 * every later size. A camera the user chose is returned untouched.
 */
object SurfaceFit {
    /** Share of the half-width and half-height the scene may fill, so its edge lines stay visible. */
    private const val FILL = 0.95f

    fun resolve(camera: RenderCamera, scene: RenderScene, width: Int, height: Int): RenderCamera {
        if (!camera.fitToSurface) return camera
        if (width <= 0 || height <= 0) return camera.copy(fitToSurface = false)
        return camera.copy(distanceMm = maxOf(camera.distanceMm, requiredDistance(camera, scene, width, height)), fitToSurface = false)
    }

    private fun requiredDistance(camera: RenderCamera, scene: RenderScene, width: Int, height: Int): Float {
        val math = ViewMath(camera, width, height)
        val target = V3(camera.targetX, camera.targetY, camera.targetZ)
        val halfHeight = math.tanHalfFov * FILL
        val halfWidth = halfHeight * math.aspect
        val boxes = listOfNotNull(scene.space) + scene.obstacles + scene.instances.flatMap { it.boxes }
        var required = 0f
        for (box in boxes) for (x in listOf(box.minX, box.maxX)) for (y in listOf(box.minY, box.maxY)) for (z in listOf(box.minZ, box.maxZ)) {
            val rel = V3(x, y, z) - target
            // A corner stays inside while |side| <= (distance + along) * half, solved here for the distance.
            val along = rel.dot(math.forward)
            required = maxOf(required, abs(rel.dot(math.right)) / halfWidth - along, abs(rel.dot(math.up)) / halfHeight - along)
        }
        return ceil(required)
    }
}

object Picking {
    /** Nearest instance under the pixel by CPU ray and AABB slabs, independent of what the GPU last drew. */
    fun pick(scene: RenderScene, camera: RenderCamera, width: Int, height: Int, px: Float, py: Float): String? {
        val (origin, direction) = ViewMath(camera, width, height).ray(px, py)
        return scene.instances
            .mapNotNull { instance -> instance.boxes.mapNotNull { hit(it, origin, direction) }.minOrNull()?.let { instance.id to it } }
            .minByOrNull { it.second }?.first
    }

    private fun hit(box: RenderAabb, origin: V3, direction: V3): Float? {
        var near = 0f
        var far = Float.MAX_VALUE
        val axes = listOf(
            Triple(origin.x, direction.x, box.minX to box.maxX),
            Triple(origin.y, direction.y, box.minY to box.maxY),
            Triple(origin.z, direction.z, box.minZ to box.maxZ),
        )
        for ((o, d, range) in axes) {
            if (d == 0f) {
                if (o < range.first || o > range.second) return null
                continue
            }
            val a = (range.first - o) / d
            val b = (range.second - o) / d
            near = maxOf(near, minOf(a, b))
            far = minOf(far, maxOf(a, b))
            if (near > far) return null
        }
        return near
    }
}
