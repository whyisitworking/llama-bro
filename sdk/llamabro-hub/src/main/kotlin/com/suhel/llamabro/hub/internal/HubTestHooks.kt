package com.suhel.llamabro.hub.internal

/** Deterministic points for process-death tests; unset in production. */
internal object HubTestHooks {
    @Volatile var onPhase: ((String) -> Unit)? = null
}
