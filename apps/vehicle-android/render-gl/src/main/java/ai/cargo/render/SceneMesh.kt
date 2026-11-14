package ai.cargo.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Interleaved position, color. Triangles first, then lines; the renderer draws the two ranges. */
class SceneMesh(val vertices: FloatBuffer, val triangleVertices: Int, val lineVertices: Int)

object SceneMeshBuilder {
    private const val FLOATS_PER_VERTEX = 7
    private const val GRID_STEP_MM = 250f

    // Fixed shading per face so that box faces stay distinct without any light setup in the shader.
    private const val LIGHT_TOP = 1f
    private const val LIGHT_NEAR = 0.82f
    private const val LIGHT_SIDE = 0.68f
    private const val LIGHT_BOTTOM = 0.5f

    fun build(scene: RenderScene, palette: RenderPalette): SceneMesh {
        val triangles = ArrayList<Float>()
        val lines = ArrayList<Float>()
        scene.space?.let { space ->
            quad(triangles, palette.floor, floorCorners(space))
            grid(lines, space, palette)
            wireBox(lines, space, palette.wire)
        }
        scene.obstacles.forEach { box ->
            solidBox(triangles, box, palette.obstacle)
            wireBox(lines, box, palette.wire)
        }
        scene.instances.forEach { instance ->
            instance.boxes.forEach { box ->
                solidBox(triangles, box, palette.of(instance.role))
                wireBox(lines, box, palette.wire)
            }
        }
        val all = FloatArray(triangles.size + lines.size)
        triangles.forEachIndexed { i, v -> all[i] = v }
        lines.forEachIndexed { i, v -> all[triangles.size + i] = v }
        val buffer = ByteBuffer.allocateDirect(all.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(all).position(0)
        return SceneMesh(buffer, triangles.size / FLOATS_PER_VERTEX, lines.size / FLOATS_PER_VERTEX)
    }

    private fun floorCorners(s: RenderAabb) = listOf(
        V3(s.minX, s.minY, s.minZ), V3(s.maxX, s.minY, s.minZ), V3(s.maxX, s.minY, s.maxZ), V3(s.minX, s.minY, s.maxZ),
    )

    private fun grid(out: MutableList<Float>, s: RenderAabb, palette: RenderPalette) {
        val color = palette.floor.mix(palette.wire, 0.25f)
        var x = s.minX
        while (x <= s.maxX) {
            line(out, V3(x, s.minY, s.minZ), V3(x, s.minY, s.maxZ), color)
            x += GRID_STEP_MM
        }
        var z = s.minZ
        while (z <= s.maxZ) {
            line(out, V3(s.minX, s.minY, z), V3(s.maxX, s.minY, z), color)
            z += GRID_STEP_MM
        }
    }

    private fun solidBox(out: MutableList<Float>, b: RenderAabb, color: Rgba) {
        val c = corners(b)
        quad(out, color.scaled(LIGHT_TOP), listOf(c[2], c[3], c[7], c[6]))
        quad(out, color.scaled(LIGHT_BOTTOM), listOf(c[0], c[1], c[5], c[4]))
        quad(out, color.scaled(LIGHT_NEAR), listOf(c[0], c[1], c[3], c[2]))
        quad(out, color.scaled(LIGHT_NEAR), listOf(c[4], c[5], c[7], c[6]))
        quad(out, color.scaled(LIGHT_SIDE), listOf(c[0], c[2], c[6], c[4]))
        quad(out, color.scaled(LIGHT_SIDE), listOf(c[1], c[3], c[7], c[5]))
    }

    private fun wireBox(out: MutableList<Float>, b: RenderAabb, color: Rgba) {
        val c = corners(b)
        listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
            .forEach { (i, j) -> line(out, c[i], c[j], color) }
    }

    /** Index bits: 1 = max x, 2 = max y, 4 = max z. */
    private fun corners(b: RenderAabb) = (0..7).map { i ->
        V3(if (i and 1 != 0) b.maxX else b.minX, if (i and 2 != 0) b.maxY else b.minY, if (i and 4 != 0) b.maxZ else b.minZ)
    }

    private fun quad(out: MutableList<Float>, color: Rgba, p: List<V3>) {
        listOf(p[0], p[1], p[2], p[0], p[2], p[3]).forEach { vertex(out, it, color) }
    }

    private fun line(out: MutableList<Float>, a: V3, b: V3, color: Rgba) {
        vertex(out, a, color)
        vertex(out, b, color)
    }

    private fun vertex(out: MutableList<Float>, p: V3, c: Rgba) {
        out.add(p.x); out.add(p.y); out.add(p.z)
        out.add(c.r); out.add(c.g); out.add(c.b); out.add(c.a)
    }
}
