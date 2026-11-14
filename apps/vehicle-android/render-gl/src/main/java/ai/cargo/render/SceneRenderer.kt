package ai.cargo.render

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GLES3 with a real depth buffer. Runs on the GL thread and only reads an immutable frame description. */
internal class SceneRenderer(private val gate: () -> Boolean) : GLSurfaceView.Renderer {
    data class FrameInput(val scene: RenderScene, val camera: RenderCamera, val palette: RenderPalette)

    val input = AtomicReference<FrameInput?>(null)
    val pendingProbes = AtomicReference<List<Pair<Int, Int>>>(emptyList())

    private var program = 0
    private var uMvp = 0
    private var buffer = 0
    private var uploadedScene: RenderScene? = null
    private var uploadedPalette: RenderPalette? = null
    private var mesh: SceneMesh? = null
    private var width = 1
    private var height = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context means every GL object is gone, so buffers are rebuilt from the retained scene description.
        program = linkProgram()
        uMvp = glGetUniformLocation(program, "mvp")
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        buffer = ids[0]
        uploadedScene = null
        val depth = IntArray(1)
        glGetIntegerv(GL_DEPTH_BITS, depth, 0)
        RenderStats.contextCreated(glGetString(GL_VERSION), depth[0])
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        glViewport(0, 0, w, h)
        RenderStats.surface(w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = input.get()
        if (!gate() || frame == null) {
            RenderStats.suppressed()
            return
        }
        val start = System.nanoTime()
        draw(frame)
        readProbes()
        glFinish()
        RenderStats.frame(System.nanoTime() - start)
    }

    private fun draw(frame: FrameInput) {
        val clear = frame.palette.clear
        glClearColor(clear.r, clear.g, clear.b, 1f)
        glClearDepthf(1f)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        // A camera move reuses the mesh; only geometry or colors rebuild it.
        if (uploadedScene != frame.scene || uploadedPalette != frame.palette) upload(frame)
        val current = mesh ?: return
        glUseProgram(program)
        glUniformMatrix4fv(uMvp, 1, false, ViewMath(frame.camera, width, height).viewProjection(), 0)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        val stride = 7 * 4
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 3 * 4)
        // Lines are pulled slightly forward so an edge never fights with the face it outlines.
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(1f, 1f)
        glDrawArrays(GL_TRIANGLES, 0, current.triangleVertices)
        glDisable(GL_POLYGON_OFFSET_FILL)
        glDrawArrays(GL_LINES, current.triangleVertices, current.lineVertices)
    }

    private fun upload(frame: FrameInput) {
        val built = SceneMeshBuilder.build(frame.scene, frame.palette)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        glBufferData(GL_ARRAY_BUFFER, built.vertices.capacity() * 4, built.vertices, GL_DYNAMIC_DRAW)
        mesh = built
        uploadedScene = frame.scene
        uploadedPalette = frame.palette
    }

    /** Actual framebuffer values at requested pixels, top-left origin as the rest of the app uses. */
    private fun readProbes() {
        val probes = pendingProbes.getAndSet(emptyList())
        if (probes.isEmpty()) return
        RenderStats.probes = probes.map { (x, y) ->
            val pixel = ByteBuffer.allocateDirect(4)
            glReadPixels(x, height - 1 - y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel)
            val hex = (0..2).joinToString("", "#") { "%02X".format(pixel.get(it).toInt() and 255) }
            PixelProbeResult(x, y, hex)
        }
    }

    private fun linkProgram(): Int {
        val id = glCreateProgram()
        glAttachShader(id, compile(GL_VERTEX_SHADER, VERTEX))
        glAttachShader(id, compile(GL_FRAGMENT_SHADER, FRAGMENT))
        glBindAttribLocation(id, 0, "position")
        glBindAttribLocation(id, 1, "color")
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] == GL_TRUE) { glGetProgramInfoLog(id) }
        return id
    }

    private fun compile(type: Int, source: String): Int = glCreateShader(type).also {
        glShaderSource(it, source)
        glCompileShader(it)
        val ok = IntArray(1)
        glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GL_TRUE) { glGetShaderInfoLog(it) }
    }

    private companion object {
        val VERTEX = """#version 300 es
            uniform mat4 mvp;
            in vec3 position;
            in vec4 color;
            out vec4 vColor;
            void main() { vColor = color; gl_Position = mvp * vec4(position, 1.0); }
        """.trimIndent()
        val FRAGMENT = """#version 300 es
            precision mediump float;
            in vec4 vColor;
            out vec4 outColor;
            void main() { outColor = vColor; }
        """.trimIndent()
    }
}
