package com.suhel.llamabro

/**
 * What a load does when the memory check says the model may not fit ([ModelConfig.memoryCheck],
 * [F128](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f128)).
 *
 * The check compares llama.cpp's projection of the model with the memory Android reports as available. It can't see
 * the memory Android frees by closing cached apps when the app in front needs it, so on a phone with background apps it
 * says "doesn't fit" for models that load and run
 * ([F128](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f128)). Hence the default is
 * to warn.
 */
public enum class MemoryCheck {
    /**
     * The default. When even the smallest context doesn't fit, the model loads anyway at the smallest context (4096
     * tokens, or [ContextSize.UpTo]'s ceiling if smaller) and [LoadReport.memoryWarning] says by how much it was short.
     * Android may close background apps to make room, as it does for any app in front. The app decides what to tell the
     * person.
     */
    Warn,

    /**
     * Fail the load with `LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory` instead: for an app that would
     * rather not ask Android for more than it reports free.
     */
    Refuse,
}
