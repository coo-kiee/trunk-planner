package ai.cargo.sync

import ai.cargo.contracts.ItemDraft
import ai.cargo.data.AcceptResult
import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundKey
import ai.cargo.data.InboundRepository
import ai.cargo.data.ReceiveResult

data class UnsentAck(val key: InboundKey, val status: String, val itemId: String?, val itemRevision: Long?)

/** What the engine may do with storage. Scenes, the library and drafts are not reachable through this seam. */
interface InboundGateway {
    fun createPairing(pairingId: String, tokenHash: String, nowMs: Long, expiresAtMs: Long)
    fun revokePairing(pairingId: String, nowMs: Long)
    fun pairingStatus(pairingId: String, nowMs: Long): String?
    fun receive(draft: ItemDraft, nowMs: Long): ReceiveResult
    fun pending(): List<ItemDraft>
    fun decide(key: InboundKey, decision: InboundDecision, nowMs: Long, at: String): AcceptResult
    fun unsentAcks(): List<UnsentAck>
    fun markAckSent(key: InboundKey, nowMs: Long)
}

class RoomInboundAdapter(private val repository: InboundRepository) : InboundGateway {
    override fun createPairing(pairingId: String, tokenHash: String, nowMs: Long, expiresAtMs: Long) = repository.createPairing(pairingId, tokenHash, nowMs, expiresAtMs)
    override fun revokePairing(pairingId: String, nowMs: Long) = repository.revokePairing(pairingId, nowMs)
    override fun pairingStatus(pairingId: String, nowMs: Long) = repository.pairingStatus(pairingId, nowMs)
    override fun receive(draft: ItemDraft, nowMs: Long) = repository.receive(draft, nowMs)
    override fun pending() = repository.pending()

    /**
     * Observed on real Room: a draft with no base for an item that does not exist yet comes back from ApplyVersion as
     * ChoiceRequired(null, 0), although nothing can conflict. :data is verified and owned elsewhere, so the defect is
     * reported to its owner and bridged here with the storage call that writes exactly that first version.
     */
    override fun decide(key: InboundKey, decision: InboundDecision, nowMs: Long, at: String): AcceptResult {
        val result = repository.decide(key, decision, nowMs, at)
        val isFirstVersion = decision == InboundDecision.ApplyVersion && result is AcceptResult.ChoiceRequired && result.baseRevision == null && result.currentRevision == 0L
        if (!isFirstVersion) return result
        val draft = repository.pending().first { it.pairingId == key.pairingId && it.draftId == key.draftId && it.clientRevision == key.clientRevision }
        return repository.decide(key, InboundDecision.SeparateItem(draft.payload.id), nowMs, at)
    }

    override fun unsentAcks() = repository.unsentAcks().map { UnsentAck(InboundKey(it.pairingId, it.draftId, it.clientRevision), it.status, it.acceptedItemId, it.acceptedItemRevision) }
    override fun markAckSent(key: InboundKey, nowMs: Long) = repository.markAckSent(key, nowMs)
}
