package com.suhel.llamabro.sdk.model

/**
 * Parameters for controlling token generation and sampling.
 *
 * These settings influence the "creativity" and structure of the model's response.
 *
 * @property temperature     Controls the randomness of predictions. Lower values (e.g., 0.1) 
 *                            make the output more deterministic (greedy), while higher 
 *                            values (e.g., 1.2) make it more diverse and creative.
 * @property repeatPenalty   Discourages the model from repeating the same sequence of 
 *                            tokens. 1.0 means no penalty.
 * @property presencePenalty Penalizes tokens based on whether they have already appeared 
 *                            in the generated text. Higher values encourage the model to 
 *                            talk about new topics.
 * @property minP            A threshold for sampling. Only tokens with a probability 
 *                            relative to the most likely token greater than this 
 *                            value are considered. This is often preferred over Top-P.
 * @property topP            Nucleus sampling: only the smallest set of most probable 
 *                            tokens whose cumulative probability exceeds P are considered.
 * @property topK            Only the K most likely tokens are considered for sampling.
 */
data class InferenceConfig(
    val temperature: Float = 0.8f,
    val repeatPenalty: Float = 1.0f,
    val presencePenalty: Float = 0.0f,
    val minP: Float? = 0.1f,
    val topP: Float? = null,
    val topK: Int? = null,
)
