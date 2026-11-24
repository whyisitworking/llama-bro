package com.suhel.llamabro.demo.data.settings

import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DevicePolicy

/**
 * What Settings › Memory says a model loads under: the chip choice's [policy] (D72) and the context length's [context]
 * (a ceiling the SDK fits to memory), and the requests it serves at once, [slots]: two while voice mode captions what
 * the person says (D99, D100), one otherwise. A model loaded under another config is not reused, except that one loaded
 * with more slots serves a turn that needs fewer ([serves]).
 */
data class LoadConfig(
    val policy: DevicePolicy = DevicePolicy.Auto,
    val context: ContextSize = ContextSize.Auto,
    val slots: Int = 1,
) {
    /** A model loaded under this config can serve a turn that [wanted]: the same chip and context, enough slots. */
    fun serves(wanted: LoadConfig): Boolean =
        policy == wanted.policy && context == wanted.context && slots >= wanted.slots
}
