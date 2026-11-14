package ai.cargo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "item")
data class ItemEntity(@PrimaryKey val id: String, val headRevision: Long)

/** Immutable: triggers refuse UPDATE and DELETE. A new item version is a new row. */
@Entity(tableName = "item_version", primaryKeys = ["itemId", "revision"])
data class ItemVersionEntity(val itemId: String, val revision: Long, val json: String, val checksum: String)

@Entity(tableName = "profile")
data class ProfileEntity(@PrimaryKey val id: String, val headRevision: Long)

@Entity(tableName = "profile_version", primaryKeys = ["profileId", "revision"])
data class ProfileVersionEntity(val profileId: String, val revision: Long, val json: String, val checksum: String)

@Entity(tableName = "scene")
data class SceneEntity(@PrimaryKey val id: String, val profileId: String, val createdAt: String)

/** Immutable revision JSON. The head moves by compare-and-set in scene_head; these rows never change. */
@Entity(tableName = "scene_revision", primaryKeys = ["sceneId", "revision"])
data class SceneRevisionEntity(
    val sceneId: String,
    val revision: Long,
    val parentRevision: Long?,
    val json: String,
    val checksum: String,
    val canonicalInputHash: String,
    @ColumnInfo(defaultValue = "'[]'") val acknowledgedIssuesJson: String,
    /** Explicit origin provenance of a stored legacy frame. Absent means the frame must not be inferred. */
    val frameProvenance: String?,
)

@Entity(tableName = "scene_head")
data class SceneHeadEntity(@PrimaryKey val sceneId: String, val headRevision: Long)

@Entity(tableName = "edit_draft")
data class EditDraftEntity(@PrimaryKey val sceneId: String, val stateJson: String, val checksum: String, val updatedAt: String)

@Entity(tableName = "inbound_draft", primaryKeys = ["pairingId", "draftId", "clientRevision"])
data class InboundDraftEntity(
    val pairingId: String,
    val draftId: String,
    val clientRevision: Long,
    val payloadHash: String,
    val payloadJson: String,
    val status: String,
    val acceptedItemId: String?,
    val acceptedItemRevision: Long?,
    val receivedAtMs: Long,
    val decidedAtMs: Long?,
    val ackSentAtMs: Long?,
)

/** Only a hash of the pairing token is ever stored. */
@Entity(tableName = "pairing")
data class PairingEntity(
    @PrimaryKey val pairingId: String,
    val tokenHash: String,
    val status: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val revokedAtMs: Long?,
)

/** A copy of what failed verification. The original row stays where it is. */
@Entity(tableName = "quarantine")
data class QuarantineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableName: String,
    val rowKey: String,
    val storedJson: String,
    val storedChecksum: String,
    val reason: String,
    val detectedAt: String,
)
