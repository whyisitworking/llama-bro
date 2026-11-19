package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.spi.BackendInstaller
import com.suhel.llamabro.spi.BackendLibraries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Finds the backend artifacts on the classpath through the merged manifest's `<meta-data>` (D24) and asks each one's
 * [BackendInstaller] what to load. Nothing here is fatal: a bad entry or a failing installer becomes a failed
 * [Backend] and the others still load.
 */
internal object BackendDiscovery {
    const val META_PREFIX = "com.suhel.llamabro.backend."

    class Found(val name: String, val libraries: BackendLibraries)

    class Result(val found: List<Found>, val failed: List<Backend>) {
        /** The runtime config's `backends` (llamabro.h). */
        fun toJson(): JsonArray =
            buildJsonArray {
                for (f in found) {
                    add(
                        buildJsonObject {
                            put("name", f.name)
                            putJsonArray("files") { f.libraries.files.forEach { add(JsonPrimitive(it)) } }
                            if (f.libraries.env.isNotEmpty()) {
                                putJsonObject("env") {
                                    f.libraries.env.forEach { (k, v) ->
                                        put(k, v)
                                    }
                                }
                            }
                        },
                    )
                }
            }

        companion object {
            val None = Result(emptyList(), emptyList())
        }
    }

    /**
     * [metaData] is the application's `<meta-data>` (all of it; other keys are ignored). [install] creates the named
     * installer class and calls it.
     */
    fun discover(metaData: Map<String, Any?>, install: (className: String) -> BackendLibraries): Result {
        val found = mutableListOf<Found>()
        val failed = mutableListOf<Backend>()
        for ((key, value) in metaData.filterKeys { it.startsWith(META_PREFIX) }.toSortedMap()) {
            val name = key.removePrefix(META_PREFIX)

            fun fail(reason: String) {
                failed += Backend(name, loaded = false, error = reason, devices = emptyList())
            }
            when {
                name.isEmpty() || name == "cpu" -> {
                    fail("invalid backend name in <meta-data> $key")
                }

                value !is String || value.isBlank() -> {
                    fail("<meta-data> $key must name the backend's installer class")
                }

                else -> {
                    try {
                        val libraries = install(value)
                        // what the native parser would reject fails this backend only, not the runtime and the CPU with
                        // it
                        when {
                            libraries.files.isEmpty() -> fail("installer $value named no libraries")

                            libraries.files.any { it.isBlank() } -> fail("installer $value named a blank library file")

                            libraries.env.keys.any {
                                it.isEmpty() || '=' in it
                            } -> fail("installer $value set an invalid environment variable name")

                            else -> found += Found(name, libraries)
                        }
                    } catch (e: Throwable) {
                        if (e is VirtualMachineError) throw e
                        fail("installer $value failed: $e")
                    }
                }
            }
        }
        return Result(found, failed)
    }
}
