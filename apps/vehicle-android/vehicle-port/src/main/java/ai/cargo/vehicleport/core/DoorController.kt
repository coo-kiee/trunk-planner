package ai.cargo.vehicleport.core

import ai.cargo.vehicleport.api.DoorAvailability
import ai.cargo.vehicleport.api.DoorPosition
import ai.cargo.vehicleport.api.DoorState
import ai.cargo.vehicleport.api.DoorSuggestion
import ai.cargo.vehicleport.api.FailureKind
import ai.cargo.vehicleport.api.NotReadyReason
import ai.cargo.vehicleport.api.UnsupportedReason
import ai.cargo.vehicleport.gateway.DoorAreaKey
import ai.cargo.vehicleport.gateway.DoorBounds
import ai.cargo.vehicleport.gateway.DoorGateway
import ai.cargo.vehicleport.gateway.DoorListenerHandle
import ai.cargo.vehicleport.gateway.DoorPositionSink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SDK effects for one door area. Each start owns a Session: a callback from an older session only settles its
 * in-flight count and can never change state. SDK release runs after that count reaches zero.
 * Each registration owns a Link. Only the current link may speak, and an error or lost connection ends it, so nothing
 * from a link that stopped being trustworthy can move the position or become a suggestion.
 */
class DoorController(
    private val gatewayFactory: () -> DoorGateway,
    private val scheduler: PortScheduler,
    private val area: DoorAreaKey,
    private val backoffMs: List<Long> = READY_BACKOFF_MS,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(DoorState(DoorAvailability.NotReady(NotReadyReason.NOT_STARTED, 0), DoorPosition.Unknown))
    private val mutableSuggestions = MutableSharedFlow<DoorSuggestion>(extraBufferCapacity = 8)
    private var session: Session? = null
    val state: StateFlow<DoorState> = mutableState.asStateFlow()
    val suggestions: SharedFlow<DoorSuggestion> = mutableSuggestions.asSharedFlow()

    private inner class Link(val session: Session) : DoorListenerHandle {
        override val sink = DoorPositionSink { value -> onListener(this, value) }
        override val onFailed: (Throwable) -> Unit = { onListenerFailed(this, it) }
    }

    private inner class Session(val gateway: DoorGateway) {
        var active = true
        var inFlight = 0
        var releaseRequested = false
        var sdkReleased = false
        var link: Link? = null
        var reconnects = 0
        var track = DoorTrack()
        var cancelRetry: (() -> Unit)? = null
        var cancelStuck: (() -> Unit)? = null
    }

    fun start(): Unit = synchronized(lock) {
        if (session?.active == true) return
        val gateway = runCatching(gatewayFactory).getOrElse {
            publishAvailability(classifySdkFailure(it, SdkPhase.BEFORE_INITIALIZE).toTerminalAvailability())
            return
        }
        val current = Session(gateway)
        session = current
        mutableState.value = DoorState(DoorAvailability.NotReady(NotReadyReason.SERVICE_NOT_CONNECTED, 0), DoorPosition.Unknown)
        if (!current.active) return
        runCatching { gateway.initialize() }
            .onSuccess { requestCapability(current, attempt = 1, reason = NotReadyReason.SERVICE_NOT_CONNECTED) }
            .onFailure { fail(current, it, SdkPhase.CONNECTING) }
    }

    /** Stops every effect now and settles SDK release when in-flight calls have finished. Idempotent. */
    fun release(): Unit = synchronized(lock) {
        val current = session ?: run {
            mutableState.value = DoorState(DoorAvailability.Released, DoorPosition.Unknown)
            return
        }
        if (current.releaseRequested) return
        current.active = false
        current.releaseRequested = true
        current.cancelRetry?.invoke()
        endLink(current)
        if (session === current) mutableState.value = DoorState(DoorAvailability.Releasing(current.inFlight), DoorPosition.Unknown)
        current.cancelStuck = scheduler.schedule(RELEASE_STUCK_MS) { onReleaseStuck(current) }
        settleRelease(current)
    }

    private fun requestCapability(current: Session, attempt: Int, reason: NotReadyReason) {
        if (!current.active) return
        current.inFlight++
        val settled = Settled()
        runCatching {
            current.gateway.checkCapabilities(
                onComplete = { map -> settled.once { onCapability(current, map) } },
                onFailed = { error -> settled.once { onCapabilityFailed(current, error, attempt, reason) } },
            )
        }.onFailure { error -> settled.once { onCapabilityFailed(current, error, attempt, reason) } }
    }

    private fun onCapability(current: Session, map: Map<DoorAreaKey, DoorBounds>?): Unit = synchronized(lock) {
        finishCall(current)
        if (!current.active) return
        val bounds = map?.get(area)
        when {
            map == null -> publishAvailability(DoorAvailability.Unsupported(UnsupportedReason.CAPABILITY_NULL))
            bounds == null -> publishAvailability(DoorAvailability.Unsupported(UnsupportedReason.CAPABILITY_ABSENT))
            bounds.minValue != DOOR_CLOSED_VALUE || bounds.maxValue != DOOR_OPEN_VALUE ->
                publishAvailability(DoorAvailability.Unsupported(UnsupportedReason.UNEXPECTED_BOUNDS))
            else -> connectListener(current, bounds)
        }
    }

    private fun onCapabilityFailed(current: Session, error: Throwable, attempt: Int, reason: NotReadyReason): Unit = synchronized(lock) {
        finishCall(current)
        if (!current.active) return
        val failure = classifySdkFailure(error, SdkPhase.CONNECTING)
        if (failure != SdkFailure.NotConnected) {
            publishAvailability(failure.toTerminalAvailability())
            return
        }
        val delay = backoffMs.getOrNull(attempt - 1)
        if (delay == null) {
            publishAvailability(DoorAvailability.Failed(FailureKind.CONNECTION_TIMEOUT, "attempts=$attempt"))
            return
        }
        publishAvailability(DoorAvailability.NotReady(reason, attempt))
        if (!current.active) return
        current.cancelRetry = scheduler.schedule(delay) {
            synchronized(lock) { if (current.active) requestCapability(current, attempt + 1, reason) }
        }
    }

    private fun connectListener(current: Session, bounds: DoorBounds) {
        endLink(current)
        if (!current.active) return
        current.track = DoorTrack()
        // Owned before the call: nothing says the SDK holds its callbacks until register returns.
        val link = Link(current)
        current.link = link
        runCatching { current.gateway.register(area, link) }.onFailure { error ->
            if (isCurrent(link)) failOrReconnect(current, error)
            return
        }
        if (!isCurrent(link)) return
        mutableState.value = DoorState(DoorAvailability.Available(bounds.minValue, bounds.maxValue), current.track.position)
        if (!isCurrent(link)) return
        current.inFlight++
        val settled = Settled()
        runCatching {
            current.gateway.read(
                area,
                onComplete = { value -> settled.once { onInitialRead(link, value) } },
                onFailed = { error -> settled.once { onReadFailed(link, error) } },
            )
        }.onFailure { error -> settled.once { onReadFailed(link, error) } }
    }

    /**
     * Trust ends before the SDK is touched, because unregister may call back on this thread and the lock is reentrant.
     * The origin dies with the link: whatever comes next starts from a new baseline.
     */
    private fun endLink(current: Session) {
        val ended = current.link
        current.link = null
        current.track = DoorTrack()
        ended?.let { link -> runCatching { current.gateway.unregister(area, link) } }
    }

    private fun isCurrent(link: Link) = link.session.active && link.session.link === link

    private fun onInitialRead(link: Link, value: Int?): Unit = synchronized(lock) {
        finishCall(link.session)
        if (!isCurrent(link)) return
        link.session.track = observeInitialRead(link.session.track, value)
        link.session.reconnects = 0
        mutableState.value = mutableState.value.copy(position = link.session.track.position)
    }

    private fun onReadFailed(link: Link, error: Throwable): Unit = synchronized(lock) {
        finishCall(link.session)
        if (isCurrent(link)) failOrReconnect(link.session, error)
    }

    private fun onListener(link: Link, value: Int?): Unit = synchronized(lock) {
        if (!isCurrent(link)) return
        val current = link.session
        val step = observeListener(current.track, value)
        current.track = step.track
        // During registration a value only feeds the baseline: the link has not been declared usable yet.
        if (mutableState.value.availability !is DoorAvailability.Available) return
        current.reconnects = 0
        mutableState.value = mutableState.value.copy(position = step.track.position)
        // A state collector may have released the port during that publish.
        if (isCurrent(link)) step.suggestion?.let { mutableSuggestions.tryEmit(it) }
    }

    private fun onListenerFailed(link: Link, error: Throwable): Unit = synchronized(lock) {
        if (isCurrent(link)) failOrReconnect(link.session, error)
    }

    private fun failOrReconnect(current: Session, error: Throwable) {
        endLink(current)
        if (!current.active) return
        if (classifySdkFailure(error, SdkPhase.READY) != SdkFailure.NotConnected) {
            fail(current, error, SdkPhase.READY)
            return
        }
        // Capability can succeed while registration keeps failing, so reconnects need their own bound.
        val reconnect = ++current.reconnects
        val delay = if (reconnect == 1) 0L else backoffMs.getOrNull(reconnect - 2)
        if (delay == null) {
            publishAvailability(DoorAvailability.Failed(FailureKind.CONNECTION_TIMEOUT, "reconnects=$reconnect"))
            return
        }
        publishAvailability(DoorAvailability.NotReady(NotReadyReason.CONNECTION_LOST, reconnect))
        if (!current.active) return
        if (delay == 0L) {
            requestCapability(current, attempt = 1, reason = NotReadyReason.CONNECTION_LOST)
        } else {
            current.cancelRetry = scheduler.schedule(delay) {
                synchronized(lock) { if (current.active) requestCapability(current, attempt = 1, reason = NotReadyReason.CONNECTION_LOST) }
            }
        }
    }

    private fun fail(current: Session, error: Throwable, phase: SdkPhase) {
        if (current.active) publishAvailability(classifySdkFailure(error, phase).toTerminalAvailability())
    }

    private fun finishCall(current: Session) {
        current.inFlight--
        if (current.releaseRequested) settleRelease(current)
    }

    private fun settleRelease(current: Session) {
        if (session === current && !current.sdkReleased) {
            mutableState.value = DoorState(DoorAvailability.Releasing(current.inFlight), DoorPosition.Unknown)
        }
        if (current.inFlight > 0 || current.sdkReleased) return
        current.sdkReleased = true
        current.cancelStuck?.invoke()
        val outcome = runCatching { current.gateway.release() }
        if (session === current) {
            mutableState.value = DoorState(
                outcome.fold({ DoorAvailability.Released }, { DoorAvailability.Failed(FailureKind.UNCLASSIFIED, it.javaClass.name) }),
                DoorPosition.Unknown,
            )
        }
    }

    /** A stuck in-flight call is reported but SDK release is still withheld; a later callback finishes it. */
    private fun onReleaseStuck(current: Session): Unit = synchronized(lock) {
        if (session === current && !current.sdkReleased) {
            mutableState.value = DoorState(DoorAvailability.Failed(FailureKind.RELEASE_STUCK, "inFlight=${current.inFlight}"), DoorPosition.Unknown)
        }
    }

    private fun publishAvailability(availability: DoorAvailability) {
        mutableState.value = DoorState(availability, DoorPosition.Unknown)
    }

    /** SDK callbacks are not documented as at-most-once; the in-flight count must not go negative. */
    private class Settled {
        private var done = false
        fun once(action: () -> Unit) {
            val first = synchronized(this) { if (done) false else { done = true; true } }
            if (first) action()
        }
    }
}
