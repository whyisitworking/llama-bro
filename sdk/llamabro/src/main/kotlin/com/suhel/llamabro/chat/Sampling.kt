package com.suhel.llamabro.chat

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Per-request sampling: every sampling field of llama-server's completion schema
 * (`tools/server/server-schema.cpp`, `make_llama_cmpl_schema`), under llama-server's names in camelCase.
 * Unset (null) = the GGUF's `general.sampling.*` default, else llama-server's (D18, F17).
 *
 * Build one with `Sampling { temperature = 0.2f }`; change one with [copy]. Fields not listed here (for example
 * `n_probs`) go through `ChatRequest.extra`.
 */
public class Sampling internal constructor(private val values: Map<String, Any>) {

    public val temperature: Float? get() = get("temperature")
    public val topK: Int? get() = get("top_k")
    public val topP: Float? get() = get("top_p")
    public val minP: Float? get() = get("min_p")
    public val presencePenalty: Float? get() = get("presence_penalty")
    public val frequencyPenalty: Float? get() = get("frequency_penalty")
    public val repeatPenalty: Float? get() = get("repeat_penalty")
    /** Must be ≥ 0 per request (F14); to cover the whole context, pass the context size. */
    public val repeatLastN: Int? get() = get("repeat_last_n")
    public val seed: Long? get() = get("seed")
    /** The sampler chain order, e.g. `listOf("top_k", "top_p", "min_p", "temperature")` (F15). */
    public val samplers: List<String>? get() = get("samplers")
    /** `max_tokens` (llama-server's `n_predict`); null = until the model stops or the context is full. */
    public val maxTokens: Int? get() = get("max_tokens")
    public val topNSigma: Float? get() = get("top_n_sigma")
    public val xtcProbability: Float? get() = get("xtc_probability")
    public val xtcThreshold: Float? get() = get("xtc_threshold")
    public val typicalP: Float? get() = get("typical_p")
    public val dynatempRange: Float? get() = get("dynatemp_range")
    public val dynatempExponent: Float? get() = get("dynatemp_exponent")
    public val dryMultiplier: Float? get() = get("dry_multiplier")
    public val dryBase: Float? get() = get("dry_base")
    public val dryAllowedLength: Int? get() = get("dry_allowed_length")
    public val dryPenaltyLastN: Int? get() = get("dry_penalty_last_n")
    public val drySequenceBreakers: List<String>? get() = get("dry_sequence_breakers")
    /** 0 = off, 1 = Mirostat, 2 = Mirostat 2.0. */
    public val mirostat: Int? get() = get("mirostat")
    public val mirostatTau: Float? get() = get("mirostat_tau")
    public val mirostatEta: Float? get() = get("mirostat_eta")
    public val adaptiveTarget: Float? get() = get("adaptive_target")
    public val adaptiveDecay: Float? get() = get("adaptive_decay")
    public val minKeep: Int? get() = get("min_keep")
    /**
     * llama-server's object form: each key is a token id (`"15043"`) or text, which the server tokenizes; each value
     * a bias. [Float.NEGATIVE_INFINITY] bans the token (sent as `false`).
     */
    public val logitBias: Map<String, Float>? get() = get("logit_bias")
    public val ignoreEos: Boolean? get() = get("ignore_eos")
    public val backendSampling: Boolean? get() = get("backend_sampling")

    /** A copy with [configure] applied on top of this one's values. */
    public fun copy(configure: Builder.() -> Unit): Sampling = Builder(values).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is Sampling && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String =
        FIELDS.filter { it.wire in values }.joinToString(", ", "Sampling(", ")") { "${it.kotlin}=${values[it.wire]}" }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(wire: String): T? = values[wire] as T?

    /** Mutable scope of `Sampling { }` and [copy]; null clears a field. */
    public class Builder internal constructor(initial: Map<String, Any>) {
        private val values = LinkedHashMap(initial)

        public var temperature: Float? by field("temperature")
        public var topK: Int? by field("top_k")
        public var topP: Float? by field("top_p")
        public var minP: Float? by field("min_p")
        public var presencePenalty: Float? by field("presence_penalty")
        public var frequencyPenalty: Float? by field("frequency_penalty")
        public var repeatPenalty: Float? by field("repeat_penalty")
        public var repeatLastN: Int? by field("repeat_last_n")
        public var seed: Long? by field("seed")
        public var samplers: List<String>? by field("samplers")
        public var maxTokens: Int? by field("max_tokens")
        public var topNSigma: Float? by field("top_n_sigma")
        public var xtcProbability: Float? by field("xtc_probability")
        public var xtcThreshold: Float? by field("xtc_threshold")
        public var typicalP: Float? by field("typical_p")
        public var dynatempRange: Float? by field("dynatemp_range")
        public var dynatempExponent: Float? by field("dynatemp_exponent")
        public var dryMultiplier: Float? by field("dry_multiplier")
        public var dryBase: Float? by field("dry_base")
        public var dryAllowedLength: Int? by field("dry_allowed_length")
        public var dryPenaltyLastN: Int? by field("dry_penalty_last_n")
        public var drySequenceBreakers: List<String>? by field("dry_sequence_breakers")
        public var mirostat: Int? by field("mirostat")
        public var mirostatTau: Float? by field("mirostat_tau")
        public var mirostatEta: Float? by field("mirostat_eta")
        public var adaptiveTarget: Float? by field("adaptive_target")
        public var adaptiveDecay: Float? by field("adaptive_decay")
        public var minKeep: Int? by field("min_keep")
        /** See [Sampling.logitBias]. NaN and +infinity are rejected: JSON can't carry them. */
        public var logitBias: Map<String, Float>?
            get() = @Suppress("UNCHECKED_CAST") (values["logit_bias"] as Map<String, Float>?)
            set(value) {
                value?.forEach { (k, v) -> require(!v.isNaN() && v != Float.POSITIVE_INFINITY) { "logitBias[$k] must be finite or -infinity, was $v" } }
                if (value == null) values.remove("logit_bias") else values["logit_bias"] = value.toMap()
            }
        public var ignoreEos: Boolean? by field("ignore_eos")
        public var backendSampling: Boolean? by field("backend_sampling")

        internal fun build(): Sampling = Sampling(LinkedHashMap(values))

        private fun <T : Any> field(wire: String) = object : ReadWriteProperty<Builder, T?> {
            @Suppress("UNCHECKED_CAST")
            override fun getValue(thisRef: Builder, property: KProperty<*>): T? = values[wire] as T?
            override fun setValue(thisRef: Builder, property: KProperty<*>, value: T?) {
                if (value == null) values.remove(wire) else values[wire] = if (value is List<*>) value.toList() else value
            }
        }
    }

    public companion object {
        /** No field set: every value is the model's or llama-server's default. */
        @JvmField
        public val Default: Sampling = Sampling(emptyMap())

        /** Wire order: P1's eleven first, so P1's request bodies stay byte-identical (PA T2 `bodiesMatchP1`). */
        internal val FIELDS: List<Field> = listOf(
            Field("temperature", "temperature", Kind.Float), Field("top_k", "topK", Kind.Int), Field("top_p", "topP", Kind.Float), Field("min_p", "minP", Kind.Float),
            Field("presence_penalty", "presencePenalty", Kind.Float), Field("frequency_penalty", "frequencyPenalty", Kind.Float),
            Field("repeat_penalty", "repeatPenalty", Kind.Float), Field("repeat_last_n", "repeatLastN", Kind.Int), Field("seed", "seed", Kind.Long),
            Field("samplers", "samplers", Kind.Strings), Field("max_tokens", "maxTokens", Kind.Int),
            Field("top_n_sigma", "topNSigma", Kind.Float), Field("xtc_probability", "xtcProbability", Kind.Float),
            Field("xtc_threshold", "xtcThreshold", Kind.Float), Field("typical_p", "typicalP", Kind.Float), Field("dynatemp_range", "dynatempRange", Kind.Float),
            Field("dynatemp_exponent", "dynatempExponent", Kind.Float), Field("dry_multiplier", "dryMultiplier", Kind.Float),
            Field("dry_base", "dryBase", Kind.Float), Field("dry_allowed_length", "dryAllowedLength", Kind.Int),
            Field("dry_penalty_last_n", "dryPenaltyLastN", Kind.Int), Field("dry_sequence_breakers", "drySequenceBreakers", Kind.Strings),
            Field("mirostat", "mirostat", Kind.Int), Field("mirostat_tau", "mirostatTau", Kind.Float), Field("mirostat_eta", "mirostatEta", Kind.Float),
            Field("adaptive_target", "adaptiveTarget", Kind.Float), Field("adaptive_decay", "adaptiveDecay", Kind.Float),
            Field("min_keep", "minKeep", Kind.Int), Field("logit_bias", "logitBias", Kind.Bias), Field("ignore_eos", "ignoreEos", Kind.Bool),
            Field("backend_sampling", "backendSampling", Kind.Bool),
        )

        internal val wireNames: Set<String> = FIELDS.map { it.wire }.toSet()

        /** From values already typed by [Kind] (ChatJson.parseOptions). */
        internal fun of(values: Map<String, Any>): Sampling = Sampling(LinkedHashMap(values))
    }

    internal enum class Kind { Float, Int, Long, Bool, Strings, Bias }

    internal class Field(val wire: String, val kotlin: String, val kind: Kind)

    /** The set values in wire order. */
    internal fun wireValues(): List<Pair<String, Any>> = FIELDS.mapNotNull { f -> values[f.wire]?.let { f.wire to it } }
}

/** `Sampling { temperature = 0.2f; topK = 40 }`. */
public fun Sampling(configure: Sampling.Builder.() -> Unit): Sampling = Sampling.Default.copy(configure)
