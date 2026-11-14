package ai.cargo.data

import ai.cargo.contracts.ContractCodec
import ai.cargo.contracts.SceneRevision
import ai.cargo.data.db.CargoDao
import ai.cargo.data.db.CargoDatabase
import ai.cargo.data.db.SceneEntity
import ai.cargo.data.db.SceneHeadEntity
import ai.cargo.data.db.SceneRevisionEntity
import ai.cargo.scene.JudgementIssue
import ai.cargo.scene.LegacyUpcast
import ai.cargo.scene.LockedDiff
import ai.cargo.scene.PreparedSave
import ai.cargo.scene.SceneChecksum
import ai.cargo.scene.SceneJson
import kotlinx.serialization.builtins.ListSerializer

sealed interface SceneLoad {
    data object NoScene : SceneLoad

    data class Healthy(val revision: SceneRevision, val acknowledged: List<JudgementIssue>) : SceneLoad

    /** The head failed verification. [revision] is the newest good one; the scene stays read-only until adopted. */
    data class RecoveredReadOnly(val revision: SceneRevision, val damagedRevision: Long) : SceneLoad

    /** A stored schema 0 scene viewed through a read-time upcast; never rewritten. */
    data class LegacyView(val revisionNumber: Long, val upcast: LegacyUpcast.Result) : SceneLoad

    data class NoValidRevision(val damagedRevision: Long) : SceneLoad
}

sealed interface SaveResult {
    data class Saved(val revision: Long) : SaveResult

    /** Compare-and-set failed. Nothing was written. */
    data class HeadMoved(val expected: Long?, val actual: Long?) : SaveResult

    data class LockedDiffRefused(val instanceIds: List<String>) : SaveResult
    data class HeadDamaged(val damagedRevision: Long) : SaveResult
    data class Invalid(val cause: String) : SaveResult
}

/** Test seam placed at the points where a crash matters. The product passes [NONE]. */
fun interface SaveFaultHook {
    fun reached(point: SavePoint)

    companion object { val NONE = SaveFaultHook { } }
}

enum class SavePoint { TRANSACTION_STARTED, REVISION_INSERTED, HEAD_UPDATED, DRAFT_REPLACED, COMMITTED }

class SceneRepository internal constructor(
    private val db: CargoDatabase,
    private val dao: CargoDao,
    private val quarantine: Quarantine,
) {
    fun sceneIds(): List<String> = dao.scenes().map { it.id }

    fun head(sceneId: String): Long? = dao.head(sceneId)?.headRevision

    fun load(sceneId: String, at: String): SceneLoad {
        val head = dao.head(sceneId)?.headRevision ?: return SceneLoad.NoScene
        val row = dao.revision(sceneId, head)
        if (row != null && LegacyUpcast.schemaVersion(row.json) == 0L && verify(row)) {
            return SceneLoad.LegacyView(head, LegacyUpcast.upcast(row.json, row.frameProvenance))
        }
        decode(row)?.let { return SceneLoad.Healthy(it, acknowledged(row!!)) }
        if (row != null) quarantine.record("scene_revision", "$sceneId/$head", row.json, row.checksum, "CHECKSUM_OR_SCHEMA_FAILED", at)
        val previous = dao.revisionsNewestFirst(sceneId).firstOrNull { it.revision < head && decode(it) != null }
        return previous?.let { SceneLoad.RecoveredReadOnly(decode(it)!!, head) } ?: SceneLoad.NoValidRevision(head)
    }

    /** Immutable historic revision; null when absent or failing verification. */
    fun revision(sceneId: String, revision: Long): SceneRevision? = decode(dao.revision(sceneId, revision))

    /**
     * One transaction: compare-and-set the head, repeat the locked diff against the stored head, insert the
     * immutable revision, move the head, replace the draft. A crash leaves the head on exactly the old or new one.
     */
    fun save(save: PreparedSave, faults: SaveFaultHook = SaveFaultHook.NONE): SaveResult {
        val result = db.runInTransaction<SaveResult> {
            faults.reached(SavePoint.TRANSACTION_STARTED)
            val revision = save.revision
            val head = dao.head(revision.id)?.headRevision
            if (head != save.expectedHead) return@runInTransaction SaveResult.HeadMoved(save.expectedHead, head)
            guard(save, head)?.let { return@runInTransaction it }
            val json = SceneJson.encodeRevision(revision)
            dao.insertScene(SceneEntity(revision.id, revision.profileId, revision.createdAt))
            dao.insertRevision(
                SceneRevisionEntity(
                    sceneId = revision.id,
                    revision = revision.revision,
                    parentRevision = revision.parentRevision,
                    json = json,
                    checksum = revision.checksum,
                    canonicalInputHash = revision.canonicalInputHash,
                    acknowledgedIssuesJson = encodeIssues(save.acknowledged),
                    frameProvenance = null,
                ),
            )
            faults.reached(SavePoint.REVISION_INSERTED)
            dao.upsertHead(SceneHeadEntity(revision.id, revision.revision))
            faults.reached(SavePoint.HEAD_UPDATED)
            dao.upsertDraft(encodeDraft(save.nextDraft, revision.updatedAt))
            faults.reached(SavePoint.DRAFT_REPLACED)
            SaveResult.Saved(revision.revision)
        }
        if (result is SaveResult.Saved) faults.reached(SavePoint.COMMITTED)
        return result
    }

    /**
     * Explicit recovery: a new head carrying the content of a verified earlier revision. All damaged and earlier
     * rows stay untouched. Revision numbers only ever grow.
     */
    fun adoptRecovery(sceneId: String, goodRevision: Long, at: String): SaveResult = db.runInTransaction<SaveResult> {
        val good = decode(dao.revision(sceneId, goodRevision))
            ?: return@runInTransaction SaveResult.Invalid("Chosen revision does not verify")
        val next = (dao.maxRevision(sceneId) ?: goodRevision) + 1
        val unsigned = good.copy(revision = next, parentRevision = goodRevision, updatedAt = at)
        val adopted = unsigned.copy(checksum = SceneChecksum.of(unsigned))
        dao.insertRevision(
            SceneRevisionEntity(
                sceneId, next, goodRevision, SceneJson.encodeRevision(adopted), adopted.checksum,
                adopted.canonicalInputHash, acknowledgedJson(sceneId, goodRevision), null,
            ),
        )
        dao.upsertHead(SceneHeadEntity(sceneId, next))
        dao.deleteDraft(sceneId)
        SaveResult.Saved(next)
    }

    private fun guard(save: PreparedSave, head: Long?): SaveResult? {
        val revision = save.revision
        if (revision.revision != (head ?: 0) + 1 || revision.parentRevision != head) {
            return SaveResult.Invalid("Revision number does not follow the head")
        }
        if (!SceneChecksum.verify(SceneJson.encodeRevision(revision))) return SaveResult.Invalid("Checksum mismatch")
        runCatching { ContractCodec.decodeScene(SceneJson.encodeRevision(revision)) }
            .onFailure { return SaveResult.Invalid("Schema violation") }
        val missing = revision.instances.any { dao.itemVersion(it.itemId, it.itemRevision) == null }
        if (missing || dao.profileVersion(revision.profileId, revision.profileRevision) == null) {
            return SaveResult.Invalid("Revision references a version that is not stored")
        }
        if (head == null) return null
        val previous = decode(dao.revision(revision.id, head)) ?: return SaveResult.HeadDamaged(head)
        val locked = LockedDiff.violations(previous.instances, revision.instances, save.lockedChanges, save.unlockedIds)
        return if (locked.isEmpty()) null else SaveResult.LockedDiffRefused(locked)
    }

    private fun verify(row: SceneRevisionEntity): Boolean = row.checksum == storedChecksum(row.json) && SceneChecksum.verify(row.json)

    private fun storedChecksum(json: String): String? = runCatching {
        (SceneJson.json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)["checksum"]
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }.getOrNull()

    private fun decode(row: SceneRevisionEntity?): SceneRevision? {
        if (row == null || !verify(row)) return null
        return runCatching { ContractCodec.decodeScene(row.json) }.getOrNull()
            ?.takeIf { it.revision == row.revision && it.id == row.sceneId }
    }

    private fun acknowledged(row: SceneRevisionEntity): List<JudgementIssue> =
        SceneJson.json.decodeFromString(ListSerializer(JudgementIssue.serializer()), row.acknowledgedIssuesJson)

    private fun acknowledgedJson(sceneId: String, revision: Long): String =
        dao.revision(sceneId, revision)?.acknowledgedIssuesJson ?: "[]"

    private fun encodeIssues(issues: List<JudgementIssue>): String =
        SceneJson.json.encodeToString(ListSerializer(JudgementIssue.serializer()), issues)
}
