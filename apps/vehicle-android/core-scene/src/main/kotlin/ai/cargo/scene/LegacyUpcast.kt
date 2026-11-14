package ai.cargo.scene

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Read-time view of a schema 0 scene, whose y was depth and z was height. The stored JSON is never rewritten.
 * Its origin and handedness cannot be inferred, so without explicit frame provenance the scene stays read-only.
 */
object LegacyUpcast {
    /** Provenance string for a stored legacy frame whose origin matches the current frame apart from the y/z swap. */
    const val FRAME_OPENING_RIGHT_V0 = "opening-floor-vehicle-right-v0"

    data class LegacyPose(val instanceId: String, val x: Long, val y: Long, val z: Long)

    sealed interface Result {
        /** Stale by construction: the view must be revalidated before anything reads as judged. */
        data class Upcast(val poses: List<LegacyPose>, val staleReasons: List<String>) : Result

        data class ReadOnly(val cause: String) : Result
    }

    fun schemaVersion(json: String): Long? = runCatching {
        SceneJson.json.parseToJsonElement(json).jsonObject["schemaVersion"]?.jsonPrimitive?.longOrNull
    }.getOrNull()

    fun upcast(json: String, frameProvenance: String?): Result {
        val root = runCatching { SceneJson.json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return Result.ReadOnly("LEGACY_JSON_UNREADABLE")
        if (root["schemaVersion"]?.jsonPrimitive?.longOrNull != 0L) return Result.ReadOnly("NOT_LEGACY_SCHEMA")
        if (frameProvenance != FRAME_OPENING_RIGHT_V0) return Result.ReadOnly("FRAME_PROVENANCE_MISSING")
        val poses = runCatching { root.getValue("instances").jsonArray.map(::pose) }.getOrNull()
            ?: return Result.ReadOnly("LEGACY_JSON_UNREADABLE")
        return Result.Upcast(poses, listOf(StaleReason.LEGACY_AXIS_UPCAST))
    }

    private fun pose(element: JsonElement): LegacyPose {
        val fields: JsonObject = element.jsonObject
        val old = fields.getValue("poseMm").jsonObject
        return LegacyPose(
            instanceId = fields.getValue("instanceId").jsonPrimitive.content,
            x = old.getValue("x").jsonPrimitive.long,
            y = old.getValue("z").jsonPrimitive.long,
            z = old.getValue("y").jsonPrimitive.long,
        )
    }
}
