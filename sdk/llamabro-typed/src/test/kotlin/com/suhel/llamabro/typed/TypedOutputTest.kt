package com.suhel.llamabro.typed

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Typed
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ResponseFormat
import com.suhel.llamabro.conversation
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TypedOutputTest {
    @Serializable
    data class Person(val name: String, val age: Int)

    @Serializable
    data class Adult(val age: Int) {
        init {
            require(age >= 18) { "an adult is 18 or older" }
        }
    }

    /** Recursive, so it has no schema. */
    @Serializable
    data class Node(val next: Node?)

    private suspend fun FakeLlamaBro.model(): Model = llamaBro.loadModel(ModelSource.file("/fake.gguf"))

    private fun body(request: String): JsonObject = Json.parseToJsonElement(request).jsonObject

    @Test
    fun valueDecodesWithItsJsonAndReasoning() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply {
                        reasoning("Bob, thirty.")
                        content("""{"name":"Bob","age":30}""")
                    }
                }
            val chat = fake.model().conversation()
            val typed = chat.ask<Person>("A person named Bob aged 30.")
            assertEquals(Typed.Value(Person("Bob", 30), """{"name":"Bob","age":30}""", "Bob, thirty."), typed)
        }

    @Test
    fun requestCarriesTheSchemaAndName() =
        runTest {
            val fake = FakeLlamaBro { reply { content("""{"name":"Bob","age":30}""") } }
            fake.model().conversation().ask<Person>("Bob, 30")
            val format = body(fake.chatRequests.single())["response_format"]!!.jsonObject
            assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
            val schema = format["json_schema"]!!.jsonObject
            assertEquals("Person", schema["name"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(typedSchema<Person>()), schema["schema"])
        }

    @Test
    fun lengthCutIsIncompleteWithPartialJson() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply {
                        content("""{"name":"Bo""")
                        finishLength()
                    }
                }
            val typed = fake.model().conversation().ask<Person>("Bob, 30")
            assertEquals(Typed.Incomplete(FinishReason.Length, """{"name":"Bo"""), typed)
        }

    @Test
    fun numberPastIntIsInvalid() =
        runTest {
            val text = """{"name":"Bob","age":3000000000}"""
            val fake = FakeLlamaBro { reply { content(text) } }
            val typed = fake.model().conversation().ask<Person>("Bob")
            assertTrue("$typed", typed is Typed.Invalid && typed.json == text)
            assertTrue("$typed", (typed as Typed.Invalid).cause is SerializationException)
        }

    @Test
    fun failingInitCheckIsInvalid() =
        runTest {
            val fake = FakeLlamaBro { reply { content("""{"age":12}""") } }
            val typed = fake.model().conversation().ask<Adult>("A child")
            assertTrue("$typed", typed is Typed.Invalid && typed.json == """{"age":12}""")
            assertEquals("an adult is 18 or older", (typed as Typed.Invalid).cause.message)
        }

    @Test
    fun schemaErrorThrowsBeforeAnyRequest() =
        runTest {
            val fake = FakeLlamaBro { reply { content("{}") } }
            val chat = fake.model().conversation()
            try {
                chat.ask<Node>("A list")
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue("${e.message}", e.message!!.contains("recursive"))
            }
            assertEquals(0, fake.chatRequests.size)
            assertEquals(emptyList<Message>(), chat.messages.value)
        }

    @Test
    fun appResponseFormatIsRefused() =
        runTest {
            val fake = FakeLlamaBro { reply { content("{}") } }
            val chat = fake.model().conversation()
            try {
                chat.ask<Person>("Bob") { responseFormat = ResponseFormat.JsonObject }
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue("${e.message}", e.message!!.contains("responseFormat"))
            }
            assertEquals(0, fake.chatRequests.size)
        }

    @Test
    fun defaultResponseFormatIsReplacedByTheSchema() =
        runTest {
            val fake = FakeLlamaBro { reply { content("""{"name":"Bob","age":30}""") } }
            val chat = fake.model().conversation { defaults { responseFormat = ResponseFormat.JsonObject } }
            val typed = chat.ask<Person>("Bob, 30")
            assertTrue("$typed", typed is Typed.Value)
            val format = body(fake.chatRequests.single())["response_format"]!!.jsonObject
            assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(typedSchema<Person>()), format["json_schema"]!!.jsonObject["schema"])
        }

    @Test
    fun appResponseFormatEqualToTheDefaultIsRefused() =
        runTest {
            val fake = FakeLlamaBro { reply { content("{}") } }
            val chat = fake.model().conversation { defaults { responseFormat = ResponseFormat.JsonObject } }
            try {
                chat.ask<Person>("Bob") { responseFormat = ResponseFormat.JsonObject }
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue("${e.message}", e.message!!.contains("responseFormat"))
            }
            assertEquals(0, fake.chatRequests.size)
        }

    @Test
    fun typedTurnKeepsToolsInTheRequest() =
        runTest {
            val fake = FakeLlamaBro { reply { content("""{"name":"Bob","age":30}""") } }
            val chat =
                fake.model().conversation {
                    tool("get_weather", "Current weather for a city", WEATHER_SCHEMA) { "sunny" }
                }
            val typed = chat.ask<Person>("Bob, 30") { sampling { temperature = 0f } }
            assertTrue("$typed", typed is Typed.Value)
            val request = body(fake.chatRequests.single())
            val tools = request["tools"]!!.jsonArray
            assertEquals(
                "get_weather",
                tools
                    .single()
                    .jsonObject["function"]!!
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
            assertTrue("$request", request["response_format"] is JsonObject)
            assertEquals("0.0", request["temperature"]!!.jsonPrimitive.content)
        }

    @Test
    fun storedAssistantMessageIsTheJson() =
        runTest {
            val text = """{"name":"Bob","age":30}"""
            val fake =
                FakeLlamaBro {
                    reply { content(text) }
                    reply { content("Bob is 30.") }
                }
            val chat = fake.model().conversation()
            chat.ask<Person>("Bob, 30")
            assertEquals(text, (chat.messages.value.last() as Message.Assistant).content)
            // the plain ask still resolves to core's member beside the typed overloads
            val plain: Typed<String> = chat.ask("Say it in words.")
            assertEquals("Bob is 30.", (plain as Typed.Value).value)
            val history = body(fake.chatRequests[1])["messages"]!!.jsonArray
            assertEquals(text, history[1].jsonObject["content"]!!.jsonPrimitive.content)
            assertTrue("${fake.chatRequests[1]}", "response_format" !in body(fake.chatRequests[1]))
        }

    @Test
    fun generateSendsNoHistoryAndTheSystemPrompt() =
        runTest {
            val fake =
                FakeLlamaBro {
                    reply { content("""{"name":"Ann","age":41}""") }
                    reply { content("""{"name":"Bob","age":30}""") }
                }
            val model = fake.model()
            assertEquals(
                Person("Ann", 41),
                (model.generate<Person>("Ann, 41", system = "Extract.") as Typed.Value).value,
            )
            val second = model.generate("Bob, 30", serializer = Person.serializer())
            assertEquals(Person("Bob", 30), (second as Typed.Value).value)
            val first = body(fake.chatRequests[0])["messages"] as JsonArray
            assertEquals(listOf("system", "user"), first.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("Extract.", first[0].jsonObject["content"]!!.jsonPrimitive.content)
            val next = body(fake.chatRequests[1])["messages"] as JsonArray
            assertEquals(listOf("user"), next.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        }

    private companion object {
        const val WEATHER_SCHEMA =
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
    }
}
