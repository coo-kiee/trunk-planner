package ai.cargo.render

import ai.cargo.designsystem.CargoTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Handle for evidence probes on the live view. Null while no surface exists. */
object SceneViewProbe {
    @Volatile internal var target: GlSceneView? = null
    fun request(points: List<Pair<Int, Int>>): Boolean = target?.let { it.probe(points); true } ?: false
}

/** The only composable entry to the renderer. Colors come from the theme tokens in scope and nowhere else. */
@Composable
fun CargoSceneView(
    scene: RenderScene,
    camera: RenderCamera,
    renderAllowed: Boolean,
    onPick: (String?) -> Unit,
    onCameraCommit: (RenderCamera) -> Unit,
    modifier: Modifier = Modifier,
    onCameraDrawn: (RenderCamera) -> Unit = {},
) {
    val theme = CargoTheme.values
    val palette = remember(theme) { RenderPalette.from(theme) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    AndroidView(
        modifier = modifier,
        factory = { context ->
            GlSceneView(context).also { view ->
                SceneViewProbe.target = view
                RenderStats.surface(0, 0)
            }
        },
        update = { view ->
            view.onPick = onPick
            view.onCameraCommit = onCameraCommit
            view.onCameraDrawn = onCameraDrawn
            view.setRenderAllowed(renderAllowed)
            view.show(scene, camera, palette)
            view.setLifecycleStarted(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        },
        onRelease = { view ->
            if (SceneViewProbe.target === view) SceneViewProbe.target = null
            view.released()
        },
    )
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val view = SceneViewProbe.target ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.setLifecycleStarted(true)
                Lifecycle.Event.ON_STOP -> view.setLifecycleStarted(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
