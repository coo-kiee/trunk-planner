package ai.cargo.scene

import ai.cargo.contracts.SceneRevision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/** Single JSON configuration and canonical form used for storage, hashes and checksums. */
object SceneJson {
    val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = false }

    /** Sorted keys, no whitespace, no nulls: the same value always gives the same bytes. */
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .filter { it.value !is JsonNull }
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> element.toString()
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun encodeRevision(revision: SceneRevision): String = json.encodeToString(SceneRevision.serializer(), revision)
}

/** Checksum over the canonical revision JSON with its own checksum field left out. */
object SceneChecksum {
    private const val FIELD = "checksum"

    fun of(revisionJson: String): String {
        val fields = SceneJson.json.parseToJsonElement(revisionJson).jsonObject
        return SceneJson.sha256Hex(SceneJson.canonical(JsonObject(fields - FIELD)))
    }

    fun of(revision: SceneRevision): String = of(SceneJson.encodeRevision(revision))

    fun verify(revisionJson: String): Boolean = runCatching {
        val stored = (SceneJson.json.parseToJsonElement(revisionJson).jsonObject[FIELD] as? JsonPrimitive)?.content
        stored != null && stored == of(revisionJson)
    }.getOrDefault(false)
}
