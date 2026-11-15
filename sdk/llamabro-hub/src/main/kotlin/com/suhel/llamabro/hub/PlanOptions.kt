package com.suhel.llamabro.hub

/**
 * File roles requested while resolving a Hub model. A property added here must also be carried through
 * `llamabro-hub-work`'s `DownloadInput`, or a background download would drop it: `DownloadWorkerTest` fails until it
 * is.
 */
public class PlanOptions internal constructor() {
    /** Include the multimodal projector when one is available. */
    public var mmproj: Boolean = true

    /** Optional DFlash draft from this or another repository. */
    public var draft: DraftRequest? = null
}

/** Requests DFlash drafting, optionally from another Hub reference. */
public class DraftRequest private constructor(
    /** Repository or exact file supplying the draft; null uses the main repository. */
    public val from: HfModel?,
) {
    public companion object {
        /** Requests DFlash, with [from] selecting an alternate repository when supplied. */
        @JvmStatic
        @JvmOverloads
        public fun dflash(from: HfModel? = null): DraftRequest = DraftRequest(from)
    }
}
