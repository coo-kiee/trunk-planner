package ai.cargo.planner

import ai.cargo.designsystem.CargoTheme
import ai.cargo.feature.editor.EditorViewModel
import ai.cargo.render.RenderStats
import ai.cargo.render.SceneViewProbe
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.FileDescriptor
import java.io.PrintWriter
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    @Volatile private var editor: EditorViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Relay polling exists only while the app is on screen.
            override fun onStart(owner: LifecycleOwner) { vm.graph.coordinator?.onForeground() }
            override fun onStop(owner: LifecycleOwner) { vm.graph.coordinator?.onBackground() }
        })
        val theme = BuildHooks.theme(this)
        setContent {
            CargoTheme(theme) {
                AppRoot(vm) { editor = it }
            }
        }
    }

    /**
     * Read-only state for `dumpsys activity` in every variant, so the release APK is observed the same way as the
     * demo one. Holds no bearer, QR or photo. "probe x,y ..." asks the renderer for real framebuffer pixels.
     */
    override fun dump(prefix: String, fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        val list = args.orEmpty().toList()
        val probeAt = list.indexOf("probe")
        if (probeAt >= 0) {
            val points = list.drop(probeAt + 1).map { it.split(',').let { (x, y) -> x.toInt() to y.toInt() } }
            SceneViewProbe.request(points)
        }
        writer.println("CARGO_DUMP " + snapshot())
    }

    private fun snapshot() = JSONObject().apply {
        put("variant", BuildHooks.VARIANT)
        put("signing", BuildConfig.SIGNING_KIND)
        put("routes", vm.stack.value.joinToString(">") { it.code })
        put("ux", vm.graph.vehicle.uxRestriction.value.toString())
        put("door", vm.graph.vehicle.door.value.toString())
        put("endpoints", vm.graph.endpoints.javaClass.simpleName)
        put("render", RenderStats.toJson())
        editor?.state?.value?.draft?.let { draft ->
            put("draft", JSONObject().apply {
                put("sceneId", draft.sceneId)
                put("baseRevision", draft.baseRevision ?: JSONObject.NULL)
                put("undo", draft.undo.size)
                put("redo", draft.redo.size)
                put("camera", "${draft.camera.yawDegrees},${draft.camera.pitchDegrees},${draft.camera.distanceMm}")
                put("selected", editor?.state?.value?.selected ?: JSONObject.NULL)
                put("instances", JSONArray(draft.instances.map {
                    JSONObject().put("id", it.instanceId).put("x", it.poseMm.x).put("y", it.poseMm.y).put("z", it.poseMm.z)
                        .put("o", it.orientationIndex).put("locked", it.locked).put("itemRevision", it.itemRevision)
                }))
            })
        }
    }
}
