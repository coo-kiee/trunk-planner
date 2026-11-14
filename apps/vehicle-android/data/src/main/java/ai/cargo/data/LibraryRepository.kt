package ai.cargo.data

import ai.cargo.contracts.ContractCodec
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.ProfileVersion
import ai.cargo.data.db.CargoDao
import ai.cargo.data.db.CargoDatabase
import ai.cargo.data.db.ItemEntity
import ai.cargo.data.db.ItemVersionEntity
import ai.cargo.data.db.ProfileEntity
import ai.cargo.data.db.ProfileVersionEntity
import ai.cargo.geometry.GeometryConfig
import ai.cargo.scene.SceneContext
import ai.cargo.scene.SceneJson

sealed interface VersionWrite {
    data class Saved(val revision: Long) : VersionWrite

    /** Someone saved a newer version first. Nothing was written and no merge was attempted. */
    data class Conflict(val expectedRevision: Long, val attemptedRevision: Long) : VersionWrite
}

/** Item library and vehicle profiles. New versions never touch scenes; scenes keep their own references. */
class LibraryRepository internal constructor(
    private val db: CargoDatabase,
    private val dao: CargoDao,
    private val quarantine: Quarantine,
) {
    fun saveItemVersion(item: ItemVersion): VersionWrite = db.runInTransaction<VersionWrite> {
        val expected = (dao.item(item.id)?.headRevision ?: 0) + 1
        if (item.revision != expected) return@runInTransaction VersionWrite.Conflict(expected, item.revision)
        val json = SceneJson.json.encodeToString(ItemVersion.serializer(), item)
        ContractCodec.validateDefinition("ItemVersion", json)
        dao.insertItemVersion(ItemVersionEntity(item.id, item.revision, json, SceneJson.sha256Hex(json)))
        dao.upsertItem(ItemEntity(item.id, item.revision))
        VersionWrite.Saved(item.revision)
    }

    fun saveProfileVersion(profile: ProfileVersion): VersionWrite = db.runInTransaction<VersionWrite> {
        val expected = (dao.profile(profile.id)?.headRevision ?: 0) + 1
        if (profile.revision != expected) return@runInTransaction VersionWrite.Conflict(expected, profile.revision)
        val json = SceneJson.json.encodeToString(ProfileVersion.serializer(), profile)
        ContractCodec.validateDefinition("ProfileVersion", json)
        dao.insertProfileVersion(ProfileVersionEntity(profile.id, profile.revision, json, SceneJson.sha256Hex(json)))
        dao.upsertProfile(ProfileEntity(profile.id, profile.revision))
        VersionWrite.Saved(profile.revision)
    }

    fun latestItems(at: String): List<ItemVersion> = dao.items().mapNotNull { item(it.id, it.headRevision, at) }

    /** A row failing verification is copied to quarantine and reported as missing, never returned. */
    fun item(id: String, revision: Long, at: String): ItemVersion? {
        val row = dao.itemVersion(id, revision) ?: return null
        return decodeItem(row, at)
    }

    fun profile(id: String, revision: Long, at: String): ProfileVersion? {
        val row = dao.profileVersion(id, revision) ?: return null
        return decodeProfile(row, at)
    }

    /** Everything a scene can reference. Corrupt versions drop out, so a scene using one reports a missing reference. */
    fun sceneContext(at: String, config: GeometryConfig = GeometryConfig.V1) = SceneContext(
        items = dao.itemVersions().mapNotNull { decodeItem(it, at) },
        profiles = dao.profileVersions().mapNotNull { decodeProfile(it, at) },
        config = config,
    )

    private fun decodeItem(row: ItemVersionEntity, at: String): ItemVersion? =
        verified(row.json, row.checksum, "item_version", "${row.itemId}/${row.revision}", at) { ContractCodec.decodeItem(it) }

    private fun decodeProfile(row: ProfileVersionEntity, at: String): ProfileVersion? =
        verified(row.json, row.checksum, "profile_version", "${row.profileId}/${row.revision}", at) { ContractCodec.decodeProfile(it) }

    private fun <T> verified(json: String, checksum: String, table: String, key: String, at: String, decode: (String) -> T): T? {
        val value = if (SceneJson.sha256Hex(json) == checksum) runCatching { decode(json) }.getOrNull() else null
        if (value == null) quarantine.record(table, key, json, checksum, "CHECKSUM_OR_SCHEMA_FAILED", at)
        return value
    }
}
