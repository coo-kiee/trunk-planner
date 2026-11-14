package ai.cargo.sync

import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundKey
import ai.cargo.data.StoreOpen
import ai.cargo.data.StoreOpener
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** One stage per process. Crash stages kill this process at the exact seam, from a thin wrapper around the real parts. */
class SyncWorkerService : Service() {
    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Thread { runStage(intent) }.start()
        return START_NOT_STICKY
    }

    private fun runStage(intent: Intent) {
        val stage = intent.getStringExtra(STAGE)!!
        val out = JSONObject().put("stage", stage).put("pid", Process.myPid())
        runCatching { execute(stage, intent, out) }.onFailure { out.put("error", "${it.javaClass.name}: ${it.message}") }
        writeResult(stage, out)
    }

    private fun writeResult(stage: String, out: JSONObject) {
        val results = File(filesDir, RESULTS).apply { mkdirs() }
        val temp = File(results, "$stage.tmp")
        temp.writeText(out.toString())
        temp.renameTo(File(results, "$stage.json"))
    }

    /** The real relay refuses changed content, so these stages alter what the vehicle reads, to reach real Room. */
    private fun tamper(stage: String, response: HttpResponse): HttpResponse {
        val text = response.body.decodeToString()
        val altered = when (stage) {
            SWAP_HASH -> text.replace(Regex("\"payloadHash\":\"[0-9a-f]{64}\""), "\"payloadHash\":\"${"0".repeat(64)}\"")
            SWAP_CONTENT -> text.replace("\"x\":116", "\"x\":117")
            else -> return response
        }
        return HttpResponse(response.status, emptyMap(), altered.toByteArray())
    }

    private fun die(stage: String, out: JSONObject): Nothing {
        writeResult(stage, out.put("killedAtSeam", true))
        Process.killProcess(Process.myPid())
        error("unreachable")
    }

    private fun execute(stage: String, intent: Intent, out: JSONObject) {
        val store = (StoreOpener.open(this, DB, System.currentTimeMillis()) as StoreOpen.Ready).store
        val room = RoomInboundAdapter(store.inbound)
        val dir = File(noBackupFilesDir, DIR).apply { mkdirs() }
        val relayUrl = if (stage == OFFLINE || stage == OFFLINE_DECIDE) "http://10.0.2.2:9" else intent.getStringExtra(RELAY)!!
        val endpoints = resolveEndpoints(relayUrl, intent.getStringExtra(PHONE)!!, TransportTrust.DEMO_LOCAL)
        val real = UrlConnectionTransport(connectMs = 3_000)
        val transport = RelayTransport { request ->
            val isAck = request.method == "POST" && request.url.endsWith("/ack")
            if (stage == CRASH_BEFORE_ACK && isAck) die(stage, out)
            val response = try {
                real.execute(request)
            } catch (error: java.io.IOException) {
                out.put("transportError", "${error.javaClass.simpleName}: ${error.message}")
                throw error
            }
            out.put("lastHttpStatus", response.status)
            if (stage == CRASH_AFTER_ACK && isAck) die(stage, out)
            if (request.method == "GET" && "/vehicle/v1/drafts?" in request.url) tamper(stage, response) else response
        }
        val gateway = if (stage == CRASH_BEFORE_ROOM) object : InboundGateway by room {
            override fun receive(draft: ai.cargo.contracts.ItemDraft, nowMs: Long) = die(stage, out)
        } else room
        if (stage == SWAP_CONTENT) File(dir, "journal.json").delete()
        val journal = SyncJournal(File(dir, "journal.json"))
        val credentials = CredentialStore(dir, AndroidKeystoreCipher(ALIAS))
        val engine = SyncEngine(endpoints, transport, gateway, journal, credentials, PhotoStore(File(dir, "photos")), { System.currentTimeMillis() }, { Instant.now().toString() })
        val key = intent.getStringExtra(DRAFT)?.let { InboundKey(intent.getStringExtra(PAIRING_ID)!!, it, 1) }
        out.put("unsentBefore", room.unsentAcks().size).put("pendingBefore", room.pending().size)
        key?.let { out.put("journalBefore", journal.entry(it) != null).put("deliveryBefore", engine.deliveryOf(it)?.name ?: JSONObject.NULL) }
        when (stage) {
            PAIR -> {
                val start = engine.startPairing() as PairingStart.WaitingClaim
                out.put("pairingId", start.pairingId).put("qrUrl", start.qrUrl.reveal()).put("redactedQr", start.qrUrl.toString())
            }
            RESTORE -> {
                val credential = (credentials.load() as CredentialLoad.Found).credential
                val secret = credential.bearer.reveal()
                out.put("fileHasRawToken", File(dir, "vehicle-credential.json").readText().contains(secret))
                    .put("toStringHasRawToken", credential.toString().contains(secret))
                    .put("tokenPrefix", secret.take(3))
                    .put("pairingStatus", room.pairingStatus(credential.pairingId, System.currentTimeMillis()) ?: JSONObject.NULL)
                    .put("poll", engine.poll().status.toString().replace(secret, "LEAK"))
            }
            CRASH_BEFORE_ROOM, POLL -> out.put("report", engine.poll().let { "${it.status} received=${it.received} warnings=${it.warnings}" }).put("inbox", engine.inbox().size)
            CRASH_BEFORE_ACK, CRASH_AFTER_ACK -> {
                engine.poll()
                out.put("decision", engine.decide(key!!, InboundDecision.ApplyVersion).toString())
            }
            DECIDE_APPLY -> {
                engine.poll()
                out.put("decision", engine.decide(key!!, InboundDecision.ApplyVersion).toString())
                out.put("conflictView", engine.inbox().firstOrNull { it.key == key }?.conflict?.toString() ?: JSONObject.NULL)
            }
            DECIDE_KEEP -> {
                out.put("conflictView", engine.inbox().firstOrNull { it.key == key }?.conflict?.toString() ?: JSONObject.NULL)
                out.put("decision", engine.decide(key!!, InboundDecision.KeepExisting).toString())
            }
            PHOTO -> {
                engine.poll()
                val ref = intent.getStringExtra(PHOTO_REF)!!
                out.put("photoBytes", engine.photoFile(ref)?.length() ?: -1).put("path", engine.photoFile(ref)?.parentFile?.name ?: JSONObject.NULL)
            }
            SWAP_HASH, SWAP_CONTENT, QUARANTINE_CHECK -> {
                out.put("warnings", engine.poll().warnings.toString())
                out.put("quarantined", engine.quarantined().contains(key))
                out.put("decision", engine.decide(key!!, InboundDecision.ApplyVersion).toString())
            }
            OFFLINE_DECIDE -> out.put("decision", engine.decide(key!!, InboundDecision.ApplyVersion).toString())
            OFFLINE -> out.put("report", engine.poll().status.toString())
            REVOKE -> out.put("revoke", engine.revoke().toString())
            AFTER_REVOKE -> {
                out.put("credential", credentials.load().javaClass.simpleName).put("poll", engine.poll().status.toString())
                AndroidKeystoreCipher(ALIAS).destroy()
            }
        }
        key?.let { out.put("delivery", engine.deliveryOf(it)?.name ?: JSONObject.NULL) }
        out.put("unsentAfter", room.unsentAcks().size).put("pendingAfter", room.pending().size)
        (credentials.load() as? CredentialLoad.Found)?.credential?.let {
            out.put("credentialClaimed", it.claimed).put("credentialExpiresAtMs", it.credentialExpiresAtMs)
                .put("roomStatus", room.pairingStatus(it.pairingId, System.currentTimeMillis()) ?: JSONObject.NULL)
        }
        store.close()
    }

    companion object {
        const val STAGE = "stage"; const val RELAY = "relay"; const val PHONE = "phone"; const val DRAFT = "draft"
        const val PAIRING_ID = "pairingId"; const val PHOTO_REF = "photoRef"; const val RESULTS = "sync-it-results"
        const val DB = "sync-it.db"; const val DIR = "sync-it"; const val ALIAS = "cargo.sync.it"
        const val PAIR = "pair"; const val RESTORE = "restore"; const val CRASH_BEFORE_ROOM = "crash-before-room"; const val POLL = "poll"
        const val CRASH_BEFORE_ACK = "crash-before-ack"; const val CRASH_AFTER_ACK = "crash-after-ack"
        const val DECIDE_APPLY = "decide-apply"; const val DECIDE_KEEP = "decide-keep"; const val PHOTO = "photo"
        const val OFFLINE = "offline"; const val OFFLINE_DECIDE = "offline-decide"; const val SWAP_HASH = "swap-hash"; const val SWAP_CONTENT = "swap-content"; const val QUARANTINE_CHECK = "quarantine-check"; const val REVOKE = "revoke"; const val AFTER_REVOKE = "after-revoke"
    }
}
