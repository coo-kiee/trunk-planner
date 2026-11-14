package ai.cargo.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Draws only on request. The gate combines what the app was told (UX restriction, lifecycle, window visibility);
 * none of those is inferred from another, and a closed gate stops drawing even if the surface asks for a frame.
 */
@SuppressLint("ViewConstructor")
internal class GlSceneView(context: Context) : GLSurfaceView(context) {
    private var lifecycleStarted = false
    private var renderAllowed = false
    private var windowVisible = false
    private val renderer = SceneRenderer { gateOpen() }

    private var scene = RenderScene.EMPTY
    private var requested: RenderCamera? = null
    private var camera: RenderCamera? = null
    private var palette: RenderPalette? = null

    var onPick: (String?) -> Unit = {}
    var onCameraDrag: (RenderCamera) -> Unit = {}
    var onCameraCommit: (RenderCamera) -> Unit = {}
    var onCameraDrawn: (RenderCamera) -> Unit = {}

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = camera ?: return false
            dragged(current.copy(distanceMm = (current.distanceMm / detector.scaleFactor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)))
            moved = true
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun gateOpen() = lifecycleStarted && renderAllowed && windowVisible

    fun setLifecycleStarted(started: Boolean) {
        if (started == lifecycleStarted) return
        lifecycleStarted = started
        if (started) onResume() else onPause()
        gateChanged()
    }

    fun setRenderAllowed(allowed: Boolean) {
        if (allowed == renderAllowed) return
        renderAllowed = allowed
        gateChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == View.VISIBLE
        gateChanged()
    }

    private fun gateChanged() {
        val reason = when {
            !lifecycleStarted -> "LIFECYCLE_STOPPED"
            !renderAllowed -> "UX_RESTRICTED"
            !windowVisible -> "WINDOW_HIDDEN"
            else -> "OPEN"
        }
        RenderStats.gate(gateOpen(), reason)
        if (gateOpen()) requestRender()
    }

    /** The view left the screen, e.g. because editing was hidden. Nothing draws until a new view exists. */
    fun released() {
        setLifecycleStarted(false)
        RenderStats.gate(false, "VIEW_REMOVED")
    }

    fun show(next: RenderScene, nextCamera: RenderCamera, nextPalette: RenderPalette) {
        requested = nextCamera
        val resolved = SurfaceFit.resolve(nextCamera, next, width, height)
        if (next == scene && resolved == camera && nextPalette == palette) return
        scene = next
        camera = resolved
        palette = nextPalette
        publish()
        onCameraDrawn(resolved)
    }

    /** A default view follows every size the window takes. A camera the user chose, or a drag in progress, does not. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wanted = requested?.takeIf { it.fitToSurface } ?: return
        val resolved = SurfaceFit.resolve(wanted, scene, w, h)
        if (resolved == camera) return
        camera = resolved
        publish()
        onCameraDrawn(resolved)
    }

    private fun dragged(next: RenderCamera) {
        camera = next
        publish()
        onCameraDrag(next)
    }

    private fun publish() {
        val cam = camera ?: return
        val pal = palette ?: return
        val previous = renderer.input.get()
        val frame = SceneRenderer.FrameInput(scene, cam, pal)
        renderer.input.set(frame)
        RenderStats.scene = scene
        RenderStats.camera = cam
        RenderStats.palette = pal
        if (previous != frame && gateOpen()) requestRender()
    }

    /** Reads back real pixels on the next drawn frame. Drawing it is a deliberate extra frame. */
    fun probe(points: List<Pair<Int, Int>>) {
        renderer.pendingProbes.set(points)
        if (gateOpen()) requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && event.pointerCount == 1) orbit(event)
            MotionEvent.ACTION_UP -> finishGesture(event)
        }
        return true
    }

    private fun orbit(event: MotionEvent) {
        val dx = event.x - lastX
        val dy = event.y - lastY
        if (!moved && abs(dx) + abs(dy) < TAP_SLOP_PX) return
        moved = true
        lastX = event.x
        lastY = event.y
        val current = camera ?: return
        dragged(
            current.copy(
                yawDegrees = current.yawDegrees - dx * DEGREES_PER_PX,
                pitchDegrees = (current.pitchDegrees + dy * DEGREES_PER_PX).coerceIn(MIN_PITCH, MAX_PITCH),
            ),
        )
    }

    private fun finishGesture(event: MotionEvent) {
        val current = camera ?: return
        if (moved) {
            onCameraCommit(current)
        } else {
            val id = Picking.pick(scene, current, width, height, event.x, event.y)
            RenderStats.lastPick = id
            onPick(id)
        }
    }

    private companion object {
        const val TAP_SLOP_PX = 12f
        const val DEGREES_PER_PX = 0.25f
        const val MIN_PITCH = -5f
        const val MAX_PITCH = 85f
        const val MIN_DISTANCE = 300f
        const val MAX_DISTANCE = 20_000f
    }
}
