package ai.cargo.vehicleport.core

import ai.cargo.vehicleport.api.UxRestrictionState
import ai.cargo.vehicleport.api.UxUnavailableReason
import ai.cargo.vehicleport.gateway.UxConnectResult
import ai.cargo.vehicleport.gateway.UxGateway
import ai.cargo.vehicleport.gateway.UxSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun uxStateOf(sample: UxSample?): UxRestrictionState =
    if (sample == null) UxRestrictionState.Unavailable(UxUnavailableReason.RESTRICTIONS_NULL)
    else UxRestrictionState.Known(sample.requiresDistractionOptimization, sample.rawRestrictionMask)

fun uxFailureOf(error: Throwable): UxRestrictionState = when (error) {
    is SecurityException -> UxRestrictionState.Unavailable(UxUnavailableReason.PERMISSION_MISSING)
    else -> UxRestrictionState.Unavailable(UxUnavailableReason.FAILED, error.javaClass.name)
}

/** The manager accepts one listener. This holds that listener and fans out through the StateFlow. */
class UxController(private val gatewayFactory: () -> UxGateway) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<UxRestrictionState>(UxRestrictionState.Unavailable(UxUnavailableReason.NOT_STARTED))
    private var gateway: UxGateway? = null
    private var generation = 0
    val state: StateFlow<UxRestrictionState> = mutableState.asStateFlow()

    /**
     * Any SDK call or state publish here may re-enter on this thread and release or restart, so this attempt checks that
     * it is still the current one after each call. A superseded attempt writes nothing, including its own late error.
     */
    fun start(): Unit = synchronized(lock) {
        if (gateway != null) return
        val mine = ++generation
        runCatching {
            val created = gatewayFactory()
            gateway = created
            val connected = created.connect()
            if (mine != generation) return
            when (connected) {
                UxConnectResult.CAR_NULL -> {
                    gateway = null
                    mutableState.value = UxRestrictionState.Unavailable(UxUnavailableReason.CAR_NULL)
                }
                UxConnectResult.MANAGER_NULL -> mutableState.value = UxRestrictionState.Unavailable(UxUnavailableReason.MANAGER_NULL)
                UxConnectResult.CONNECTED -> {
                    created.register { sample -> onSample(mine, sample) }
                    if (mine != generation) return
                    val sample = created.current()
                    if (mine != generation) return
                    mutableState.value = uxStateOf(sample)
                }
            }
        }.onFailure {
            if (mine == generation) {
                // A listener left over from a failed start must not bring the gate back open.
                generation++
                mutableState.value = uxFailureOf(it)
            }
        }
    }

    private fun onSample(from: Int, sample: UxSample?): Unit = synchronized(lock) {
        if (from == generation && gateway != null) mutableState.value = uxStateOf(sample)
    }

    fun release(): Unit = synchronized(lock) {
        val ended = gateway
        gateway = null
        val mine = ++generation
        ended?.let { current ->
            runCatching { current.unregister() }
            runCatching { current.disconnect() }
        }
        // Unchanged generation means nothing restarted from inside those calls.
        if (mine == generation) mutableState.value = UxRestrictionState.Unavailable(UxUnavailableReason.RELEASED)
    }
}
