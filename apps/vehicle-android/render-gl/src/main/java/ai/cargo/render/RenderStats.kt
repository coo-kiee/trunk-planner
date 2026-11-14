package ai.cargo.render

import org.json.JSONArray
import org.json.JSONObject

/** Plain counters of what the renderer really did, read by the activity dump. Nothing here affects drawing. */
object RenderStats {
    private const val WINDOW = 512
    private val frameNanos = LongArray(WINDOW)
    private var frameCursor = 0L

    @Volatile var framesDrawn = 0L; private set
    @Volatile var framesSuppressed = 0L; private set
    @Volatile var contextsCreated = 0L; private set
    @Volatile var gateOpen = false; private set
    @Volatile var gateReason = "NOT_ATTACHED"; private set
    @Volatile var depthBits = -1; private set
    @Volatile var glVersion: String? = null; private set
    @Volatile var surfaceWidth = 0; private set
    @Volatile var surfaceHeight = 0; private set
    @Volatile var lastPick: String? = null
    @Volatile var palette: RenderPalette? = null
    @Volatile var scene: RenderScene = RenderScene.EMPTY
    @Volatile var camera: RenderCamera? = null
    @Volatile var probes: List<PixelProbeResult> = emptyList()

    @Synchronized fun frame(nanos: Long) {
        frameNanos[(frameCursor % WINDOW).toInt()] = nanos
        frameCursor++
        framesDrawn++
    }

    @Synchronized fun suppressed() { framesSuppressed++ }
    @Synchronized fun contextCreated(version: String?, depth: Int) {
        contextsCreated++
        glVersion = version
        depthBits = depth
    }

    fun gate(open: Boolean, reason: String) {
        gateOpen = open
        gateReason = reason
    }

    fun surface(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    @Synchronized fun resetTiming() { frameCursor = 0 }

    @Synchronized private fun timing(): JSONObject {
        val count = minOf(frameCursor, WINDOW.toLong()).toInt()
        val sorted = frameNanos.take(count).sorted()
        return JSONObject().apply {
            put("samples", count)
            if (count > 0) {
                put("avgMs", sorted.average() / 1e6)
                put("p95Ms", sorted[(count * 95 / 100).coerceAtMost(count - 1)] / 1e6)
                put("maxMs", sorted.last() / 1e6)
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("framesDrawn", framesDrawn)
        put("framesSuppressed", framesSuppressed)
        put("contextsCreated", contextsCreated)
        put("gateOpen", gateOpen)
        put("gateReason", gateReason)
        put("glVersion", glVersion ?: JSONObject.NULL)
        put("depthBits", depthBits)
        put("surface", JSONArray(listOf(surfaceWidth, surfaceHeight)))
        put("lastPick", lastPick ?: JSONObject.NULL)
        put("timing", timing())
        palette?.let { put("palette", paletteJson(it)) }
        camera?.let { put("camera", cameraJson(it)) }
        put("instances", instancesJson())
        put("probes", JSONArray(probes.map { JSONObject().put("x", it.x).put("y", it.y).put("rgba", it.hex) }))
    }

    private fun paletteJson(p: RenderPalette) = JSONObject().apply {
        put("clear", p.clear.hex()); put("floor", p.floor.hex()); put("wire", p.wire.hex()); put("obstacle", p.obstacle.hex())
        put("item", p.item.hex()); put("selected", p.selected.hex()); put("conflict", p.conflict.hex()); put("unverified", p.unverified.hex())
    }

    private fun cameraJson(c: RenderCamera) = JSONObject().apply {
        put("target", JSONArray(listOf(c.targetX, c.targetY, c.targetZ)))
        put("yaw", c.yawDegrees); put("pitch", c.pitchDegrees); put("distance", c.distanceMm)
    }

    /** Where each instance actually lands on the surface, so direction claims can be checked in pixels. */
    private fun instancesJson(): JSONArray {
        val cam = camera
        val rows = scene.instances.map { instance ->
            val box = instance.boxes.first()
            JSONObject().apply {
                put("id", instance.id)
                put("role", instance.role.name)
                if (cam != null && surfaceWidth > 0) {
                    val top = V3(box.centerX, box.maxY, box.centerZ)
                    val point = ViewMath(cam, surfaceWidth, surfaceHeight).project(top)
                    put("screenX", point.x)
                    put("screenY", point.y)
                }
            }
        }
        return JSONArray(rows)
    }
}

data class PixelProbeResult(val x: Int, val y: Int, val hex: String)
