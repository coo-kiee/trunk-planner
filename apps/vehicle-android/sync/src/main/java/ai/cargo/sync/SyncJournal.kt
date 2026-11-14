package ai.cargo.sync

import ai.cargo.data.InboundKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** What the relay was told, as far as the vehicle knows. Delivery is only this value, never Room's ackSentAtMs. */
enum class AckDelivery { DELIVERED, UNDELIVERED_REVOKED, UNDELIVERED_EXPIRED, REJECTED }

@Serializable
data class JournalEntry(
    val pairingId: String,
    val draftId: String,
    val clientRevision: Long,
    val relayPayloadHash: String,
    val conflictReported: Boolean = false,
    val conflictAckSent: Boolean = false,
    val authoritativeItemId: String? = null,
    val authoritativeRevision: Long? = null,
    val invalidDraft: Boolean = false,
    val superseded: Boolean = false,

    /** The relay presented other content for this key. Persisted: no decision or ack may follow, even after a restart. */
    val payloadQuarantined: Boolean = false,
    val photoRef: String? = null,
    val terminal: AckDelivery? = null,
    val terminalDetail: String? = null,
)

@Serializable
private data class JournalFile(val version: Int = 1, val pendingRevokePairingId: String? = null, val entries: List<JournalEntry> = emptyList())

/**
 * Sidecar to Room for relay facts Room does not hold: the relay's payload hash and the delivery outcome. Room and this
 * file are not atomic together. Each save reaches the disk before the next step, and a crash in between converges
 * through re-listing and idempotent acks. A damaged file is set aside and rebuilt from the relay list.
 */
class SyncJournal(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    var damagedOnLoad: Boolean = false
        private set
    private var state: JournalFile = load()

    private fun load(): JournalFile {
        if (!file.exists()) return JournalFile()
        return runCatching { json.decodeFromString<JournalFile>(file.readText()) }.getOrElse {
            damagedOnLoad = true
            file.renameTo(File(file.parentFile, "${file.name}.damaged-${System.nanoTime()}"))
            JournalFile()
        }
    }

    @Synchronized fun entry(key: InboundKey) = state.entries.firstOrNull { it.keyEquals(key) }
    @Synchronized fun entries() = state.entries
    @Synchronized fun pendingRevoke() = state.pendingRevokePairingId
    @Synchronized fun setPendingRevoke(pairingId: String?) = save(state.copy(pendingRevokePairingId = pairingId))

    @Synchronized fun put(entry: JournalEntry) {
        val others = state.entries.filterNot { it.keyEquals(InboundKey(entry.pairingId, entry.draftId, entry.clientRevision)) }
        save(state.copy(entries = others + entry))
    }

    @Synchronized fun update(key: InboundKey, change: (JournalEntry) -> JournalEntry) {
        entry(key)?.let { put(change(it)) }
    }

    private fun save(next: JournalFile) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(next).toByteArray())
            out.fd.sync()
        }
        check(temp.renameTo(file)) { "Journal rename failed" }
        syncDirectory()
        state = next
    }

    private fun syncDirectory() {
        runCatching { FileChannel.open(file.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun JournalEntry.keyEquals(key: InboundKey) = pairingId == key.pairingId && draftId == key.draftId && clientRevision == key.clientRevision
}

internal fun InboundKey.toEntry(hash: String) = JournalEntry(pairingId, draftId, clientRevision, hash)
internal fun JournalEntry.key() = InboundKey(pairingId, draftId, clientRevision)
