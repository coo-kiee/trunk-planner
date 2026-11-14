package ai.cargo.vehicleport.gateway

/** Own vocabulary for the SDK seam. Implementations translate; nothing here names an SDK class. */
enum class DoorAreaKey { REAR, ROW_3_LEFT }

data class DoorBounds(val minValue: Int, val maxValue: Int)

fun interface DoorPositionSink {
    fun onPosition(value: Int?)
}

interface DoorListenerHandle {
    val sink: DoorPositionSink
    val onFailed: (Throwable) -> Unit
}

interface DoorGateway {
    fun initialize()
    fun checkCapabilities(onComplete: (Map<DoorAreaKey, DoorBounds>?) -> Unit, onFailed: (Throwable) -> Unit)
    fun read(area: DoorAreaKey, onComplete: (Int?) -> Unit, onFailed: (Throwable) -> Unit)
    fun register(area: DoorAreaKey, handle: DoorListenerHandle)
    fun unregister(area: DoorAreaKey, handle: DoorListenerHandle)
    fun release()
}

data class UxSample(val requiresDistractionOptimization: Boolean, val rawRestrictionMask: Int)

/** Returned by connect so that a null car or manager stays distinct from a failure. */
enum class UxConnectResult { CONNECTED, CAR_NULL, MANAGER_NULL }

interface UxGateway {
    fun connect(): UxConnectResult
    fun current(): UxSample?
    fun register(listener: (UxSample?) -> Unit)
    fun unregister()
    fun disconnect()
}
