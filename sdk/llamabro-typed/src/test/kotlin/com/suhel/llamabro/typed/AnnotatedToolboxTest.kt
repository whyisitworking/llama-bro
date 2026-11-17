package com.suhel.llamabro.typed

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.conversation
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What an app would inject into its toolbox. */
class Notes(val texts: Map<String, String>)

@Serializable
data class Search(val q: String, val limit: Int = 2)

@Serializable
data class Forecast(val city: String, val days: Int?)

/** A toolbox as an app writes one: an injected class whose methods the real processor turns into tools (D61). */
@LbToolbox
class Assistant(private val notes: Notes) {
    var blockingThread: Thread? = null
    val sent = mutableListOf<String>()

    @LbTool("Weather forecast for a city")
    suspend fun getForecast(
        @LbToolParam("The city") city: String,
        @LbToolParam days: Int? = null,
    ): Forecast = Forecast(city, days)

    @LbTool("Reads a note by its title")
    fun readNote(
        @LbToolParam title: String,
    ): String {
        blockingThread = Thread.currentThread()
        return notes.texts.getValue(title)
    }

    @LbTool("Searches the notes; the result is the matching titles")
    suspend fun search(args: Search): List<String> = List(args.limit) { "${args.q} $it" }

    @LbTool("Sends an email", requiresApproval = true)
    suspend fun sendEmail(
        @LbToolParam to: String,
    ) {
        sent += to
    }

    @LbTool("Says which call this is")
    suspend fun whoAmI(call: LbToolCallContext): String = "${call.callId} ${call.toolName}"

    @LbTool("The weekday of a date")
    suspend fun weekday(
        @LbToolParam on:
            @Serializable(with = IsoDate::class)
            LocalDate,
    ): String = on.dayOfWeek.name
}

/** A date as ISO text, for a separate parameter that names its serializer (D88). */
object IsoDate : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("IsoDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

class AnnotatedToolboxTest {
    private val assistant = Assistant(Notes(mapOf("milk" to "buy milk")))

    private suspend fun FakeLlamaBro.model(): Model = llamaBro.loadModel(ModelSource.file("/fake.gguf"))

    private fun body(request: String): JsonObject = Json.parseToJsonElement(request).jsonObject

    /** Runs one call of [name] with [args] through the loop and returns the result the model got. */
    private suspend fun callThroughLoop(name: String, args: String): String {
        val fake =
            FakeLlamaBro {
                reply { toolCall(name, args, id = "c1") }
                reply { content("ok") }
            }
        val events =
            fake
                .model()
                .conversation { toolbox(assistant.asToolbox()) }
                .send("go")
                .toList()
        assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        return events.filterIsInstance<ChatEvent.ToolResult>().single().result
    }

    @Test
    fun everyToolIsRegisteredWithItsSchema() =
        runTest {
            val fake = FakeLlamaBro { reply { content("hi") } }
            fake
                .model()
                .conversation { toolbox(assistant.asToolbox()) }
                .send("hi")
                .toList()
            val tools = body(fake.chatRequests[0])["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject }
            assertEquals(
                listOf("get_forecast", "read_note", "search", "send_email", "who_am_i", "weekday"),
                tools.map { it["name"]!!.jsonPrimitive.content },
            )
            val forecast = tools[0]["parameters"]!!.jsonObject
            assertEquals(
                "The city",
                forecast["properties"]!!
                    .jsonObject["city"]!!
                    .jsonObject["description"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(listOf("city"), forecast["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            val whoAmI = tools[4]["parameters"]!!.jsonObject
            assertTrue(whoAmI.toString(), whoAmI["properties"]?.jsonObject.isNullOrEmpty())
            val weekday = tools[5]["parameters"]!!.jsonObject
            assertEquals("""{"type":"string"}""", weekday["properties"]!!.jsonObject["on"].toString())
        }

    @Test
    fun separateParamKeepsItsSerializer() =
        runTest {
            assertEquals("TUESDAY", callThroughLoop("weekday", """{"on":"2026-11-17"}"""))
        }

    @Test
    fun suspendingToolWithSeparateParams() =
        runTest {
            assertEquals("""{"city":"Paris","days":null}""", callThroughLoop("get_forecast", """{"city":"Paris"}"""))
            assertEquals(
                """{"city":"Paris","days":3}""",
                callThroughLoop("get_forecast", """{"city":"Paris","days":3}"""),
            )
        }

    @Test
    fun blockingToolRunsOffTheCallersThread() =
        runTest {
            val caller = Thread.currentThread()
            assertEquals("buy milk", callThroughLoop("read_note", """{"title":"milk"}"""))
            assertNotSame(caller, assistant.blockingThread)
        }

    @Test
    fun dataClassFormKeepsItsRealDefault() =
        runTest {
            assertEquals("""["llamas 0","llamas 1"]""", callThroughLoop("search", """{"q":"llamas"}"""))
        }

    @Test
    fun approvalToolWaitsForTheApp() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("send_email", """{"to":"sam"}""", id = "c1") }
                    reply { content("Sent.") }
                }
            val chat = fake.model().conversation { toolbox(assistant.asToolbox()) }
            val paused = chat.send("Email Sam.").toList().last() as ChatEvent.Done
            assertEquals(FinishReason.ToolCallsPending, paused.reason)
            assertFalse("sam" in assistant.sent)
            val done = chat.approve("c1").toList()
            assertEquals(listOf("sam"), assistant.sent)
            assertEquals("Done", done.filterIsInstance<ChatEvent.ToolResult>().single().result)
            assertEquals(FinishReason.Stop, (done.last() as ChatEvent.Done).reason)
        }

    @Test
    fun contextParamGetsTheCall() =
        runTest {
            assertEquals("c1 who_am_i", callThroughLoop("who_am_i", ""))
        }
}
