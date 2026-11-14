package ai.cargo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/** Rows only. Rules live in repositories and core-scene. Inserts abort on conflict so immutable rows cannot be replaced. */
@Dao
interface CargoDao {
    @Upsert fun upsertItem(row: ItemEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertItemVersion(row: ItemVersionEntity)
    @Query("SELECT * FROM item WHERE id = :id") fun item(id: String): ItemEntity?
    @Query("SELECT * FROM item ORDER BY id") fun items(): List<ItemEntity>
    @Query("SELECT * FROM item_version WHERE itemId = :id AND revision = :revision") fun itemVersion(id: String, revision: Long): ItemVersionEntity?
    @Query("SELECT * FROM item_version ORDER BY itemId, revision") fun itemVersions(): List<ItemVersionEntity>

    @Upsert fun upsertProfile(row: ProfileEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertProfileVersion(row: ProfileVersionEntity)
    @Query("SELECT * FROM profile WHERE id = :id") fun profile(id: String): ProfileEntity?
    @Query("SELECT * FROM profile_version WHERE profileId = :id AND revision = :revision") fun profileVersion(id: String, revision: Long): ProfileVersionEntity?
    @Query("SELECT * FROM profile_version ORDER BY profileId, revision") fun profileVersions(): List<ProfileVersionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertScene(row: SceneEntity)
    @Query("SELECT * FROM scene ORDER BY createdAt, id") fun scenes(): List<SceneEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertRevision(row: SceneRevisionEntity)
    @Query("SELECT * FROM scene_revision WHERE sceneId = :id AND revision = :revision") fun revision(id: String, revision: Long): SceneRevisionEntity?
    @Query("SELECT * FROM scene_revision WHERE sceneId = :id ORDER BY revision DESC") fun revisionsNewestFirst(id: String): List<SceneRevisionEntity>
    @Query("SELECT MAX(revision) FROM scene_revision WHERE sceneId = :id") fun maxRevision(id: String): Long?
    @Query("SELECT COUNT(*) FROM scene_revision") fun revisionCount(): Int
    @Query("SELECT * FROM scene_head WHERE sceneId = :id") fun head(id: String): SceneHeadEntity?
    @Upsert fun upsertHead(row: SceneHeadEntity)

    @Upsert fun upsertDraft(row: EditDraftEntity)
    @Query("SELECT * FROM edit_draft WHERE sceneId = :id") fun draft(id: String): EditDraftEntity?
    @Query("DELETE FROM edit_draft WHERE sceneId = :id") fun deleteDraft(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertInbound(row: InboundDraftEntity)
    @Upsert fun upsertInbound(row: InboundDraftEntity)
    @Query("SELECT * FROM inbound_draft WHERE pairingId = :pairing AND draftId = :draft AND clientRevision = :client")
    fun inbound(pairing: String, draft: String, client: Long): InboundDraftEntity?
    @Query("SELECT * FROM inbound_draft WHERE status = 'PENDING' ORDER BY receivedAtMs, draftId") fun pendingInbound(): List<InboundDraftEntity>
    @Query("SELECT * FROM inbound_draft WHERE status != 'PENDING' AND ackSentAtMs IS NULL ORDER BY decidedAtMs, draftId") fun unsentAcks(): List<InboundDraftEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertPairing(row: PairingEntity)
    @Upsert fun upsertPairing(row: PairingEntity)
    @Query("SELECT * FROM pairing WHERE pairingId = :id") fun pairing(id: String): PairingEntity?

    @Insert fun insertQuarantine(row: QuarantineEntity)
    @Query("SELECT * FROM quarantine ORDER BY id") fun quarantined(): List<QuarantineEntity>
    @Query("SELECT COUNT(*) FROM quarantine WHERE tableName = :table AND rowKey = :key") fun quarantineCount(table: String, key: String): Int
}
