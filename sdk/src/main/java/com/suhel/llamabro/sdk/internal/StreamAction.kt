package com.suhel.llamabro.sdk.internal

/**
 * Internal actions produced by the [TokenStreamParser] during token processing.
 *
 * These actions inform the [LlamaChatSessionImpl] how to update the 
 * current completion state.
 */
internal sealed interface StreamAction {
    /** New content text was verified. */
    data class Content(val text: String) : StreamAction
    
    /** New thinking/reasoning text was verified. */
    data class Thinking(val text: String) : StreamAction
    
    /** 
     * A stop sequence (like the assistant suffix) was detected. 
     * Generation should halt.
     */
    data object Stop : StreamAction
}
