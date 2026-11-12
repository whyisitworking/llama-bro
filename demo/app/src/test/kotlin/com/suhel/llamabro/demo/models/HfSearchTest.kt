package com.suhel.llamabro.demo.models

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class HfSearchTest {
    /** What a test server saw: the path, the raw query and the Authorization header. */
    private class Seen(val path: String, val query: String?, val authorization: String?)

    private class Server(handler: (HttpExchange, Seen) -> Unit) {
        val seen = CopyOnWriteArrayList<Seen>()
        val http: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
                createContext("/") { ex ->
                    val s =
                        Seen(ex.requestURI.path, ex.requestURI.rawQuery, ex.requestHeaders.getFirst("Authorization"))
                    seen += s
                    try {
                        handler(ex, s)
                    } finally {
                        ex.close()
                    }
                }
                start()
            }
        val port get() = http.address.port

        fun stop() = http.stop(0)
    }

    private val servers = mutableListOf<Server>()

    @After fun stopServers() = servers.forEach { it.stop() }

    private fun server(handler: (HttpExchange, Seen) -> Unit) = Server(handler).also(servers::add)

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/search/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private fun HttpExchange.reply(code: Int, body: ByteArray, type: String = "application/json") {
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    private fun serving(name: String) = server { ex, _ -> ex.reply(200, fixture(name)) }

    private fun client(server: Server, token: String? = null) = HfSearch("http://127.0.0.1:${server.port}") { token }

    private fun search(server: Server, query: String, token: String? = null) =
        runBlocking { client(server, token).search(query) }

    @Test fun requestAsTheSpecSays() {
        val s = serving("empty.json")
        search(s, "qwen")
        val seen = s.seen.single()
        assertEquals("/api/models", seen.path)
        assertEquals("search=qwen&filter=gguf&sort=downloads&direction=-1&limit=40", seen.query)
    }

    @Test fun uploadsOfOneModelCollapseIntoOneRow() {
        val rows = search(serving("qwen.json"), "qwen3.5 4b")
        assertEquals(4, rows.size)
        val four = rows.single { it.groupKey == "qwen3.5-4b" }
        // unsloth 900,000 + bartowski 300,000 + lmstudio-community 41,200
        assertEquals(1_241_200L, four.downloads)
        assertEquals("unsloth/Qwen3.5-4B-GGUF", four.repo)
        assertEquals("unsloth", four.author)
        assertEquals(1, rows.count { it.repo.contains("4B", ignoreCase = true) })
        assertEquals(rows.map { it.downloads }.sortedDescending(), rows.map { it.downloads })
    }

    @Test fun representativeIsTheMostDownloadedUnlessACatalogRepoIsInTheGroup() {
        val bartowskiLeads = """[
          {"id":"bartowski/Qwen_Qwen3.5-4B-GGUF","downloads":900000,"tags":["base_model:quantized:Qwen/Qwen3.5-4B"]},
          {"id":"unsloth/Qwen3.5-4B-GGUF","downloads":300000,"tags":["base_model:quantized:Qwen/Qwen3.5-4B"]}]"""
        val rows = search(server { ex, _ -> ex.reply(200, bartowskiLeads.toByteArray()) }, "qwen")
        assertEquals("unsloth/Qwen3.5-4B-GGUF", rows.single().repo)
        val noCatalog = """[
          {"id":"a/Foo-3B-GGUF","downloads":10,"tags":[]},{"id":"b/Foo-3B-GGUF","downloads":20,"tags":[]}]"""
        val plain = search(server { ex, _ -> ex.reply(200, noCatalog.toByteArray()) }, "foo").single()
        assertEquals("b/Foo-3B-GGUF", plain.repo)
        assertEquals(30L, plain.downloads)
        assertNull(plain.catalog)
    }

    @Test fun catalogModelsCarryTheirEntry() {
        val rows = search(serving("qwen.json"), "qwen")
        val deep = rows.single { it.groupKey == "qwen3.5-4b" }
        assertEquals("Qwen3.5 4B", deep.catalog?.realName)
        assertEquals("Qwen3.5 4B", deep.displayName)
        assertEquals("Thinks things through before answering", deep.line)
        assertEquals("Qwen3.5 0.8B", rows.single { it.repo == "unsloth/Qwen3.5-0.8B-GGUF" }.catalog?.realName)
        assertNull(rows.single { it.repo == "Qwen/Qwen3-30B-A3B-GGUF" }.catalog)
        val writer = search(serving("gemma.json"), "gemma").single { it.repo == "unsloth/gemma-3-4b-it-qat-GGUF" }
        assertEquals("Gemma 3 4B", writer.catalog?.realName)
    }

    @Test fun displayNameKeepsCasingAndSpacesTheSeparators() {
        val rows = search(serving("qwen.json"), "qwen")
        assertEquals("Qwen3 30B A3B", rows.single { it.repo == "Qwen/Qwen3-30B-A3B-GGUF" }.displayName)
        val gemma = search(serving("gemma.json"), "gemma")
        assertEquals("TinyLlama 1.1B Chat v1.0", gemma.single { it.author == "TheBloke" }.displayName)
    }

    @Test fun seesImagesFromPipelineTag() {
        val rows = search(serving("gemma.json"), "gemma")

        fun sees(author: String) = rows.single { it.author == author }.sees
        assertTrue(sees("google")) // pipeline_tag image-text-to-text
        assertTrue(sees("ggml-org")) // tag multimodal
        assertTrue(sees("microsoft")) // tag vision
        assertFalse(sees("bartowski")) // text-generation
        assertFalse(sees("TheBloke"))
        assertTrue(search(serving("qwen.json"), "qwen").single { it.groupKey == "qwen3.5-4b" }.sees)
    }

    @Test fun plainLineFromParameterCount() {
        val rows = search(serving("gemma.json"), "gemma")

        fun line(author: String) = rows.single { it.author == author }.line
        assertEquals("Tiny and quick, for everyday questions", line("TheBloke")) // 1.1B
        assertEquals("Balanced, a good all-rounder", line("microsoft")) // 3.8B
        assertEquals("Smarter, but heavy for a phone", line("bartowski")) // 7B
        assertEquals("A model from ggml-org", line("ggml-org")) // 500M: no B
        val qwen = search(serving("qwen.json"), "qwen")
        assertEquals("Needs a computer, not a phone", qwen.single { it.author == "Qwen" }.line) // 30B
        assertEquals("Smarter, but heavy for this phone", qwen.single { it.repo == "unsloth/Qwen3.5-9B-GGUF" }.line)
    }

    @Test fun boundariesOfThePlainLine() {
        assertEquals("Tiny and quick, for everyday questions", HfSearch.plainLine("Foo-1.4B", "x"))
        assertEquals("Balanced, a good all-rounder", HfSearch.plainLine("Foo-1.5B", "x"))
        assertEquals("Balanced, a good all-rounder", HfSearch.plainLine("Foo-4.9b", "x"))
        assertEquals("Smarter, but heavy for a phone", HfSearch.plainLine("Foo-5B", "x"))
        assertEquals("Needs a computer, not a phone", HfSearch.plainLine("Foo-10B", "x"))
        assertEquals("Needs a computer, not a phone", HfSearch.plainLine("Qwen3-30B-A3B", "x"))
        assertEquals("A model from x", HfSearch.plainLine("Foo", "x"))
    }

    @Test fun tokenOnlyWhenSet() {
        val s = serving("empty.json")
        search(s, "qwen", token = null)
        assertNull(s.seen.single().authorization)
        search(s, "qwen", token = "hf_test_token")
        assertEquals("Bearer hf_test_token", s.seen.last().authorization)
    }

    @Test fun tokenIsSentOnlyToHuggingFace() {
        // 127.0.0.1 and localhost are different hosts: the token must not follow the redirect across them
        val other = serving("qwen.json")
        val origin =
            server { ex, _ ->
                ex.responseHeaders.add("Location", "http://localhost:${other.port}/api/models?search=qwen")
                ex.sendResponseHeaders(302, -1)
            }
        val rows = search(origin, "qwen", token = "hf_test_token")
        assertEquals("Bearer hf_test_token", origin.seen.single().authorization)
        assertEquals(1, other.seen.size)
        assertNull("the token leaked to another host", other.seen.single().authorization)
        assertEquals(4, rows.size)
    }

    @Test fun tokenFollowsARedirectOnTheSameHost() {
        val origin =
            server { ex, s ->
                if (s.path == "/api/models") {
                    ex.responseHeaders.add("Location", "/api/models/moved?search=qwen")
                    ex.sendResponseHeaders(301, -1)
                } else {
                    ex.reply(200, fixture("empty.json"))
                }
            }
        search(origin, "qwen", token = "hf_test_token")
        assertEquals(listOf("/api/models", "/api/models/moved"), origin.seen.map { it.path })
        assertEquals("Bearer hf_test_token", origin.seen.last().authorization)
    }

    @Test fun redirectLoopsAreSearchFailed() {
        val origin =
            server { ex, _ ->
                ex.responseHeaders.add("Location", "/api/models")
                ex.sendResponseHeaders(302, -1)
            }
        val e = failure { search(origin, "qwen") }
        assertFalse(e.retryable)
    }

    @Test fun httpErrorBecomesSearchFailed() {
        val e = failure { search(server { ex, _ -> ex.reply(500, "oops".toByteArray(), "text/plain") }, "qwen") }
        assertTrue(e.retryable)
        val unauthorized = failure { search(server { ex, _ -> ex.reply(401, ByteArray(0)) }, "qwen") }
        assertFalse(unauthorized.retryable)
        val limited = failure { search(server { ex, _ -> ex.reply(429, ByteArray(0)) }, "qwen") }
        assertTrue(limited.retryable)
    }

    @Test fun unreachableServerIsRetryableSearchFailed() {
        val dead = Server { _, _ -> }.also { it.stop() }
        val e = failure { search(dead, "qwen") }
        assertTrue(e.retryable)
    }

    @Test fun malformedJsonIsSearchFailed() {
        failure { search(server { ex, _ -> ex.reply(200, "{not json".toByteArray()) }, "qwen") }
        failure { search(server { ex, _ -> ex.reply(200, """{"error":"x"}""".toByteArray()) }, "qwen") }
    }

    @Test fun emptyResultsAreEmptyNotError() {
        assertEquals(emptyList<SearchModel>(), search(serving("empty.json"), "zzzzzz"))
    }

    @Test fun itemsWithoutAnIdAreSkippedAndMissingFieldsDefault() {
        val body = """[{"downloads":5},{"id":"a/Foo-GGUF"}]"""
        val row = search(server { ex, _ -> ex.reply(200, body.toByteArray()) }, "foo").single()
        assertEquals(0L, row.downloads)
        assertFalse(row.sees)
        assertEquals("Foo", row.displayName)
    }

    @Test fun queryIsUrlEncoded() {
        val s = serving("empty.json")
        search(s, "a b+c/d é")
        assertEquals(
            "search=a+b%2Bc%2Fd+%C3%A9&filter=gguf&sort=downloads&direction=-1&limit=40",
            s.seen.single().query,
        )
    }

    @Test fun filesListsEveryGgufExceptTheProjector() {
        val s = server { ex, _ -> ex.reply(200, fixture("tree-qwen3.5-4b.json")) }
        val files = runBlocking { client(s).files("unsloth/Qwen3.5-4B-GGUF") }
        assertEquals("/api/models/unsloth/Qwen3.5-4B-GGUF/tree/main", s.seen.single().path)
        assertEquals("recursive=true", s.seen.single().query)
        assertEquals(22, files.size)
        assertFalse(files.any { it.path.contains("mmproj") })
        val q4 = files.single { it.quant == "Q4_K_M" }
        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", q4.path)
        assertEquals(2_740_937_888L, q4.bytes)
        assertEquals(
            setOf("UD-Q4_K_XL", "BF16", "IQ4_NL", "Q8_0"),
            files
                .map { it.quant }
                .filter {
                    it in setOf("UD-Q4_K_XL", "BF16", "IQ4_NL", "Q8_0")
                }.toSet(),
        )
        val tiers = Sizes.tiers(files)
        assertEquals("Q4_K_M", tiers.getValue(SizeTier.Recommended).quant)
        assertEquals("Q8_0", tiers.getValue(SizeTier.Best).quant)
    }

    @Test fun splitFilesAreOneEntryWithTheSummedSize() {
        val body = """[
          {"type":"file","path":"BF16/m-BF16-00002-of-00002.gguf","size":300},
          {"type":"file","path":"BF16/m-BF16-00001-of-00002.gguf","size":500},
          {"type":"directory","path":"BF16"},
          {"type":"file","path":"m-Q4_K_M.gguf","size":100}]"""
        val files = runBlocking { client(server { ex, _ -> ex.reply(200, body.toByteArray()) }).files("a/m") }
        val bf16 = files.single { it.quant == "BF16" }
        assertEquals("BF16/m-BF16-00001-of-00002.gguf", bf16.path)
        assertEquals(800L, bf16.bytes)
        assertEquals(2, files.size)
    }

    @Test fun filesOfAMissingRepoIsSearchFailed() {
        failure { runBlocking { client(server { ex, _ -> ex.reply(404, ByteArray(0)) }).files("a/missing") } }
    }

    @Test fun tokenGoesToTheTreeCallToo() {
        val s = server { ex, _ -> ex.reply(200, "[]".toByteArray()) }
        runBlocking { client(s, "hf_test_token").files("a/b") }
        assertEquals("Bearer hf_test_token", s.seen.single().authorization)
    }

    private fun failure(block: () -> Unit): SearchFailed {
        try {
            block()
        } catch (e: SearchFailed) {
            assertNotNull(e.message)
            return e
        }
        fail("expected SearchFailed")
        throw AssertionError()
    }
}
