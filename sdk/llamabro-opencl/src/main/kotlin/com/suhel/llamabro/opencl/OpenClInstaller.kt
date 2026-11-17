package com.suhel.llamabro.opencl

import android.content.Context
import com.suhel.llamabro.internal.BackendInstaller
import com.suhel.llamabro.internal.BackendLibraries
import com.suhel.llamabro.internal.BackendOptions

/**
 * Called by the core when the runtime starts (BackendDiscovery, through this module's manifest). Nothing to install.
 */
internal class OpenClInstaller : BackendInstaller {
    override fun install(context: Context, options: BackendOptions): BackendLibraries =
        BackendLibraries(listOf("libllamabro-ggml-opencl.so"), ENV)

    internal companion object {
        /**
         * Set before ggml-opencl loads. Its cooperative-K DP4A Q4_K kernel gives garbage on the Adreno 830 for
         * matmuls of 2–4 rows, which llama-server sends for every model with checkpoints (Qwen3.5, Gemma 3) and for
         * speculative verification (F75). Off, the next kernel in line is correct. Drop once a pin passes F75's cases.
         */
        val ENV = mapOf("GGML_OPENCL_COOPK_DP4A" to "0")
    }
}
