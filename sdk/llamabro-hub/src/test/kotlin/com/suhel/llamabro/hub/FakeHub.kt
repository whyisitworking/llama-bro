package com.suhel.llamabro.hub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Process fixture shared by one test class, with independent API and CDN listeners. */
internal class FakeHub(extraRepos: JsonObject = buildJsonObject {}) : Closeable {
    private val corpus: File = File.createTempFile("llamabro-hub-corpus-", ".json")
    private val stderr: File = File.createTempFile("llamabro-hub-stderr-", ".log")
    private val process: Process
    val apiUrl: String
    val cdnPort: Int

    init {
        val token = "hf_" + "a".repeat(34)
        val repos =
            buildJsonObject {
                fun repo(seed: Int, size: Int, relative: Boolean = false, gated: Boolean = false) =
                    buildJsonObject {
                        if (relative) put("redirect", "relative")
                        if (gated) put("gated_token", token)
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "model-Q4_K_M.gguf")
                                        put("size", size)
                                        put("seed", seed)
                                    },
                                )
                            },
                        )
                    }
                put("test/flat", repo(1, 1 shl 20))
                put("test/download", repo(14, 4 shl 20))
                put(
                    "test/nonlfs",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "model-Q4_K_M.gguf")
                                        put("text", "non-lfs gguf test payload")
                                        put("lfs", false)
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "test/shared",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                for ((path, seed) in listOf(
                                    "model-Q4_K_M.gguf" to 15,
                                    "model-Q8_0.gguf" to 16,
                                    "mmproj-model-F16.gguf" to 17,
                                )) {
                                    add(
                                        buildJsonObject {
                                            put("path", path)
                                            put("size", 1 shl 20)
                                            put("seed", seed)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
                put(
                    "test/progress",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "model-Q4_K_M.gguf")
                                        put("size", 6 shl 20)
                                        put("seed", 31)
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("path", "mmproj-model-Q4_K_M.gguf")
                                        put("size", 1 shl 20)
                                        put("seed", 32)
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "test/split",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                for (part in 1..2) {
                                    add(
                                        buildJsonObject {
                                            put("path", "Q3_K_M/model-Q3_K_M-0000$part-of-00002.gguf")
                                            put("size", 1 shl 16)
                                            put("seed", 40 + part)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
                put("test/relative", repo(2, 1 shl 20, relative = true))
                put("test/gated", repo(3, 1 shl 20, gated = true))
                put("test/big", repo(4, 64 shl 20))
                put(
                    "test/choice",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                for ((path, seed) in listOf(
                                    "model-Q4_K_M.gguf" to 5,
                                    "model-Q8_0.gguf" to 6,
                                    "mmproj-model-Q8_0.gguf" to 7,
                                    "dflash-model-Q8_0.gguf" to 8,
                                )) {
                                    add(
                                        buildJsonObject {
                                            put("path", path)
                                            put("size", 4096)
                                            put("seed", seed)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
                put(
                    "test/branch",
                    buildJsonObject {
                        put(
                            "branches",
                            buildJsonArray {
                                add(JsonPrimitive("feature"))
                                add(JsonPrimitive("release"))
                            },
                        )
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "model-Q4_K_M.gguf")
                                        put("size", 11)
                                        put("seed", 9)
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "test/escape",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                for (path in listOf("model-Q4_K_M.gguf", "../evil.gguf", "nested/../../bad.gguf")) {
                                    add(
                                        buildJsonObject {
                                            put("path", path)
                                            put("size", 13)
                                            put("seed", 10)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
                put(
                    "test/preset",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                for (path in listOf("model-Q4_K_M.gguf", "preset.ini")) {
                                    add(
                                        buildJsonObject {
                                            put("path", path)
                                            put("size", 17)
                                            put("seed", 11)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
                put(
                    "test/draft",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "dflash-model-Q8_0.gguf")
                                        put("size", 19)
                                        put("seed", 12)
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "test/draftfull",
                    buildJsonObject {
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("path", "small-Q8_0.gguf")
                                        put("size", 23)
                                        put("seed", 13)
                                    },
                                )
                            },
                        )
                    },
                )
                for ((name, repo) in extraRepos) put(name, repo)
            }
        corpus.writeText(buildJsonObject { put("repos", repos) }.toString())
        val script =
            requireNotNull(System.getProperty("llamabro.hub.fake")) {
                "Gradle must set llamabro.hub.fake"
            }
        process =
            ProcessBuilder("python3", script, "--corpus", corpus.path)
                .redirectError(stderr)
                .start()
        val line =
            process.inputStream.bufferedReader().readLine()
                ?: error("fake Hub exited before reporting its ports: ${stderr.readText()}")
        val ports = Json.parseToJsonElement(line).jsonObject
        apiUrl = "http://127.0.0.1:${ports.getValue("api").jsonPrimitive.int}/"
        cdnPort = ports.getValue("cdn").jsonPrimitive.int
    }

    fun control(faults: String) {
        request("/_control", "{\"faults\":$faults}")
    }

    fun log(): JsonArray = Json.parseToJsonElement(request("/_log")).let { it as JsonArray }

    fun reset() {
        request("/_reset", "{}")
    }

    private fun request(path: String, body: String? = null): String {
        val connection = URL(apiUrl + path.removePrefix("/")).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode == 200) { "fake Hub $path returned ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        corpus.delete()
        stderr.delete()
    }
}
