package com.suhel.llamabro.internal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * JSON Merge Patch (RFC 7396). Keys keep the target's order; new keys follow it, in the patch's order, so a
 * patch that only replaces top-level values gives the same bytes as a shallow merge (F43).
 */
internal object JsonMergePatch {
    fun apply(target: JsonElement, patch: JsonElement): JsonElement {
        if (patch !is JsonObject) return patch
        val out = LinkedHashMap<String, JsonElement>()
        if (target is JsonObject) out.putAll(target)
        for ((key, value) in patch) {
            if (value is JsonNull) {
                out.remove(key)
            } else {
                out[key] = apply(out[key] ?: JsonNull, value)
            }
        }
        return JsonObject(out)
    }

    /** @throws IllegalArgumentException if [patch] isn't a JSON object or sets one of [owned]. */
    fun checkPatch(patch: String, owned: Set<String>): JsonObject {
        val parsed =
            try {
                Json.parseToJsonElement(patch)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("extra is not valid JSON: ${e.message}", e)
            }
        require(parsed is JsonObject) { "extra must be a JSON object" }
        val clash = owned.filter { it in parsed }
        require(clash.isEmpty()) { "extra may not set ${clash.joinToString()}: the typed calls own them" }
        return parsed
    }
}
