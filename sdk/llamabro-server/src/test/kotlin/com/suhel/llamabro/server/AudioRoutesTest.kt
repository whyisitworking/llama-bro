package com.suhel.llamabro.server

import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/** The audio routes: a multipart upload to `Model.transcribe`, and `Model.speak` streamed back as bytes. */
class AudioRoutesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val transcriptions = "/v1/audio/transcriptions"
    private val speech = "/v1/audio/speech"
    private val boundary = "llamabro-test-boundary"

    private fun hearing(script: FakeLlamaBro.() -> Unit = {}) =
        FakeServed(
            FakeLlamaBro {
                supportsAudio = true
                script()
            },
        )

    private fun speaking(script: FakeLlamaBro.() -> Unit = {}) =
        FakeServed(
            FakeLlamaBro {
                voices = listOf("female")
                script()
            },
        )

    private fun config(configure: ServerConfig.Builder.() -> Unit = {}) =
        ServerConfig {
            port = 0
            workDir = Path(temp.root.path, "work")
            configure()
        }

    private fun multipart(fields: Map<String, String>, file: ByteArray? = "RIFFxxxxWAVE".toByteArray()): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        fun line(text: String) = out.write("$text\r\n".toByteArray())
        fields.forEach { (name, value) ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("")
            line(value)
        }
        if (file != null) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"")
            line("Content-Type: audio/wav")
            line("")
            out.write(file)
            line("")
        }
        line("--$boundary--")
        return out.toByteArray()
    }

    private suspend fun upload(port: Int, body: ByteArray, headers: Map<String, String> = emptyMap()): Reply =
        withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:$port$transcriptions").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val received =
                    connection.headerFields
                        .filterKeys { it != null }
                        .mapKeys { it.key.lowercase() }
                        .mapValues { it.value.first() }
                Reply(status, text, received)
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun speak(port: Int, body: String) =
        withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:$port$speech").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                Triple(status, stream.readBytes(), connection.headerFields.mapKeys { it.key?.lowercase() })
            } finally {
                connection.disconnect()
            }
        }

    private fun errorOf(reply: Reply) =
        Json
            .parseToJsonElement(reply.body)
            .jsonObject
            .getValue("error")
            .jsonObject

    private fun workFiles(): List<String> {
        val dir = Path(temp.root.path, "work")
        return if (SystemFileSystem.exists(dir)) SystemFileSystem.list(dir).map { it.name } else emptyList()
    }

    @Test
    fun transcriptionAnswersJson() {
        val models = hearing { transcript("hello there") }
        withServer(config(), models) { _, port ->
            val reply = upload(port, multipart(mapOf("model" to "alpha", "language" to "en", "prompt" to "names")))
            assertEquals(reply.body, 200, reply.status)
            assertTrue(reply.headers.getValue("content-type").startsWith("application/json"))
            assertEquals(
                "hello there",
                Json
                    .parseToJsonElement(reply.body)
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
            val sent = Json.parseToJsonElement(models.fake.transcribeRequests.single()).jsonObject
            assertEquals("en", sent.getValue("language").jsonPrimitive.content)
            assertEquals("names", sent.getValue("prompt").jsonPrimitive.content)
            assertEquals(listOf<String?>("alpha"), models.asked)
            eventually { models.released.get() == 1 }
        }
    }

    @Test
    fun transcriptionAnswersText() {
        val models = hearing { transcript("plain words") }
        withServer(config(), models) { _, port ->
            val reply = upload(port, multipart(mapOf("response_format" to "text")))
            assertEquals(200, reply.status)
            assertEquals("plain words", reply.body)
            assertEquals("text/plain; charset=utf-8", reply.headers.getValue("content-type").lowercase())
        }
    }

    @Test
    fun transcriptionRefusesOtherFormatsAndBadForms() {
        val models = hearing { transcript("never") }
        withServer(config(), models) { _, port ->
            val srt = upload(port, multipart(mapOf("response_format" to "srt")))
            assertEquals(400, srt.status)
            assertEquals("invalid_request_error", errorOf(srt).getValue("type").jsonPrimitive.content)
            assertEquals(400, upload(port, multipart(emptyMap(), file = null)).status)
            assertEquals(400, upload(port, multipart(mapOf("temperature" to "hot"))).status)
            assertEquals(400, call(port, transcriptions, "POST", body = "{}").status)
            assertEquals(0, models.acquired.get())
            assertEquals(emptyList<String>(), workFiles())
        }
    }

    @Test
    fun transcriptionBodyOverTheLimitIs413() {
        val models = hearing { transcript("never") }
        withServer(config { maxBodyBytes = 64 }, models) { _, port ->
            val reply = upload(port, multipart(emptyMap(), file = ByteArray(10_000)))
            assertEquals(413, reply.status)
            assertEquals(0, models.acquired.get())
            assertEquals(emptyList<String>(), workFiles())
        }
    }

    @Test
    fun uploadGoesThroughAFileTheServerDeletes() {
        val models = hearing { transcript("ok") }
        withServer(config(), models) { _, port ->
            assertEquals(200, upload(port, multipart(emptyMap())).status)
            eventually { models.released.get() == 1 }
            // the folder was made for the upload and nothing is left in it
            assertTrue(SystemFileSystem.exists(Path(temp.root.path, "work")))
            assertEquals(emptyList<String>(), workFiles())
        }
        // a cut transcript with no maxTokens fails inside transcribe: the file must go on that path too
        val failing = hearing { transcript("cut", truncated = true) }
        withServer(config(), failing) { _, port ->
            val reply = upload(port, multipart(emptyMap()))
            assertEquals(500, reply.status)
            eventually { failing.released.get() == 1 }
            assertEquals(emptyList<String>(), workFiles())
        }
    }

    @Test
    fun speechStreamsAudio() {
        val models = speaking { speech(shortArrayOf(1, 2, 3, -2), sampleRate = 22050) }
        withServer(config(), models) { _, port ->
            val (status, bytes, headers) = speak(port, """{"model":"alpha","input":"hi","voice":"alloy"}""")
            assertEquals(200, status)
            assertEquals("audio/pcm", headers.getValue("content-type").first())
            assertEquals("22050", headers.getValue("x-sample-rate").first())
            assertEquals(listOf<Byte>(1, 0, 2, 0, 3, 0, -2, -1), bytes.toList())
            // a voice the model doesn't have is left out, not an error: OpenAI clients always send one
            val sent = Json.parseToJsonElement(models.fake.speechRequests.single()).jsonObject
            assertEquals("hi", sent.getValue("input").jsonPrimitive.content)
            assertTrue("voice" !in sent)
            eventually { models.released.get() == 1 }
        }
    }

    @Test
    fun speechWavHasAStreamingHeader() {
        val models = speaking { speech(shortArrayOf(7, 8), sampleRate = 16000) }
        withServer(config(), models) { _, port ->
            val (status, bytes, headers) =
                speak(port, """{"input":"hi","voice":"female","speed":1.5,"response_format":"wav"}""")
            assertEquals(200, status)
            assertEquals("audio/wav", headers.getValue("content-type").first())
            assertEquals("RIFF", bytes.decodeToString(0, 4))
            assertEquals("WAVE", bytes.decodeToString(8, 12))
            assertEquals(44 + 4, bytes.size)
            assertEquals(listOf<Byte>(-1, -1, -1, -1), bytes.slice(40..43))
            assertEquals(listOf<Byte>(7, 0, 8, 0), bytes.slice(44..47))
            val sent = Json.parseToJsonElement(models.fake.speechRequests.single()).jsonObject
            assertEquals("female", sent.getValue("voice").jsonPrimitive.content)
            assertEquals(
                1.5,
                sent
                    .getValue("speed")
                    .jsonPrimitive.content
                    .toDouble(),
                0.0,
            )
        }
    }

    @Test
    fun speechRefusesFormatsItCannotMake() {
        val models = speaking { speech(shortArrayOf(1)) }
        withServer(config(), models) { _, port ->
            val (status, body, _) = speak(port, """{"input":"hi","response_format":"mp3"}""")
            assertEquals(400, status)
            val error =
                Json
                    .parseToJsonElement(body.decodeToString())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals("invalid_request_error", error.getValue("type").jsonPrimitive.content)
            assertTrue(
                error
                    .getValue("message")
                    .jsonPrimitive.content
                    .contains("wav"),
            )
            assertEquals(400, speak(port, """{"model":"alpha"}""").first)
            assertEquals(0, models.acquired.get())
        }
    }

    @Test
    fun speechErrorPassesStatus() {
        val models = speaking { speechError(502, "server_error", "upstream broke") }
        withServer(config(), models) { _, port ->
            val (status, body, _) = speak(port, """{"input":"hi"}""")
            assertEquals(502, status)
            val error =
                Json
                    .parseToJsonElement(body.decodeToString())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            assertEquals(502, error.getValue("code").jsonPrimitive.int)
            assertEquals("upstream broke", error.getValue("message").jsonPrimitive.content)
            eventually { models.released.get() == 1 }
        }
    }

    @Test
    fun audioRoutesAre404OnAModelThatCannot() {
        val deaf = FakeServed(FakeLlamaBro { transcript("never") })
        withServer(config(), deaf) { _, port ->
            val reply = upload(port, multipart(emptyMap()))
            assertEquals(404, reply.status)
            assertEquals("not_found_error", errorOf(reply).getValue("type").jsonPrimitive.content)
            eventually { deaf.released.get() == 1 }
            assertEquals(emptyList<String>(), workFiles())
            val (status, _, _) = speak(port, """{"input":"hi"}""")
            assertEquals(404, status)
            eventually { deaf.released.get() == 2 }
        }
    }

    @Test
    fun audioRoutesNeedTheKeyAndAreLogged() {
        val models = hearing { transcript("ok") }
        withServer(config { apiKey = "secret" }, models) { server, port ->
            val events = mutableListOf<ServerEvent>()
            val collecting =
                server.events
                    .onEach {
                        synchronized(events) { events += it }
                    }.launchIn(CoroutineScope(Dispatchers.Default))
            assertEquals(401, upload(port, multipart(emptyMap())).status)
            assertEquals(401, speak(port, """{"input":"hi"}""").first)
            assertEquals(0, models.acquired.get())
            val ok = upload(port, multipart(emptyMap()), mapOf("Authorization" to "Bearer secret"))
            assertEquals(200, ok.status)
            eventually { synchronized(events) { events.any { it is ServerEvent.RequestEnded } } }
            val seen = synchronized(events) { events.toList() }
            assertTrue(seen.any { it is ServerEvent.RequestStarted && it.path == transcriptions })
            assertEquals(200, seen.filterIsInstance<ServerEvent.RequestEnded>().single().status)
            collecting.cancel()
        }
    }

    @Test
    fun refusedFormsAreRejectedEvents() {
        withServer(config(), hearing()) { server, port ->
            val events = mutableListOf<ServerEvent>()
            val collecting =
                server.events
                    .onEach {
                        synchronized(events) { events += it }
                    }.launchIn(CoroutineScope(Dispatchers.Default))
            assertEquals(400, upload(port, multipart(mapOf("response_format" to "vtt"))).status)
            eventually { synchronized(events) { events.any { it is ServerEvent.Rejected } } }
            collecting.cancel()
        }
    }
}
