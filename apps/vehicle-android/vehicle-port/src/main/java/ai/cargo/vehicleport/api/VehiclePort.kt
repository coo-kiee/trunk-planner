package ai.cargo.vehicleport.api

import ai.cargo.vehicleport.core.DoorController
import ai.cargo.vehicleport.core.HandlerPortScheduler
import ai.cargo.vehicleport.core.UxController
import ai.cargo.vehicleport.gateway.DoorAreaKey
import ai.cargo.vehicleport.gateway.DoorGateway
import ai.cargo.vehicleport.gateway.UxGateway
import ai.cargo.vehicleport.sdk.CarUxGateway
import ai.cargo.vehicleport.sdk.PleosDoorGateway
import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the app may learn from the vehicle. Values are typed; no SDK type crosses this boundary. */
interface VehiclePort {
    val door: StateFlow<DoorState>
    val doorSuggestions: SharedFlow<DoorSuggestion>
    val uxRestriction: StateFlow<UxRestrictionState>
    val naviArrival: NaviArrivalState

    fun start()

    /** Unregisters and invalidates callbacks now. SDK release waits until in-flight calls have completed. */
    fun release()
}

object VehiclePorts {
    fun create(context: Context): VehiclePort {
        val app = context.applicationContext
        return compose(
            DoorController({ doorGateway(app) }, HandlerPortScheduler(), DoorAreaKey.REAR),
            UxController { uxGateway(app) },
        )
    }

    /** The real gateways, exposed through SDK-free interfaces for the isolated harness. */
    fun doorGateway(context: Context): DoorGateway = PleosDoorGateway(context.applicationContext)
    fun uxGateway(context: Context): UxGateway = CarUxGateway(context.applicationContext)

    internal fun compose(door: DoorController, ux: UxController): VehiclePort = DefaultVehiclePort(door, ux)
}

private class DefaultVehiclePort(private val doorController: DoorController, private val uxController: UxController) : VehiclePort {
    override val door = doorController.state
    override val doorSuggestions = doorController.suggestions
    override val uxRestriction = uxController.state
    override val naviArrival: NaviArrivalState = NaviArrivalState.Disabled("NOT_OBSERVED")

    override fun start() {
        uxController.start()
        doorController.start()
    }

    override fun release() {
        doorController.release()
        uxController.release()
    }
}
