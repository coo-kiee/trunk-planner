package ai.cargo.planner

import ai.cargo.data.CargoStore
import ai.cargo.data.StoreOpen
import ai.cargo.data.StoreOpener
import ai.cargo.feature.editor.EditorHooks
import ai.cargo.sync.AndroidKeystoreCipher
import ai.cargo.sync.CredentialStore
import ai.cargo.sync.PhotoStore
import ai.cargo.sync.RelayEndpoints
import ai.cargo.sync.RoomInboundAdapter
import ai.cargo.sync.SyncCoordinator
import ai.cargo.sync.SyncEngine
import ai.cargo.sync.SyncJournal
import ai.cargo.sync.SyncStatus
import ai.cargo.sync.UrlConnectionTransport
import ai.cargo.sync.resolveEndpoints
import ai.cargo.vehicleport.api.VehiclePort
import android.app.Application
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/** Process-wide wiring. Storage opens once and every blocking write goes through one serial dispatcher. */
class AppGraph(private val app: Application) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val io = Dispatchers.IO.limitedParallelism(1)
    val compute = Dispatchers.Default
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val index = LocalIndex(app)
    val vehicle: VehiclePort by lazy { BuildHooks.vehiclePort(app) }

    /** Blocking; callers stay off the main thread. A store that cannot open never yields a writable handle. */
    val storeOpen: StoreOpen by lazy { StoreOpener.open(app, DB_NAME, System.currentTimeMillis()) }
    val store: CargoStore? get() = (storeOpen as? StoreOpen.Ready)?.store

    val editorHooks = EditorHooks(saveFault = { BuildHooks.saveFault(app) }, searchBudget = { BuildHooks.searchBudget(app) })

    fun newId(): String = UUID.randomUUID().toString()
    fun isoNow(): String = Instant.now().toString()

    val endpoints: RelayEndpoints by lazy { resolveEndpoints(BuildHooks.relayUrl(app), BuildHooks.phoneUrl(app), BuildHooks.TRUST) }

    /** Absent only when storage failed. With no endpoints it still exists and reports Disabled honestly. */
    val sync: SyncEngine? by lazy {
        val opened = store ?: return@lazy null
        val home = File(app.noBackupFilesDir, "sync").apply { mkdirs() }
        SyncEngine(
            endpoints, UrlConnectionTransport(), RoomInboundAdapter(opened.inbound), SyncJournal(File(home, "journal.json")),
            CredentialStore(home, AndroidKeystoreCipher()), PhotoStore(File(home, "photos")), System::currentTimeMillis, ::isoNow,
        )
    }

    val coordinator: SyncCoordinator? by lazy {
        val engine = sync
        if (engine == null || endpoints !is RelayEndpoints.Enabled) null else SyncCoordinator(engine, scope, Dispatchers.IO)
    }

    fun initialSyncStatus(): SyncStatus = when (val e = endpoints) {
        is RelayEndpoints.Disabled -> SyncStatus.Disabled(e.problem)
        is RelayEndpoints.Enabled -> SyncStatus.NotPaired
    }

    companion object { const val DB_NAME = "cargo.db" }
}
