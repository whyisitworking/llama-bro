#pragma once

/**
 * Canonical error codes for all failure modes in the llama-bro native layer.
 * These values are stringified and passed through JNI as Java RuntimeExceptions,
 * where the Kotlin internal layer maps them to typed LlamaError subclasses.
 *
 * IMPORTANT: The integer values of these enumerators are part of the ABI between
 * C++ and Kotlin. Do NOT reorder or renumber — only append new entries.
 */
enum class LlamaErrorCode : int {
    // ── Engine ──────────────────────────────────────────────────────────────
    MODEL_NOT_FOUND     = 1,  // model file path does not exist
    MODEL_LOAD_FAILED   = 2,  // file exists but llama_model_load_from_file returned null
    BACKEND_LOAD_FAILED = 3,  // ggml_backend_load returned non-zero

    // ── Session ─────────────────────────────────────────────────────────────
    CONTEXT_INIT_FAILED = 10, // llama_init_from_model returned null
    CONTEXT_OVERFLOW    = 11, // HALT strategy: context is full, cannot recover
    DECODE_FAILED       = 12, // llama_decode returned non-zero

    // ── Catch-all ────────────────────────────────────────────────────────────
    UNKNOWN             = 99,
};
