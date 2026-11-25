package com.suhel.llamabro.server

import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only `data:` media reaches the model: llama-server reads `file://` from the media folder and fetches `http(s)`. */
class MediaGuardTest {
    private val png = "data:image/png;base64,iVBORw0KGgo="

    private fun offending(body: String) = firstNonDataMedia(Json.parseToJsonElement(body))

    /** A chat body with one user message holding [part]. */
    private fun userPart(part: String) = """{"messages":[{"role":"user","content":[$part]}]}"""

    private fun chatImage(url: String) = userPart("""{"type":"image_url","image_url":{"url":"$url"}}""")

    @Test
    fun dataUrlPasses() {
        assertNull(offending(chatImage(png)))
        // the scheme is case-insensitive, as URL schemes are
        assertNull(offending(chatImage("DATA:image/png;base64,iVBORw0KGgo=")))
    }

    @Test
    fun fileUrlRefused() {
        assertEquals("file:///chat/1/a.png", offending(chatImage("file:///chat/1/a.png")))
        assertEquals("FILE:a.png", offending(chatImage("FILE:a.png")))
    }

    @Test
    fun httpUrlRefused() = assertEquals("http://192.168.1.1/a.png", offending(chatImage("http://192.168.1.1/a.png")))

    @Test
    fun httpsUrlRefused() = assertEquals("https://example.com/a.png", offending(chatImage("https://example.com/a.png")))

    @Test
    fun anyOtherSchemeRefused() {
        listOf(
            "ftp://host/a.png",
            "FILE:a.png",
            "lb-attachment:0",
            "  http://192.168.1.1/a.png",
            " data:image/png;base64,",
            "c:/a.png",
            "iVBOR:w0KGgo=",
            // upstream downloads whatever starts with "http", so base64 that does could never be read as an image
            "http//192.168.1.1/a.png",
        ).forEach { assertEquals(it, offending(chatImage(it))) }
    }

    @Test
    fun rawBase64Passes() {
        // upstream decodes a string with no scheme as base64; with no colon it can't name a scheme
        val wav = "UklGRiQAAABXQVZFZm10IBAAAAABAAEA"
        assertNull(offending(chatImage("iVBORw0KGgo=")))
        assertNull(offending(chatImage("/9j/4AAQSkZJRg+/=")))
        assertNull(offending(userPart("""{"type":"input_audio","input_audio":{"data":"$wav","format":"wav"}}""")))
        assertNull(offending("""{"input":[{"role":"user","content":[{"type":"input_image","image_url":"$wav"}]}]}"""))
        assertNull(offending(chatImage("")))
    }

    @Test
    fun everyApiShapeIsChecked() {
        listOf("file:///chat/1/a.png", "http://192.168.1.1/a.png").forEach { everyShapeRefuses(it) }
    }

    private fun everyShapeRefuses(bad: String) {
        val shapes =
            mapOf(
                "chat image_url object" to chatImage(bad),
                "chat image_url string" to
                    userPart("""{"type":"image_url","image_url":"$bad"}"""),
                "chat input_audio data" to
                    userPart("""{"type":"input_audio","input_audio":{"data":"$bad","format":"wav"}}"""),
                "chat input_audio url" to
                    userPart("""{"type":"input_audio","input_audio":{"url":"$bad"}}"""),
                // only Anthropic's source has raw data; upstream reads input_audio's data as a URL, whatever its type
                "chat input_audio typed base64" to
                    userPart("""{"type":"input_audio","input_audio":{"type":"base64","data":"$bad"}}"""),
                "chat video_url" to
                    userPart("""{"type":"video_url","video_url":{"url":"$bad"}}"""),
                "chat input_video" to
                    userPart("""{"type":"input_video","input_video":{"data":"$bad"}}"""),
                "audio_url" to
                    userPart("""{"type":"audio_url","audio_url":{"url":"$bad"}}"""),
                "responses input_image" to
                    """{"input":[{"role":"user","content":[{"type":"input_image","image_url":"$bad"}]}]}""",
                "responses input_file url" to
                    """{"input":[{"role":"user","content":[{"type":"input_file","file_url":"$bad"}]}]}""",
                "responses input_file data" to
                    """{"input":[{"role":"user","content":[{"type":"input_file","file_data":"$bad"}]}]}""",
                "anthropic image url" to
                    userPart("""{"type":"image","source":{"type":"url","url":"$bad"}}"""),
                "anthropic document url" to
                    userPart("""{"type":"document","source":{"type":"url","url":"$bad"}}"""),
                "anthropic data with type url" to
                    userPart("""{"type":"image","source":{"type":"url","data":"$bad"}}"""),
                "rerank image" to """{"query":"q","documents":[{"text":"t","image":"$bad"}]}""",
                "rerank image list" to """{"query":{"text":"q","image":["$png","$bad"]},"documents":["d"]}""",
                "multi-turn history" to
                    """{"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"},""" +
                    """{"role":"user","content":[{"type":"text","text":"and this?"},""" +
                    """{"type":"image_url","image_url":{"url":"$png"}}]},{"role":"assistant","content":"a cat"},""" +
                    """{"role":"user","content":[{"type":"image_url","image_url":{"url":"$bad"}}]}]}""",
                "tool result" to
                    """{"messages":[{"role":"tool","tool_call_id":"1","content":[""" +
                    """{"type":"image_url","image_url":{"url":"$bad"}}]}]}""",
                "anthropic tool_result" to
                    """{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"1","content":[""" +
                    """{"type":"image","source":{"type":"url","url":"$bad"}}]}]}]}""",
                "responses nested input" to
                    """{"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"x"}]},""" +
                    """{"type":"function_call_output","call_id":"1","output":[""" +
                    """{"type":"input_image","image_url":"$bad"}]}]}""",
            )
        shapes.forEach { (shape, body) -> assertEquals(shape, bad, offending(body)) }
    }

    @Test
    fun deepNestingIsWalked() {
        val depth = 20_000
        // objects: kotlinx.serialization parses them on the heap past 200 deep, so only the walk could overflow
        val body = """{"a":""".repeat(depth) + chatImage("file:///a.png") + "}".repeat(depth)
        assertEquals("file:///a.png", offending(body))
    }

    @Test
    fun duplicateKeysAreCheckedAsLlamaServerReadsThem() {
        // the last value wins, in kotlinx.serialization and in llama-server's nlohmann::json alike
        val image = """"image_url":{"url":"$png"}"""
        val file = """"image_url":{"url":"file:///a.png"}"""
        assertEquals("file:///a.png", offending(userPart("""{"type":"image_url",$image,$file}""")))
    }

    @Test
    fun anthropicBase64DataPasses() {
        val body =
            """{"messages":[{"role":"user","content":[{"type":"image","source":""" +
                """{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}]}]}"""
        assertNull(offending(body))
    }

    @Test
    fun textMentioningFileUrlPasses() {
        val body =
            """{"model":"alpha","messages":[{"role":"user","content":[""" +
                """{"type":"text","text":"read file:///etc/hosts"},""" +
                """{"type":"image_url","image_url":{"url":"$png"}}]},""" +
                """{"role":"user","content":"see https://example.com"}],""" +
                """"input":[{"type":"input_text","text":"file:///a.png"}],""" +
                // a search result's source is a citation, not something read
                """"x":[{"type":"search_result","source":"https://example.com","title":"t","content":[]}]}"""
        assertNull(offending(body))
    }

    @Test
    fun toolSchemasNamingMediaKeysPass() {
        val body =
            """{"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object","properties":""" +
                """{"image_url":{"type":"string","description":"file:// or http"},"source":{"type":"string"}}}}}]}"""
        assertNull(offending(body))
    }

    @Test
    fun refusedMediaIs400AndNeverReachesTheModel() {
        val models = FakeServed()
        withServer(models = models) { server, port ->
            val rejected = CompletableDeferred<ServerEvent.Rejected>()
            val collecting =
                server.events
                    .onEach { if (it is ServerEvent.Rejected) rejected.complete(it) }
                    .launchIn(CoroutineScope(Dispatchers.Unconfined))
            val reply = call(port, "/v1/chat/completions", "POST", body = chatImage("file:///chat/1/a.png"))
            assertEquals(400, reply.status)
            val error =
                Json
                    .parseToJsonElement(reply.body)
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(400, error.getValue("code").jsonPrimitive.int)
            assertEquals("invalid_request_error", error.getValue("type").jsonPrimitive.content)
            assertEquals(
                "media must be a data: URL or base64; URLs are not fetched or read",
                error.getValue("message").jsonPrimitive.content,
            )
            val event = withTimeout(1_000) { rejected.await() }
            collecting.cancel()
            assertEquals("/v1/chat/completions", event.path)
            assertEquals(400, event.status)
            assertEquals(0, models.acquired.get())
            assertEquals(emptyList<String?>(), models.asked)
        }
    }

    @Test
    fun dataMediaIsForwarded() {
        val models = FakeServed(FakeLlamaBro { raw(200, "{}") })
        withServer(models = models) { _, port ->
            assertEquals(200, call(port, "/v1/chat/completions", "POST", body = chatImage(png)).status)
            assertEquals(
                chatImage(png),
                models.fake.rawRequests
                    .single()
                    .body,
            )
        }
    }
}
