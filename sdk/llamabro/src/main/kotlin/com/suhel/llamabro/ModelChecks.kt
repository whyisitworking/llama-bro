package com.suhel.llamabro

/*
 * The checks every call makes before it asks the model anything, in one place so the same failure is reported the same
 * way whichever call meets it (D142): a model loaded for another task is the app's misuse (IllegalStateException,
 * D136); a model without the capability is NotSupported, as llama-server reports a missing capability.
 */

internal inline fun <reified T : ModelTask> Model.requireTask(call: String, loadWith: String) {
    check(task is T) { "$call needs a model loaded with task = $loadWith; this one was loaded with ModelTask.$task" }
}

/**
 * @throws LlamaBroException.NotSupported if the model doesn't hear ([ModelInfo.supportsAudio]). A clip inside a chat
 *   message stays an [LlamaBroException.InvalidRequest]: there the message's data is what the model can't take.
 */
internal fun Model.requireHears(call: String) {
    if (!info.supportsAudio) {
        throw LlamaBroException.NotSupported(
            "$call needs a model that hears, and this one doesn't (ModelInfo.supportsAudio is false): load an audio " +
                "model with its audio encoder (ModelSource.file(path) { mmproj = … })",
        )
    }
}
