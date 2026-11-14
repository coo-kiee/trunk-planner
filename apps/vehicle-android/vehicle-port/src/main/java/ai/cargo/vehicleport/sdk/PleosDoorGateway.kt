package ai.cargo.vehicleport.sdk

import ai.cargo.vehicleport.gateway.DoorAreaKey
import ai.cargo.vehicleport.gateway.DoorBounds
import ai.cargo.vehicleport.gateway.DoorGateway
import ai.cargo.vehicleport.gateway.DoorListenerHandle
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.content.Context

/** The only file that names Vehicle SDK classes. Read-only: no set API exists on this seam. */
internal class PleosDoorGateway(context: Context) : DoorGateway {
    private val vehicle = Vehicle(context)
    private val listeners = HashMap<DoorListenerHandle, DoorPositionListener>()

    override fun initialize() = vehicle.initialize()

    override fun checkCapabilities(onComplete: (Map<DoorAreaKey, DoorBounds>?) -> Unit, onFailed: (Throwable) -> Unit) {
        vehicle.getDoor().checkDoorPositionCapabilities(
            onComplete = { _, areaMap ->
                onComplete(
                    DoorAreaKey.entries.mapNotNull { key ->
                        areaMap[doorArea(key)]?.let { key to DoorBounds(it.minValue, it.maxValue) }
                    }.toMap(),
                )
            },
            onFailed = { onFailed(it) },
        )
    }

    override fun read(area: DoorAreaKey, onComplete: (Int?) -> Unit, onFailed: (Throwable) -> Unit) {
        vehicle.getDoor().getCurrentDoorPosition(
            area = doorArea(area),
            onComplete = { onComplete(it) },
            onFailed = { onFailed(it) },
        )
    }

    override fun register(area: DoorAreaKey, handle: DoorListenerHandle) {
        val listener = object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) = handle.sink.onPosition(pos)
            override fun onFailed(exception: Exception) = handle.onFailed(exception)
        }
        listeners[handle] = listener
        vehicle.getDoor().registerDoorPosition(doorArea(area), listener)
    }

    /** The SDK matches on listener identity, so the registered instance is the one passed back. */
    override fun unregister(area: DoorAreaKey, handle: DoorListenerHandle) {
        listeners.remove(handle)?.let { vehicle.getDoor().unregisterDoorPosition(doorArea(area), it) }
    }

    override fun release() = vehicle.release()

    private fun doorArea(key: DoorAreaKey): DoorArea = when (key) {
        DoorAreaKey.REAR -> DoorArea(DoorValue.REAR)
        DoorAreaKey.ROW_3_LEFT -> DoorArea(DoorValue.ROW_3_LEFT)
    }
}
