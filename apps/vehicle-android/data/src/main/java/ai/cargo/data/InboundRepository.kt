package ai.cargo.data

import ai.cargo.contracts.ContractCodec
import ai.cargo.contracts.ItemDraft
import ai.cargo.contracts.ItemVersion
import ai.cargo.data.db.CargoDao
import ai.cargo.data.db.CargoDatabase
import ai.cargo.data.db.InboundDraftEntity
import ai.cargo.data.db.ItemEntity
import ai.cargo.data.db.ItemVersionEntity
import ai.cargo.data.db.PairingEntity
import ai.cargo.scene.SceneJson

data class InboundKey(val pairingId: String, val draftId: String, val clientRevision: Long)

sealed interface ReceiveResult {
    data class Stored(val key: InboundKey) : ReceiveResult

    /** Same key and same payload: a replay. Nothing changed; the earlier status is reported. */
    data class Duplicate(val key: InboundKey, val status: String) : ReceiveResult

    /** Same key with a different payload. Nothing changed. */
    data class PayloadMismatch(val key: InboundKey) : ReceiveResult

    data class PairingInactive(val pairingId: String, val status: String) : ReceiveResult
    data class Invalid(val cause: String) : ReceiveResult
}

/** The user's decision for an inbound draft. The store never picks a version by itself. */
sealed interface InboundDecision {
    /** Add as a new library version, or as a first version when the draft has no base item. */
    data object ApplyVersion : InboundDecision

    /** Apply even though the library moved since the phone's base revision. */
    data object OverwriteAfterConflictShown : InboundDecision

    data object KeepExisting : InboundDecision
    data class SeparateItem(val newItemId: String) : InboundDecision
}

sealed interface AcceptResult {
    data class Accepted(val itemId: String, val itemRevision: Long) : AcceptResult
    data object Kept : AcceptResult

    /** The library moved since the base revision and the user has not chosen. Nothing was written. */
    data class ChoiceRequired(val baseRevision: Long?, val currentRevision: Long) : AcceptResult

    data class AlreadyDecided(val status: String, val itemId: String?, val itemRevision: Long?) : AcceptResult
    data class PairingInactive(val pairingId: String, val status: String) : AcceptResult
    data object NotFound : AcceptResult
}

object InboundStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val KEPT = "KEPT"
}

object PairingStatus {
    const val ACTIVE = "ACTIVE"
    const val REVOKED = "REVOKED"
    const val EXPIRED = "EXPIRED"
}

/** Storage boundary for pairing and phone drafts. Network belongs to the platform module. */
class InboundRepository internal constructor(private val db: CargoDatabase, private val dao: CargoDao) {
    fun createPairing(pairingId: String, tokenHash: String, nowMs: Long, expiresAtMs: Long) {
        db.runInTransaction { dao.insertPairing(PairingEntity(pairingId, tokenHash, PairingStatus.ACTIVE, nowMs, expiresAtMs, null)) }
    }

    fun revokePairing(pairingId: String, nowMs: Long) {
        db.runInTransaction {
            dao.pairing(pairingId)?.let { dao.upsertPairing(it.copy(status = PairingStatus.REVOKED, revokedAtMs = nowMs)) }
        }
    }

    fun pairingStatus(pairingId: String, nowMs: Long): String? = dao.pairing(pairingId)?.let { effectiveStatus(it, nowMs) }

    fun receive(draft: ItemDraft, nowMs: Long): ReceiveResult = db.runInTransaction<ReceiveResult> {
        val key = InboundKey(draft.pairingId, draft.draftId, draft.clientRevision)
        val payload = SceneJson.json.encodeToString(ItemDraft.serializer(), draft)
        runCatching { ContractCodec.validateDefinition("ItemDraft", payload) }
            .onFailure { return@runInTransaction ReceiveResult.Invalid("Schema violation") }
        val hash = SceneJson.sha256Hex(payload)
        val existing = dao.inbound(key.pairingId, key.draftId, key.clientRevision)
        if (existing != null) {
            return@runInTransaction if (existing.payloadHash == hash) ReceiveResult.Duplicate(key, existing.status) else ReceiveResult.PayloadMismatch(key)
        }
        val status = dao.pairing(draft.pairingId)?.let { effectiveStatus(it, nowMs) } ?: "UNKNOWN"
        if (status != PairingStatus.ACTIVE) return@runInTransaction ReceiveResult.PairingInactive(draft.pairingId, status)
        dao.insertInbound(
            InboundDraftEntity(key.pairingId, key.draftId, key.clientRevision, hash, payload, InboundStatus.PENDING, null, null, nowMs, null, null),
        )
        ReceiveResult.Stored(key)
    }

    fun pending(): List<ItemDraft> = dao.pendingInbound().map { SceneJson.json.decodeFromString(ItemDraft.serializer(), it.payloadJson) }

    /**
     * One transaction: new item version plus the ack record. Scenes are not touched: they keep their references
     * until the user explicitly applies the new version there.
     */
    fun decide(key: InboundKey, decision: InboundDecision, nowMs: Long, at: String): AcceptResult = db.runInTransaction<AcceptResult> {
        val row = dao.inbound(key.pairingId, key.draftId, key.clientRevision) ?: return@runInTransaction AcceptResult.NotFound
        if (row.status != InboundStatus.PENDING) {
            return@runInTransaction AcceptResult.AlreadyDecided(row.status, row.acceptedItemId, row.acceptedItemRevision)
        }
        val pairingStatus = dao.pairing(key.pairingId)?.let { effectiveStatus(it, nowMs) } ?: "UNKNOWN"
        if (pairingStatus != PairingStatus.ACTIVE) return@runInTransaction AcceptResult.PairingInactive(key.pairingId, pairingStatus)
        val draft = SceneJson.json.decodeFromString(ItemDraft.serializer(), row.payloadJson)
        when (decision) {
            InboundDecision.KeepExisting -> {
                dao.upsertInbound(row.copy(status = InboundStatus.KEPT, decidedAtMs = nowMs))
                AcceptResult.Kept
            }
            is InboundDecision.SeparateItem -> accept(row, draft.payload, decision.newItemId, 1, nowMs, at)
            InboundDecision.ApplyVersion, InboundDecision.OverwriteAfterConflictShown -> {
                val itemId = draft.baseItemId ?: draft.payload.id
                val current = dao.item(itemId)?.headRevision ?: 0
                val moved = current != (draft.baseItemRevision ?: 0)
                if (moved && decision != InboundDecision.OverwriteAfterConflictShown) {
                    return@runInTransaction AcceptResult.ChoiceRequired(draft.baseItemRevision, current)
                }
                accept(row, draft.payload, itemId, current + 1, nowMs, at)
            }
        }
    }

    fun unsentAcks(): List<InboundDraftEntity> = dao.unsentAcks()

    fun markAckSent(key: InboundKey, nowMs: Long) {
        db.runInTransaction {
            dao.inbound(key.pairingId, key.draftId, key.clientRevision)?.let { dao.upsertInbound(it.copy(ackSentAtMs = nowMs)) }
        }
    }

    private fun accept(row: InboundDraftEntity, payload: ItemVersion, itemId: String, revision: Long, nowMs: Long, at: String): AcceptResult {
        val version = payload.copy(id = itemId, revision = revision, updatedAt = at)
        val json = SceneJson.json.encodeToString(ItemVersion.serializer(), version)
        ContractCodec.validateDefinition("ItemVersion", json)
        dao.insertItemVersion(ItemVersionEntity(itemId, revision, json, SceneJson.sha256Hex(json)))
        dao.upsertItem(ItemEntity(itemId, revision))
        dao.upsertInbound(row.copy(status = InboundStatus.ACCEPTED, acceptedItemId = itemId, acceptedItemRevision = revision, decidedAtMs = nowMs))
        return AcceptResult.Accepted(itemId, revision)
    }

    private fun effectiveStatus(row: PairingEntity, nowMs: Long): String =
        if (row.status == PairingStatus.ACTIVE && nowMs >= row.expiresAtMs) PairingStatus.EXPIRED else row.status
}
