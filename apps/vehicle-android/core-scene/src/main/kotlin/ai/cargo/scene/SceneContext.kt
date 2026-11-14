package ai.cargo.scene

import ai.cargo.contracts.Instance
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.ProfileVersion
import ai.cargo.geometry.GeometryConfig
import ai.cargo.geometry.PlacedInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
data class ItemRef(val itemId: String, val revision: Long)

val Instance.itemRef: ItemRef get() = ItemRef(itemId, itemRevision)

/** The immutable library versions a scene can reference, handed in by the caller so no IO happens here. */
class SceneContext(
    items: List<ItemVersion>,
    profiles: List<ProfileVersion>,
    val config: GeometryConfig = GeometryConfig.V1,
) {
    private val itemsByRef = items.associateBy { ItemRef(it.id, it.revision) }
    private val profilesByRef = profiles.associateBy { ItemRef(it.id, it.revision) }

    fun item(ref: ItemRef): ItemVersion? = itemsByRef[ref]

    fun profile(id: String, revision: Long): ProfileVersion? = profilesByRef[ItemRef(id, revision)]

    fun newestItemRevision(itemId: String): Long? =
        itemsByRef.keys.filter { it.itemId == itemId }.maxOfOrNull { it.revision }

    fun newestProfileRevision(id: String): Long? =
        profilesByRef.keys.filter { it.itemId == id }.maxOfOrNull { it.revision }

    /** Null when a referenced version is missing, which callers report rather than guessing a substitute. */
    fun placed(instances: List<Instance>): List<PlacedInput>? = instances.map { instance ->
        val item = item(instance.itemRef) ?: return null
        PlacedInput(instance.instanceId, item, instance.poseMm, instance.orientationIndex)
    }
}

/**
 * Canonical input hash: profile, poses, handling conditions, margin, engine and configuration. The locked flag is
 * left out because it changes no geometric input; lock changes are guarded by the save rules instead.
 */
object InputHash {
    fun of(instances: List<Instance>, profile: ProfileVersion, context: SceneContext): String? {
        val config = context.config
        val entries = instances.sortedBy { it.instanceId }.map { instance ->
            val item = context.item(instance.itemRef) ?: return null
            instanceInput(instance, item)
        }
        val input = buildJsonObject {
            put("engineVersion", config.engineVersion)
            put("configurationRevision", config.revision)
            put("marginMm", config.marginMm)
            put("supportRatioBasisPoints", config.supportRatioBasisPoints)
            put("profile", profileInput(profile))
            put("instances", buildJsonArray { entries.forEach { add(it) } })
        }
        return SceneJson.sha256Hex(SceneJson.canonical(input))
    }

    /** Fingerprint of what a search result depends on, so a result outlives only its own fixed items. */
    fun searchFingerprint(fixed: List<Instance>, profile: ProfileVersion, candidate: ItemRef, context: SceneContext): String? {
        val hash = of(fixed, profile, context) ?: return null
        return SceneJson.sha256Hex("$hash|${candidate.itemId}|${candidate.revision}")
    }

    private fun instanceInput(instance: Instance, item: ItemVersion): JsonElement = buildJsonObject {
        put("instanceId", instance.instanceId)
        put("pose", SceneJson.json.encodeToJsonElement(instance.poseMm))
        put("orientationIndex", instance.orientationIndex)
        put("itemId", item.id)
        put("itemRevision", item.revision)
        put("shapeKind", item.shapeKind.name)
        put("shapeProxy", SceneJson.json.encodeToJsonElement(item.shapeProxy))
        put("dimensions", SceneJson.json.encodeToJsonElement(item.measuredDimensionsMm))
        put("errorMm", item.errorMm)
        put("rotationRule", item.rotationRule.name)
        put("stackable", item.stackable)
        put("removalPriority", item.removalPriority)
        put("confirmedDimensions", item.confirmedDimensions)
    }

    private fun profileInput(profile: ProfileVersion): JsonElement = buildJsonObject {
        put("id", profile.id)
        put("revision", profile.revision)
        put("grade", profile.grade.name)
        profile.bounds?.let { put("bounds", SceneJson.json.encodeToJsonElement(it)) }
        put("obstacles", SceneJson.json.encodeToJsonElement(profile.obstacles))
        profile.opening?.let { put("opening", SceneJson.json.encodeToJsonElement(it)) }
        put("errorMm", profile.errorMm)
    }
}
