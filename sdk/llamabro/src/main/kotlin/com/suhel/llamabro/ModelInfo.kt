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
public class ModelInfo internal constructor(
    /** The context this model was loaded with (`n_ctx`). */
    public val contextSize: Int,
    /** The context the model was trained with (`n_ctx_train`). */
    public val trainedContextSize: Int,
    public val parameterCount: Long,
    /** The weights' size in bytes. */
    public val sizeBytes: Long,
    /** llama.cpp's one-line description, e.g. "qwen35 4B Q4_K - Medium". */
    public val description: String,
    /** The Jinja chat template in use, null if none. */
    public val chatTemplate: String?,
    /** Images are accepted (a projector is loaded). */
    public val supportsVision: Boolean,
    /** Audio input is accepted. */
    public val supportsAudio: Boolean,
    /** The GGUF has a multi-token-prediction head ([Speculative.Mtp]). */
    public val hasMtp: Boolean,
    public val templateCaps: Map<String, Boolean>,
    public val metadata: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean = other is ModelInfo && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "ModelInfo(contextSize=$contextSize, trainedContextSize=$trainedContextSize, parameterCount=$parameterCount, " +
            "sizeBytes=$sizeBytes, description=$description, supportsVision=$supportsVision, " +
            "supportsAudio=$supportsAudio, " +
            "hasMtp=$hasMtp, templateCaps=$templateCaps, metadata=${metadata.size} keys)"

    private fun parts() =
        listOf(
            contextSize,
            trainedContextSize,
            parameterCount,
            sizeBytes,
            description,
            chatTemplate,
            supportsVision,
            supportsAudio,
            hasMtp,
            templateCaps,
            metadata,
        )

    internal companion object {
        fun parse(infoJson: String): ModelInfo {
            val o = Json.parseToJsonElement(infoJson) as JsonObject
            val props = o["props"] as? JsonObject ?: JsonObject(emptyMap())
            val modalities = props["modalities"] as? JsonObject ?: JsonObject(emptyMap())
            val metadata =
                (o["metadata"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.content
                        ?: v.toString()
                }
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
                templateCaps =
                    (props["chat_template_caps"] as? JsonObject)
                        .orEmpty()
                        .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }
                        .toMap(),
                metadata = metadata,
            )
        }
    }
}
