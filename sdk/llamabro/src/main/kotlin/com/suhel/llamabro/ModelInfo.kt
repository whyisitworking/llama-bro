package com.suhel.llamabro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * What the loaded model is, from the GGUF and llama-server's `/props` (findings F16).
 * @property templateCaps llama-server's `chat_template_caps` (boolean entries).
 * @property metadata every GGUF key as a string.
 */
public data class ModelInfo(
    public val contextSize: Int,
    public val trainedContextSize: Int,
    public val parameterCount: Long,
    public val sizeBytes: Long,
    public val description: String,
    public val chatTemplate: String?,
    public val supportsVision: Boolean,
    public val supportsAudio: Boolean,
    public val hasMtp: Boolean,
    public val templateCaps: Map<String, Boolean>,
    public val metadata: Map<String, String>,
) {
    internal companion object {
        fun parse(infoJson: String): ModelInfo {
            val o = Json.parseToJsonElement(infoJson) as JsonObject
            val props = o["props"] as? JsonObject ?: JsonObject(emptyMap())
            val modalities = props["modalities"] as? JsonObject ?: JsonObject(emptyMap())
            val metadata = (o["metadata"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
            val arch = metadata["general.architecture"]
            return ModelInfo(
                contextSize = o.getValue("n_ctx").jsonPrimitive.int,
                trainedContextSize = o.getValue("n_ctx_train").jsonPrimitive.int,
                parameterCount = o.getValue("n_params").jsonPrimitive.long,
                sizeBytes = o.getValue("size").jsonPrimitive.long,
                description = o.getValue("desc").jsonPrimitive.content,
                chatTemplate = (props["chat_template"] as? JsonPrimitive)?.content,
                supportsVision = (modalities["vision"] as? JsonPrimitive)?.booleanOrNull ?: false,
                supportsAudio = (modalities["audio"] as? JsonPrimitive)?.booleanOrNull ?: false,
                hasMtp = arch != null && (metadata["$arch.nextn_predict_layers"]?.toIntOrNull() ?: 0) > 0,
                templateCaps = (props["chat_template_caps"] as? JsonObject).orEmpty()
                    .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }.toMap(),
                metadata = metadata,
            )
        }
    }
}
