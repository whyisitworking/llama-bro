package com.suhel.llamabro.testing

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.speak
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speech (D139) on [FakeLlamaBro]: the real `Model.speak` over scripted, streamed WAV. */
class FakeSpeechTest {
    private suspend fun FakeLlamaBro.model(): Model = llamaBro.loadModel(ModelSource.file("/models/kokoro.gguf"))

    @Test
    fun theScriptedSpeechComesBackAsPcmAtItsRate() =
        runBlocking {
            val samples = ShortArray(9) { (it * 100 - 400).toShort() }
            val fake =
                FakeLlamaBro {
                    voices = listOf("af_heart", "am_adam")
                    speech(samples, sampleRate = 24000)
                }
            val pieces = fake.model().speak("Hello.") { voice = "af_heart" }.toList()
            assertEquals(setOf(24000), pieces.map { it.sampleRate }.toSet())
            assertArrayEquals(samples, pieces.flatMap { it.pcm.toList() }.toShortArray())
        }

    @Test
    fun theRequestCarriesOnlyWhatWasSet() =
        runBlocking {
            val fake = FakeLlamaBro { speech(ShortArray(2)) }
            fake
                .model()
                .speak("Hi.") {
                    language = "en"
                    speed = 1.2f
                    maxFrames = 300
                    extra = """{"loudness":-16}"""
                }.toList()
            val body = Json.parseToJsonElement(fake.speechRequests.single()).jsonObject
            assertEquals("Hi.", body["input"]!!.jsonPrimitive.content)
            assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
            assertEquals("audio", body["stream_format"]!!.jsonPrimitive.content)
            assertEquals("en", body["lang"]!!.jsonPrimitive.content)
            assertEquals(1.2f, body["speed"]!!.jsonPrimitive.content.toFloat())
            assertEquals(300, body["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertEquals(-16, body["loudness"]!!.jsonPrimitive.content.toInt())
            assertFalse(body.containsKey("voice"))
            assertFalse(body.containsKey("seed"))
        }

    @Test
    fun nothingScriptedIsAServerError() =
        runBlocking {
            val model = FakeLlamaBro().model()
            assertThrows(LlamaBroException::class.java) { runBlocking { model.speak("Hi.").toList() } }
            Unit
        }

    @Test
    fun aModelThatDoesNotSpeakRefusesEverySpeakAsNotSupported() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    speaks = false
                    speech(ShortArray(2))
                }
            val model = fake.model()
            repeat(2) {
                val e = runCatching { model.speak("Hi.").toList() }.exceptionOrNull()
                assertTrue("$e", e is LlamaBroException.NotSupported)
                assertTrue("$e", e!!.message!!.contains("this server does not support audio generation"))
            }
            assertEquals(2, fake.speechRequests.size)
        }

    @Test
    fun aQueuedSpeechErrorAnswersTheNextSpeakBeforeTheScript() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    speechError(400, "invalid_request_error", "this model takes no speed")
                    speech(ShortArray(2))
                }
            val model = fake.model()
            val e = runCatching { model.speak("Hi.").toList() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.InvalidRequest)
            assertTrue("$e", e!!.message!!.contains("this model takes no speed"))
            assertTrue(model.speak("Hi.").toList().isNotEmpty())
            assertEquals(2, fake.speechRequests.size)
        }

    @Test
    fun misuseIsRefusedBeforeAnyRequest() =
        runBlocking {
            val fake = FakeLlamaBro { voices = listOf("af_heart") }
            val model = fake.model()
            assertThrows(IllegalArgumentException::class.java) { model.speak("Hi.") { voice = "nobody" } }
            assertThrows(IllegalArgumentException::class.java) {
                model.speak("Hi.") {
                    voice = "af_heart"
                    speakerReference = MediaSource.bytes(ByteArray(4))
                }
            }
            assertThrows(IllegalArgumentException::class.java) { model.speak("Hi.") { extra = """{"input":"x"}""" } }
            assertEquals(emptyList<String>(), fake.speechRequests)
        }
}
