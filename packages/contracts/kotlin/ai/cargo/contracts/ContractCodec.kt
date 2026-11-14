package ai.cargo.contracts

import kotlinx.serialization.json.*

/** Trust boundary: validate the generated schema before constructing typed DTOs. */
object ContractCodec {
    private val json = Json { explicitNulls = false; encodeDefaults = true }
    private val schema = json.parseToJsonElement(schemaSource).jsonObject
    private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun decode(text: String): Envelope {
        val value = normalizeNumbers(json.parseToJsonElement(text))
        require(matches(schema, value)) { "Contract schema violation" }
        return json.decodeFromJsonElement<Envelope>(value)
    }

    fun encode(value: Envelope): String {
        val text = json.encodeToString(Envelope.serializer(), value)
        require(matches(schema, json.parseToJsonElement(text))) { "Invalid outgoing contract" }
        return text
    }

    fun validateDefinition(name: String, text: String): JsonElement {
        val value = normalizeNumbers(json.parseToJsonElement(text))
        val rule = schema["\$defs"]!!.jsonObject[name] ?: error("Unknown definition $name")
        require(matches(rule.jsonObject, value)) { "Invalid $name" }
        return value
    }
    fun decodeItem(text: String): ItemVersion = json.decodeFromJsonElement(validateDefinition("ItemVersion", text))
    fun decodeProfile(text: String): ProfileVersion = json.decodeFromJsonElement(validateDefinition("ProfileVersion", text))
    fun decodeScene(text: String): SceneRevision = json.decodeFromJsonElement(validateDefinition("SceneRevision", text))
    fun decodeDraft(text: String): ItemDraft = json.decodeFromJsonElement(validateDefinition("ItemDraft", text))

    /** AJV parses JSON numbers as IEEE-754. Normalize only finite safe integers,
     * so 120.0 / 1.2e2 decode to Long while fractional and out-of-range values fail.
     * Strings remain strings; normalization also gives numeric const its schema meaning. */
    private fun normalizeNumbers(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { normalizeNumbers(it.value) })
        is JsonArray -> JsonArray(value.map { normalizeNumbers(it) })
        is JsonPrimitive -> {
            val token = value.content
            val isBoolean = token == "true" || token == "false"
            val isNull = value === JsonNull
            if (!value.isString && !isBoolean && !isNull) {
                require(jsonNumber.matches(token)) { "Invalid JSON number token" }
            }
            val number = if (value.isString || isBoolean || isNull) null else value.doubleOrNull
            if (number != null && number.isFinite() && number % 1.0 == 0.0 &&
                kotlin.math.abs(number) <= 9007199254740991.0) JsonPrimitive(number.toLong()) else value
        }
    }

    private fun matches(rule: JsonObject, value: JsonElement): Boolean {
        rule["\$ref"]?.let {
            return matches(schema["\$defs"]!!.jsonObject[it.jsonPrimitive.content.substringAfterLast('/')]!!.jsonObject, value)
        }
        rule["oneOf"]?.let { if (it.jsonArray.count { r -> matches(r.jsonObject, value) } != 1) return false }
        rule["not"]?.let { if (matches(it.jsonObject, value)) return false }
        rule["const"]?.let { if (it != value) return false }
        rule["enum"]?.let { if (value !in it.jsonArray) return false }
        when (rule["type"]?.jsonPrimitive?.content) {
            "object" -> if (value !is JsonObject) return false
            "array" -> if (value !is JsonArray) return false
            "string" -> if (value !is JsonPrimitive || !value.isString) return false
            "boolean" -> if (value !is JsonPrimitive || value.isString ||
                (value.content != "true" && value.content != "false")) return false
            "integer" -> {
                if (value !is JsonPrimitive || value.isString) return false
                val number = value.longOrNull ?: return false
                rule["minimum"]?.let { if (number < it.jsonPrimitive.long) return false }
                rule["maximum"]?.let { if (number > it.jsonPrimitive.long) return false }
            }
        }
        if (value is JsonObject) {
            rule["required"]?.let { if (it.jsonArray.any { k -> k.jsonPrimitive.content !in value }) return false }
            val props = rule["properties"]?.jsonObject
            if (rule["additionalProperties"] == JsonPrimitive(false) && value.keys.any { it !in (props?.keys ?: emptySet()) }) return false
            props?.forEach { (k, r) -> value[k]?.let { if (!matches(r.jsonObject, it)) return false } }
        }
        if (value is JsonArray) {
            rule["minItems"]?.let { if (value.size < it.jsonPrimitive.int) return false }
            rule["maxItems"]?.let { if (value.size > it.jsonPrimitive.int) return false }
            rule["items"]?.let { if (value.any { v -> !matches(it.jsonObject, v) }) return false }
        }
        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            val codePoints = text.codePointCount(0, text.length)
            rule["minLength"]?.let { if (codePoints < it.jsonPrimitive.int) return false }
            rule["maxLength"]?.let { if (codePoints > it.jsonPrimitive.int) return false }
            rule["pattern"]?.let {
                val pattern = it.jsonPrimitive.content
                // Current schema uses an unescaped terminal $ without multiline mode.
                // Java $ also matches before a final line terminator; ECMA requires true end.
                val ecmaPattern = if (pattern.endsWith("$")) pattern.dropLast(1) + "\\z" else pattern
                if (!Regex(ecmaPattern).containsMatchIn(text)) return false
            }
        }
        return true
    }
}
