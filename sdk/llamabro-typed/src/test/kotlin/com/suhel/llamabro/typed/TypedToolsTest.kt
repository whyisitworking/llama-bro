package com.suhel.llamabro.typed

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.LbToolAbort
import com.suhel.llamabro.conversation.LbToolError
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TypedToolsTest {
    @Serializable
    data class City(val city: String, val days: Int = 1)

    @Serializable
    data class Forecast(val city: String, val sky: String)

    @Serializable
    data class Query(val q: String)

    @Serializable
    data class NoArgs(val verbose: Boolean = false)

    @Serializable
    data class Stop(val name: String, val minutes: Int = 5)

    @Serializable
    data class Route(val from: String, val to: String, val stop: Stop? = null)

    @Serializable
    data class Days(val days: Int) {
        init {
            require(days in 1..7) { "days must be 1-7" }
        }
    }

    private suspend fun FakeLlamaBro.model(): Model = llamaBro.loadModel(ModelSource.file("/fake.gguf"))

    private fun body(request: String): JsonObject = Json.parseToJsonElement(request).jsonObject

    /** The `tool` message the second request carries, the result the model sees. */
    private fun toolResultSent(fake: FakeLlamaBro, request: Int = 1): String =
        body(fake.chatRequests[request])["messages"]!!
            .jsonArray
            .last { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }
            .jsonObject["content"]!!
            .jsonPrimitive.content

    private val forecast =
        tool<City, Forecast>("get_forecast", "Weather forecast for a city") { args -> Forecast(args.city, "sunny") }

    @Test
    fun typedToolDecodesArgsAndSendsJsonResult() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("get_forecast", """{"city":"Paris"}""", id = "c1") }
                    reply { content("Sunny in Paris.") }
                }
            val chat = fake.model().conversation { tool(forecast) }
            val events = chat.send("Weather in Paris?").toList()
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            assertEquals("""{"city":"Paris","sky":"sunny"}""", result.result)
            assertEquals("""{"city":"Paris","sky":"sunny"}""", toolResultSent(fake))
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun inlineTypedToolInsideTheBuilderRegistersAndRuns() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("get_forecast", """{"city":"Paris"}""", id = "c1") }
                    reply { content("Sunny in Paris.") }
                }
            val chat =
                fake.model().conversation {
                    tool<City, String>("get_forecast", "Weather forecast for a city") { args ->
                        "sunny in ${args.city}"
                    }
                }
            val events = chat.send("Weather in Paris?").toList()
            val name =
                body(fake.chatRequests.first())["tools"]!!
                    .jsonArray
                    .single()
                    .jsonObject["function"]!!
                    .jsonObject["name"]!!
                    .jsonPrimitive.content
            assertEquals("get_forecast", name)
            assertEquals("sunny in Paris", events.filterIsInstance<ChatEvent.ToolResult>().single().result)
            assertEquals("sunny in Paris", toolResultSent(fake))
        }

    @Test
    fun stringResultIsSentAsIs() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("search", """{"q":"llamas"}""") }
                    reply { content("ok") }
                }
            val search = tool<Query, String>("search", "Searches the web") { args -> "results for ${args.q}" }
            fake
                .model()
                .conversation { tool(search) }
                .send("go")
                .toList()
            assertEquals("results for llamas", toolResultSent(fake))
        }

    @Test
    fun unitResultSendsDone() =
        runTest {
            var saved: String? = null
            val fake =
                FakeLlamaBro {
                    reply { toolCall("save_note", """{"q":"buy milk"}""") }
                    reply { content("Saved.") }
                }
            val save = tool<Query, Unit>("save_note", "Saves a note") { args -> saved = args.q }
            fake
                .model()
                .conversation { tool(save) }
                .send("go")
                .toList()
            assertEquals("buy milk", saved)
            assertEquals("Done", toolResultSent(fake))
        }

    @Test
    fun undecodableArgsAnswerInvalidArgumentsAndLoopGoesOn() =
        runTest {
            var calls = 0
            val fake =
                FakeLlamaBro {
                    reply { toolCall("get_forecast", """{"town":"Paris"}""", id = "c1") }
                    reply { toolCall("get_forecast", """{"city":"Paris"}""", id = "c2") }
                    reply { content("Sunny.") }
                }
            val counted =
                tool<City, Forecast>("get_forecast", "Weather forecast for a city") { args ->
                    calls++
                    Forecast(args.city, "sunny")
                }
            val events =
                fake
                    .model()
                    .conversation { tool(counted) }
                    .send("go")
                    .toList()
            val first = toolResultSent(fake, 1)
            assertTrue(first, first.startsWith("Error: invalid arguments: "))
            assertFalse(first, "JSON input" in first)
            // kotlinx's own error is the cause: the app can see the full text the model was cut short of
            assertTrue(events.filterIsInstance<ChatEvent.ToolResult>().first().error is SerializationException)
            assertEquals("""{"city":"Paris","sky":"sunny"}""", toolResultSent(fake, 2))
            assertEquals(1, calls)
            assertEquals(3, fake.chatRequests.size)
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun otherExceptionTextNeverReachesThePrompt() =
        runTest {
            val secret = IllegalStateException("token=sk-FAKE-123")
            val fake =
                FakeLlamaBro {
                    reply { toolCall("get_forecast", """{"city":"Paris"}""") }
                    reply { content("sorry") }
                }
            val failing = tool<City, Forecast>("get_forecast", "Weather forecast for a city") { throw secret }
            val chat = fake.model().conversation { tool(failing) }
            val events = chat.send("go").toList()
            assertEquals("Error: the tool failed", toolResultSent(fake))
            assertTrue(fake.chatRequests.none { "sk-FAKE-123" in it })
            assertFalse("sk-FAKE-123" in chat.toJson())
            assertSame(secret, events.filterIsInstance<ChatEvent.ToolResult>().single().error)
        }

    @Test
    fun abortEndsTheTurn() =
        runTest {
            val abort = LbToolAbort("no network")
            val fake = FakeLlamaBro { reply { toolCall("get_forecast", """{"city":"Paris"}""") } }
            val aborting = tool<City, Forecast>("get_forecast", "Weather forecast for a city") { throw abort }
            val chat = fake.model().conversation { tool(aborting) }
            val done = chat.send("go").toList().last() as ChatEvent.Done
            assertEquals(FinishReason.ToolAborted, done.reason)
            assertSame(abort, done.abort)
            assertEquals(1, fake.chatRequests.size)
            assertTrue(chat.messages.value.none { it is Message.ToolResult })
        }

    @Test
    fun requiresApprovalPausesTheTurn() =
        runTest {
            var sent = false
            val fake =
                FakeLlamaBro {
                    reply { toolCall("send_email", """{"q":"hi"}""", id = "c1") }
                    reply { content("Sent.") }
                }
            val email =
                tool<Query, Unit>("send_email", "Sends an email", requiresApproval = true) { sent = true }
            val chat = fake.model().conversation { tool(email) }
            val paused = chat.send("Email them.").toList().last() as ChatEvent.Done
            assertEquals(FinishReason.ToolCallsPending, paused.reason)
            assertFalse(sent)
            assertEquals("c1", chat.pendingApproval.value?.id)
            val done = chat.approve("c1").toList().last() as ChatEvent.Done
            assertTrue(sent)
            assertEquals("Done", toolResultSent(fake))
            assertEquals(FinishReason.Stop, done.reason)
        }

    @Test
    fun contextCarriesCallIdNameAndRawArgs() =
        runTest {
            var seen: LbToolCallContext? = null
            val raw = """{"city":"Oslo"}"""
            val fake =
                FakeLlamaBro {
                    reply { toolCall("get_forecast", raw, id = "call-7") }
                    reply { content("ok") }
                }
            val recording =
                tool<City, Forecast>("get_forecast", "Weather forecast for a city") { args ->
                    seen = this
                    Forecast(args.city, "snow")
                }
            fake
                .model()
                .conversation { tool(recording) }
                .send("go")
                .toList()
            assertEquals("call-7", seen!!.callId)
            assertEquals("get_forecast", seen!!.toolName)
            assertEquals(raw, seen!!.rawArguments)
        }

    @Test
    fun badNameIsRefused() {
        for (name in listOf("", "get weather", "a".repeat(65), "get.weather", "météo")) {
            try {
                tool<City, Forecast>(name, "Weather") { Forecast(it.city, "sunny") }
                fail("expected IllegalArgumentException for '$name'")
            } catch (e: IllegalArgumentException) {
                assertTrue("${e.message}", e.message!!.contains("name"))
            }
        }
        tool<City, Forecast>("a".repeat(64), "Weather") { Forecast(it.city, "sunny") }
        tool<City, Forecast>("Get-Weather_2", "Weather") { Forecast(it.city, "sunny") }
    }

    @Test
    fun blankDescriptionIsRefused() {
        try {
            tool<City, Forecast>("get_forecast", "  ") { Forecast(it.city, "sunny") }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message}", e.message!!.contains("description"))
        }
    }

    @Test
    fun argumentsThatAreNotAnObjectAreRefused() {
        try {
            tool<String, String>("echo", "Echoes") { it }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message}", e.message!!.contains("object"))
        }
        try {
            tool<City?, String>("echo", "Echoes") { "x" }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message}", e.message!!.contains("object"))
        }
    }

    @Test
    fun toolboxRegistersEveryTool() =
        runTest {
            val search = tool<Query, String>("search", "Searches the web") { "r" }
            val box = toolboxOf(forecast, search)
            assertEquals(listOf("get_forecast", "search"), box.tools.map { it.name })
            val fake = FakeLlamaBro { reply { content("hi") } }
            fake
                .model()
                .conversation { toolbox(box) }
                .send("hi")
                .toList()
            val names =
                body(fake.chatRequests.single())["tools"]!!.jsonArray.map {
                    it.jsonObject["function"]!!
                        .jsonObject["name"]!!
                        .jsonPrimitive.content
                }
            assertEquals(listOf("get_forecast", "search"), names)
        }

    @Test
    fun schemaSentIsTheConvertersOutput() =
        runTest {
            val fake = FakeLlamaBro { reply { content("hi") } }
            fake
                .model()
                .conversation { tool(forecast) }
                .send("hi")
                .toList()
            val function =
                body(fake.chatRequests.single())["tools"]!!
                    .jsonArray
                    .single()
                    .jsonObject["function"]!!
                    .jsonObject
            assertEquals("Weather forecast for a city", function["description"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(typedSchema<City>()), function["parameters"])
        }

    @Test
    fun blankArgumentsDecodeAsAnEmptyObject() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("ping", "") }
                    reply { content("ok") }
                }
            val ping = tool<NoArgs, String>("ping", "Checks the network") { "pong ${it.verbose}" }
            fake
                .model()
                .conversation { tool(ping) }
                .send("go")
                .toList()
            assertEquals("pong false", toolResultSent(fake))
        }

    @Test
    fun failingRequireInInitSendsNoReasonAndTheLoopGoesOn() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("plan", """{"days":9}""") }
                    reply { toolCall("plan", """{"days":3}""") }
                    reply { content("Planned.") }
                }
            val plan = tool<Days, String>("plan", "Plans a trip") { "planned ${it.days} days" }
            val events =
                fake
                    .model()
                    .conversation { tool(plan) }
                    .send("go")
                    .toList()
            assertEquals("Error: invalid arguments", toolResultSent(fake, 1))
            val error = events.filterIsInstance<ChatEvent.ToolResult>().first().error
            assertTrue("$error", error is IllegalArgumentException && error.message == "days must be 1-7")
            assertEquals("planned 3 days", toolResultSent(fake, 2))
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Serializable
    data class Trip(val days: Int) {
        init {
            if (days !in 1..7) throw LbToolError("days must be 1-7")
        }
    }

    @Test
    fun lbToolErrorFromInitReachesTheModelWithItsMessage() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("trip", """{"days":9}""") }
                    reply { content("Sorry.") }
                }
            val trip = tool<Trip, String>("trip", "Plans a trip") { "planned ${it.days} days" }
            val events =
                fake
                    .model()
                    .conversation { tool(trip) }
                    .send("go")
                    .toList()
            assertEquals("Error: days must be 1-7", toolResultSent(fake, 1))
            assertNull(events.filterIsInstance<ChatEvent.ToolResult>().first().error)
        }

    @Test
    fun missingFieldSaysWhichField() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("plan", "{}") }
                    reply { toolCall("route", "{}") }
                    reply { toolCall("route", """{"from":"A","to":"B","stop":{}}""") }
                    reply { content("Sorry.") }
                }
            val plan = tool<Days, String>("plan", "Plans a trip") { "planned ${it.days} days" }
            val route = tool<Route, String>("route", "Plans a route") { "ok" }
            fake
                .model()
                .conversation {
                    tool(plan)
                    tool(route)
                }.send("go")
                .toList()
            assertEquals("Error: invalid arguments: missing field 'days'", toolResultSent(fake, 1))
            assertEquals("Error: invalid arguments: missing fields 'from', 'to'", toolResultSent(fake, 2))
            assertEquals("Error: invalid arguments: missing field 'name' at \$.stop", toolResultSent(fake, 3))
        }

    @Serializable
    data class Teen(val age: Int) {
        init {
            check(age in 13..19) { "a teen is 13 to 19" }
        }
    }

    @Test
    fun failingCheckInInitSendsInvalidArguments() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("teen", """{"age":30}""") }
                    reply { content("Sorry.") }
                }
            val teen = tool<Teen, String>("teen", "Greets a teen") { "hi" }
            val events =
                fake
                    .model()
                    .conversation { tool(teen) }
                    .send("go")
                    .toList()
            assertEquals("Error: invalid arguments", toolResultSent(fake, 1))
            val error = events.filterIsInstance<ChatEvent.ToolResult>().first().error
            assertTrue("$error", error is IllegalStateException)
        }

    @Serializable
    data class Report(val city: String, val sky: String = "clear", val alerts: List<String> = emptyList())

    @Test
    fun resultKeepsFieldsAtTheirDefault() =
        runTest {
            // the model reads the whole result: a field equal to its default is still sent (D88)
            val fake =
                FakeLlamaBro {
                    reply { toolCall("report", """{"city":"Paris"}""") }
                    reply { content("Clear.") }
                }
            val report = tool<City, Report>("report", "Weather report") { Report(it.city) }
            fake
                .model()
                .conversation { tool(report) }
                .send("go")
                .toList()
            assertEquals("""{"city":"Paris","sky":"clear","alerts":[]}""", toolResultSent(fake, 1))
        }

    @Test
    fun nullableStringResultIsSentAsIsOrAsNull() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("lookup", """{"q":"hit"}""") }
                    reply { toolCall("lookup", """{"q":"miss"}""") }
                    reply { content("ok") }
                }
            val lookup = tool<Query, String?>("lookup", "Looks up a word") { if (it.q == "hit") "found it" else null }
            fake
                .model()
                .conversation { tool(lookup) }
                .send("go")
                .toList()
            assertEquals("found it", toolResultSent(fake, 1))
            assertEquals("null", toolResultSent(fake, 2))
        }

    @Test
    fun nullableUnitResultSendsDone() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { toolCall("touch", """{"q":"a"}""") }
                    reply { toolCall("touch", """{"q":"b"}""") }
                    reply { content("ok") }
                }
            val touch = tool<Query, Unit?>("touch", "Touches a file") { if (it.q == "a") Unit else null }
            fake
                .model()
                .conversation { tool(touch) }
                .send("go")
                .toList()
            assertEquals("Done", toolResultSent(fake, 1))
            assertEquals("Done", toolResultSent(fake, 2))
        }
}
