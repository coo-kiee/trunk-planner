package ai.cargo.portharness

import ai.cargo.vehicleport.api.VehiclePort
import ai.cargo.vehicleport.api.VehiclePorts
import ai.cargo.vehicleport.core.SdkPhase
import ai.cargo.vehicleport.core.classifySdkFailure
import ai.cargo.vehicleport.gateway.DoorAreaKey
import ai.cargo.vehicleport.gateway.DoorGateway
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Drives the real port and the real SDK gateway, then writes observed values to logcat for the runner. */
class HarnessActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var port: VehiclePort

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Port harness: no product UI" })
        record("life", "onCreate")
        // The real pre-initialize boundary, on an uninitialized gateway separate from the port's own.
        readDirect("beforeInitialize", VehiclePorts.doorGateway(this), DoorAreaKey.REAR, SdkPhase.BEFORE_INITIALIZE)
        port = VehiclePorts.create(this)
        port.door.onEach { record("door", it.toString()) }.launchIn(scope)
        port.doorSuggestions.onEach { record("suggestion", it.name) }.launchIn(scope)
        port.uxRestriction.onEach { record("ux", "$it editing=${it.editingAllowed} qr=${it.qrAllowed} renderStop=${it.renderStop}") }.launchIn(scope)
        record("navi", port.naviArrival.toString())
        port.start()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.getStringExtra("cmd")) {
            "row3" -> readRow3()
            "release" -> port.release()
            "restart" -> port.start()
            "afterRelease" -> readAfterRelease()
        }
    }

    private fun readRow3() {
        val gateway = VehiclePorts.doorGateway(this)
        gateway.initialize()
        // Capability is the bounded readiness signal here too: read only once it answered.
        gateway.checkCapabilities(
            onComplete = { map ->
                record("row3.capability", "present=${map?.containsKey(DoorAreaKey.ROW_3_LEFT)}")
                readDirect("row3.read", gateway, DoorAreaKey.ROW_3_LEFT, SdkPhase.READY)
            },
            onFailed = { record("row3.capability", classify(it, SdkPhase.CONNECTING)) },
        )
    }

    private fun readAfterRelease() {
        val gateway = VehiclePorts.doorGateway(this)
        gateway.initialize()
        gateway.release()
        readDirect("afterRelease", gateway, DoorAreaKey.REAR, SdkPhase.AFTER_RELEASE)
    }

    private fun readDirect(topic: String, gateway: DoorGateway, area: DoorAreaKey, phase: SdkPhase) {
        runCatching {
            gateway.read(area, { record(topic, "value=$it") }, { record(topic, "onFailed ${classify(it, phase)}") })
        }.onFailure { record(topic, "thrown ${classify(it, phase)}") }
    }

    private fun classify(error: Throwable, phase: SdkPhase) =
        "${classifySdkFailure(error, phase)} raw=${error.javaClass.name}:${error.message?.take(120)}"

    private fun record(topic: String, value: String) = Log.i("PORTHARNESS", "$topic | $value")

    override fun onDestroy() {
        record("life", "onDestroy")
        scope.cancel()
        port.release()
        super.onDestroy()
    }
}
