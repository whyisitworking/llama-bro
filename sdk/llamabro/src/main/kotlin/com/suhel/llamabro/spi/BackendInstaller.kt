package com.suhel.llamabro.spi

import android.content.Context
import com.suhel.llamabro.InternalLlamaBroApi

/**
 * Implemented by each backend artifact (`llamabro-hexagon`, …), which names its class in its manifest:
 * `<meta-data android:name="com.suhel.llamabro.backend.<name>" android:value="<class>" />`
 * ([D24](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d24)). The core finds it in the merged
 * manifest and calls [install] once, on `Dispatchers.IO`, when the runtime starts. Public only for those artifacts;
 * apps never implement it.
 */
@InternalLlamaBroApi
public interface BackendInstaller {
    /**
     * Prepares the backend (Hexagon copies its DSP skeletons first) and names its libraries. Throwing marks the
     * backend failed, with the exception as the reason; it doesn't stop the runtime. The class needs a public
     * no-argument constructor (kept from R8 by the core's consumer rules).
     */
    public fun install(context: Context, options: BackendOptions): BackendLibraries
}

/** The app's settings an installer may need, from the first [com.suhel.llamabro.LlamaBroConfig]. */
@InternalLlamaBroApi
public class BackendOptions(public val npuPolling: Boolean)

/** What [BackendInstaller.install] returns: libraries loaded by file name, after [env] is set. */
@InternalLlamaBroApi
public class BackendLibraries(public val files: List<String>, public val env: Map<String, String> = emptyMap())
