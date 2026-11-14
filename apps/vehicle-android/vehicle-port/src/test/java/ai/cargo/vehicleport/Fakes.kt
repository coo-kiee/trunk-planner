package ai.cargo.vehicleport

import ai.cargo.vehicleport.core.PortScheduler
import ai.cargo.vehicleport.gateway.DoorAreaKey
import ai.cargo.vehicleport.gateway.DoorBounds
import ai.cargo.vehicleport.gateway.DoorGateway
import ai.cargo.vehicleport.gateway.DoorListenerHandle
import ai.cargo.vehicleport.gateway.UxConnectResult
import ai.cargo.vehicleport.gateway.UxGateway
import ai.cargo.vehicleport.gateway.UxSample

/** Fixture only. Lives under test so that production can never fall back to it. */
class ManualScheduler : PortScheduler {
    val pending = ArrayList<Pair<Long, () -> Unit>>()
    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val entry = delayMs to task
        pending.add(entry)
        return { pending.remove(entry) }
    }
    fun runNext() = pending.removeAt(0).second()
}

class FakeDoorGateway : DoorGateway {
    val calls = ArrayList<String>()
    var initializeError: Throwable? = null
    var capabilityThrows: Throwable? = null
    var registerThrows: Throwable? = null
    var pendingCapability: Pair<(Map<DoorAreaKey, DoorBounds>?) -> Unit, (Throwable) -> Unit>? = null
    var pendingRead: Pair<(Int?) -> Unit, (Throwable) -> Unit>? = null
    var handle: DoorListenerHandle? = null

    override fun initialize() { calls += "initialize"; initializeError?.let { throw it } }
    override fun checkCapabilities(onComplete: (Map<DoorAreaKey, DoorBounds>?) -> Unit, onFailed: (Throwable) -> Unit) {
        calls += "capability"
        capabilityThrows?.let { throw it }
        pendingCapability = onComplete to onFailed
    }
    override fun read(area: DoorAreaKey, onComplete: (Int?) -> Unit, onFailed: (Throwable) -> Unit) {
        calls += "read"
        pendingRead = onComplete to onFailed
    }
    override fun register(area: DoorAreaKey, handle: DoorListenerHandle) {
        calls += "register"
        registerThrows?.let { throw it }
        this.handle = handle
    }
    override fun unregister(area: DoorAreaKey, handle: DoorListenerHandle) { calls += "unregister" }
    override fun release() { calls += "release" }

    fun completeCapability(map: Map<DoorAreaKey, DoorBounds>? = mapOf(DoorAreaKey.REAR to DoorBounds(0, 100))) = pendingCapability!!.first(map)
    fun failCapability(error: Throwable) = pendingCapability!!.second(error)
    fun completeRead(value: Int?) = pendingRead!!.first(value)
    fun failRead(error: Throwable) = pendingRead!!.second(error)
    fun emit(value: Int?) = handle!!.sink.onPosition(value)
}

class FakeUxGateway(private val result: UxConnectResult = UxConnectResult.CONNECTED, var sample: UxSample? = UxSample(false, 0)) : UxGateway {
    val calls = ArrayList<String>()
    var listeners = 0
    var listener: ((UxSample?) -> Unit)? = null
    override fun connect() = result.also { calls += "connect" }
    override fun current() = sample
    override fun register(listener: (UxSample?) -> Unit) { listeners++; this.listener = listener }
    override fun unregister() { calls += "unregister" }
    override fun disconnect() { calls += "disconnect" }
}
