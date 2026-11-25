package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.spi.Response
import com.suhel.llamabro.hub.spi.Transport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

/** A scripted Hub and its GGUF builder, shared by the hub tests that read headers (D68, D155). */
internal object SearchFixtures {
    const val ENDPOINT = "https://hub.test/"

    val COMMIT = "c".repeat(40)

    fun refs(): String = """{"branches":[{"name":"main","targetCommit":"$COMMIT"}]}"""

    /** A tree listing whose LFS digests are the real SHA-256 of [files]' bytes, so a download verifies. */
    fun tree(files: List<Pair<String, ByteArray>>, sizes: Map<String, Long> = emptyMap()): String =
        files.joinToString(",", "[", "]") { (path, bytes) ->
            val size = sizes[path]?.takeIf { it > 0 } ?: bytes.size.toLong()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            """{"type":"file","path":"$path","size":$size,"oid":"${"1".repeat(40)}",""" +
                """"lfs":{"oid":"$digest","size":$size}}"""
        }

    fun info(base: String?): String {
        val tags = listOfNotNull("gguf", base?.let { "base_model:$it" }, base?.let { "base_model:quantized:$it" })
        return """{"id":"x/y","tags":[${tags.joinToString(",") { "\"$it\"" }}]}"""
    }

    /** A GGUF of metadata only, with the given keys (Float as f32, Int as i32, Boolean as bool, String as string). */
    fun gguf(metadata: Map<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()

        fun bytes(size: Int, fill: ByteBuffer.() -> Unit) =
            out.write(
                ByteBuffer
                    .allocate(size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply(fill)
                    .array(),
            )

        fun string(value: String) {
            val raw = value.toByteArray()
            bytes(8) { putLong(raw.size.toLong()) }
            out.write(raw)
        }
        out.write("GGUF".toByteArray())
        bytes(4) { putInt(3) }
        bytes(8) { putLong(0) }
        bytes(8) { putLong(metadata.size.toLong()) }
        for ((key, value) in metadata) {
            string(key)
            when (value) {
                is Float -> bytes(8) { putInt(6).putFloat(value) }
                is Int -> bytes(8) { putInt(5).putInt(value) }
                is Boolean -> bytes(5) { putInt(7).put(if (value) 1 else 0) }
                is String -> bytes(4) { putInt(8) }.also { string(value) }
                else -> error("unsupported $value")
            }
        }
        val aligned = (out.size() + 31) and -32
        out.write(ByteArray(aligned - out.size() + 64))
        return out.toByteArray()
    }

    fun hub(transport: Transport, cache: File = Files.createTempDirectory("hub-search-").toFile()): LlamaBroHub {
        val config =
            HubConfig {
                endpoint = ENDPOINT
                cacheDir = cache
            }
        return LlamaBroHub(config, cache, transport, JvmStorage())
    }

    /** Answers by exact URL and records every request; anything unscripted is a 404. */
    class ScriptedTransport : Transport {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val headerRanges: MutableList<Long?> = Collections.synchronizedList(mutableListOf())
        val unmatched: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var failWith: Exception? = null
        var attempts: Int = 0
        private val routes = mutableMapOf<String, Route>()

        private class Route(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>)

        fun json(url: String, body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) {
            routes[url] = Route(status, body.toByteArray(), headers)
        }

        fun text(repo: String, readme: String) {
            routes["$ENDPOINT$repo/resolve/main/README.md"] = Route(200, readme.toByteArray(), emptyMap())
        }

        /** The refs and tree a [com.suhel.llamabro.hub.internal.Resolver] reads; a size of 0 is filled by [gguf]. */
        fun listing(repo: String, files: List<Pair<String, Long>>) {
            json("${ENDPOINT}api/models/$repo/refs", refs())
            treeFiles[repo] = files.toMutableList()
            writeTree(repo)
        }

        fun gguf(repo: String, path: String, bytes: ByteArray) {
            routes["$ENDPOINT$repo/resolve/$COMMIT/$path"] = Route(200, bytes, emptyMap())
            contents["$repo/$path"] = bytes
            treeFiles.getValue(repo).replaceAll { if (it.first == path) path to bytes.size.toLong() else it }
            writeTree(repo)
        }

        /** A GGUF repo of one file, tagged as a quantized upload of [base] (if any), its header holding [header]. */
        fun model(repo: String, file: String, base: String?, header: Map<String, Any> = emptyMap()) {
            json("${ENDPOINT}api/models/$repo", info(base))
            listing(repo, listOf(file to 0L))
            gguf(repo, file, gguf(header))
        }

        private val treeFiles = mutableMapOf<String, MutableList<Pair<String, Long>>>()
        private val contents = mutableMapOf<String, ByteArray>()

        private fun writeTree(repo: String) {
            val files =
                treeFiles.getValue(repo).map { (path, size) ->
                    path to (contents["$repo/$path"] ?: ByteArray(0))
                }
            json(
                "${ENDPOINT}api/models/$repo/tree/$COMMIT?recursive=true",
                tree(files, sizes = treeFiles.getValue(repo).toMap()),
            )
        }

        override suspend fun open(
            method: String,
            url: String,
            headers: Map<String, String>,
            range: Long?,
            rangeEnd: Long?,
        ): Response {
            attempts++
            failWith?.let { throw it }
            requests += url
            if ("/resolve/$COMMIT/" in url) headerRanges += rangeEnd
            val route = routes[url]
            if (route == null) unmatched += url
            val answer = route ?: Route(404, """{"error":"not found"}""".toByteArray(), emptyMap())
            return Response(answer.status, answer.headers, url, ByteArrayInputStream(answer.body)) {}
        }
    }
}
