package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.HubTestHooks
import com.suhel.llamabro.hub.internal.JvmStorage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File

/** Child process used to kill a transfer at a precise cache mutation phase. */
internal object HubChildMain {
    @JvmStatic fun main(args: Array<String>) {
        val (cachePath, endpoint, ref, phase) = args.toList()
        HubTestHooks.onPhase = { reached ->
            if (reached == phase) {
                println(reached)
                System.out.flush()
                Thread.sleep(60_000)
            }
        }
        val cache = File(cachePath)
        val config =
            HubConfig {
                this.endpoint = endpoint
                cacheDir = cache
            }
        val hub = LlamaBroHub(config, cache, HttpTransport(endpoint, null, null), JvmStorage())
        runBlocking { hub.download(HfModel(ref)).toList() }
    }
}
