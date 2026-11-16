package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.internal.EngineAttachment
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** D84: media from a file, a descriptor or bytes, sent as placeholders with the files beside the request. */
class MediaTest {
    private suspend fun setup(info: String = FakeModel.HEARING_INFO): Pair<FakeEngine, Model> {
        val engine = FakeEngine()
        engine.model.infoJson = info
        return engine to testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
    }

    private fun parts(body: String, message: Int): JsonArray =
        (Json.parseToJsonElement(body) as JsonObject)
            .getValue("messages")
            .jsonArray[message]
            .jsonObject
            .getValue("content")
            .jsonArray

    private fun FakeEngine.chats(): List<FakeRequest> = synchronized(model.requests) { model.requests.toList() }

    private fun bytesOf(a: EngineAttachment): ByteArray = (a as EngineAttachment.Bytes).bytes

    /** Every request fails with [body]. */
    private fun FakeModel.failWith(status: Int, body: String) {
        onRequest = { r ->
            r.chunk(body, status = status)
            r.done()
        }
    }

    @Test
    fun audioContentSendsAPlaceholderAndTheAttachment() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("You said hello."))
            model.conversation().send("What did I say?", Content.Audio(MediaSource.bytes(WAV))).toList()
            val r = engine.chats().single()
            assertEquals(
                """{"type":"input_audio","input_audio":{"data":"lb-attachment:0","format":"wav"}}""",
                parts(r.body, 0)[1].toString(),
            )
            val a = r.attachments.single()
            assertEquals("0", a.name)
            assertEquals("audio/wav", a.mime)
            assertArrayEquals(WAV, bytesOf(a))
            // the request went to the native side off the caller's thread, since attachments are read in the call
            assertNotEquals(Thread.currentThread(), engine.model.requestThreads.single())
        }

    @Test
    fun imageFromAFileIsNeverReadInKotlin() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("A cat."))
            val file = File.createTempFile("big", ".png").apply { deleteOnExit() }
            RandomAccessFile(file, "rw").use { it.setLength(20L * 1024 * 1024) }
            model
                .conversation()
                .send("What is this?", Content.Image(MediaSource.file(file.path), "image/png"))
                .toList()
            val r = engine.chats().single()
            assertTrue("the body holds only the placeholder: ${r.body.length} chars", r.body.length < 1024)
            assertEquals(
                """{"type":"image_url","image_url":{"url":"lb-attachment:0"}}""",
                parts(r.body, 0)[1].toString(),
            )
            val a = r.attachments.single() as EngineAttachment.Path
            assertEquals(file.path, a.path)
            assertEquals("image/png", a.mime)
        }

    @Test
    fun descriptorIsReadOnceOnAppend() =
        runTest {
            val (engine, model) = setup()
            engine.fdContents[FD] = WAV
            engine.model.script(Replies.text("one"), Replies.text("two"))
            val chat = model.conversation()
            chat.send("Listen", Content.Audio(MediaSource.Descriptor { FD })).toList()
            // the app closes its descriptor now; the chat keeps the bytes
            engine.fdContents.remove(FD)
            chat.send("And again?").toList()
            assertEquals(listOf(FD), engine.fdReads.map { it.first })
            assertNotEquals(Thread.currentThread(), engine.fdReads.single().second)
            val user = chat.messages.value.first() as Message.User
            val audio = user.content[1] as Content.Audio
            assertArrayEquals(WAV, (audio.source as MediaSource.Bytes).bytes)
            engine.chats().forEach { assertArrayEquals(WAV, bytesOf(it.attachments.single())) }
        }

    @Test
    fun descriptorThatCantBeReadIsInvalidRequest() =
        runTest {
            val (engine, model) = setup()
            val chat = model.conversation()
            val e = runCatching { chat.send("Listen", Content.Audio(MediaSource.Descriptor { FD })).toList() }
            val error = e.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertTrue(error!!.message!!, error.message!!.contains("descriptor $FD"))
            assertTrue(chat.messages.value.isEmpty())
            assertTrue(engine.chats().isEmpty())
        }

    @Test
    fun mediaSurvivesLaterTurns() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("a"), Replies.text("b"), Replies.text("c"))
            val chat = model.conversation()
            chat
                .send(
                    "Look and listen",
                    Content.Image(MediaSource.file(CAT.path), "image/png"),
                    Content.Audio(MediaSource.bytes(WAV)),
                ).toList()
            chat.send("Second").toList()
            chat.send("Third").toList()
            val third = engine.chats()[2]
            val first = parts(third.body, 0)
            assertEquals(
                "lb-attachment:0",
                first[1]
                    .jsonObject["image_url"]!!
                    .jsonObject["url"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "lb-attachment:1",
                first[2]
                    .jsonObject["input_audio"]!!
                    .jsonObject["data"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(listOf("0", "1"), third.attachments.map { it.name })
            assertEquals(CAT.path, (third.attachments[0] as EngineAttachment.Path).path)
            assertArrayEquals(WAV, bytesOf(third.attachments[1]))
        }

    @Test
    fun toJsonSavesPathsAndBytes() =
        runTest {
            val (engine, model) = setup()
            engine.fdContents[FD] = FLAC
            engine.model.script(Replies.text("a"))
            val chat = model.conversation()
            chat
                .send(
                    "Three",
                    Content.Image(MediaSource.file(CAT.path), "image/png"),
                    Content.Audio(MediaSource.bytes(WAV)),
                    Content.Audio(MediaSource.Descriptor { FD }, "audio/flac"),
                ).toList()
            val saved = Json.parseToJsonElement(chat.toJson()).jsonObject
            val content =
                saved
                    .getValue("messages")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("content")
                    .jsonArray
            assertEquals("""{"type":"image","mime":"image/png","path":"${CAT.path}"}""", content[1].toString())
            assertEquals(
                """{"type":"audio","mime":"audio/wav","data":"${Base64.getEncoder().encodeToString(WAV)}"}""",
                content[2].toString(),
            )
            assertEquals(
                """{"type":"audio","mime":"audio/flac","data":"${Base64.getEncoder().encodeToString(FLAC)}"}""",
                content[3].toString(),
            )
        }

    @Test
    fun restoredConversationResendsMedia() =
        runTest {
            val (engine, model) = setup()
            engine.fdContents[FD] = WAV
            engine.model.script(Replies.text("a"), Replies.text("b"), Replies.text("b"))
            val original = model.conversation()
            original
                .send(
                    "Look and listen",
                    Content.Image(MediaSource.file(CAT.path), "image/png"),
                    Content.Audio(MediaSource.Descriptor { FD }),
                ).toList()
            val saved = original.toJson()
            original.send("Again").toList()
            engine.fdContents.remove(FD)
            val restored = model.restoreConversation(saved)
            assertEquals(original.messages.value.dropLast(2), restored.messages.value)
            restored.send("Again").toList()
            val (_, before, after) = engine.chats()
            assertEquals(before.body, after.body)
            assertEquals(before.attachments.map { it.name to it.mime }, after.attachments.map { it.name to it.mime })
            assertEquals(CAT.path, (after.attachments[0] as EngineAttachment.Path).path)
            assertArrayEquals(WAV, bytesOf(after.attachments[1]))
        }

    @Test
    fun savesWithInlineImagesStillLoad() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.text("a"))
            val old =
                """{"version":2,"system":null,"settings":{"options":{},"extra":null,"max_turns":10,
                "pre_encode_after_reply":false},"messages":[{"role":"user","content":[{"type":"text","text":"hi"},
                {"type":"image","mime":"image/png","data":"AQID"}]}]}"""
            val chat = model.restoreConversation(old)
            val image = (chat.messages.value.single() as Message.User).content[1] as Content.Image
            assertArrayEquals(byteArrayOf(1, 2, 3), (image.source as MediaSource.Bytes).bytes)
            chat.send("more").toList()
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                bytesOf(
                    engine
                        .chats()
                        .single()
                        .attachments
                        .single(),
                ),
            )
        }

    @Test
    fun audioOnAModelThatCantHearIsInvalidRequest() =
        runTest {
            val (engine, model) = setup(FakeModel.DEFAULT_INFO)
            val chat = model.conversation()
            val error =
                runCatching {
                    chat
                        .send(
                            "hi",
                            Content.Audio(MediaSource.bytes(WAV)),
                        ).toList()
                }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertTrue(error!!.message!!, error.message!!.contains("supportsAudio"))
            assertTrue(chat.messages.value.isEmpty())
            val direct =
                runCatching {
                    model.chat(ChatRequest { messages(Message.user(Content.Audio(MediaSource.bytes(WAV)))) }).toList()
                }.exceptionOrNull()
            assertTrue("$direct", direct is LlamaBroException.InvalidRequest)
            assertTrue(engine.chats().isEmpty())
        }

    @Test
    fun imageOnAModelThatCantSeeIsInvalidRequest() =
        runTest {
            val (engine, model) = setup(FakeModel.DEFAULT_INFO.replace("\"vision\":true", "\"vision\":false"))
            val error =
                runCatching {
                    model.conversation().send("hi", Content.Image(byteArrayOf(1, 2, 3), "image/png")).toList()
                }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertTrue(error!!.message!!, error.message!!.contains("supportsVision"))
            assertTrue(engine.chats().isEmpty())
        }

    @Test
    fun aacIsRefused() =
        runTest {
            val (engine, model) = setup()
            val chat = model.conversation()
            val clips =
                listOf(
                    Content.Audio(MediaSource.bytes(M4A), "audio/mp4"),
                    Content.Audio(MediaSource.bytes(ADTS)),
                    Content.Audio(MediaSource.file("/clips/note.m4a"), "audio/mp4"),
                    Content.Audio(MediaSource.file("/clips/note.aac"), "audio/aac"),
                )
            for (clip in clips) {
                val error = runCatching { chat.send("hi", clip).toList() }.exceptionOrNull()
                assertTrue("$clip: $error", error is LlamaBroException.InvalidRequest)
                assertTrue(error!!.message!!, error.message!!.contains("WAV, MP3 or FLAC"))
            }
            assertTrue(chat.messages.value.isEmpty())
            assertTrue(engine.chats().isEmpty())
        }

    @Test
    fun nativeAudioErrorSaysWhatToDo() =
        runTest {
            val (engine, model) = setup()
            engine.model.onRequest = { r ->
                r.chunk(
                    """{"error":{"code":400,"message":"attachment \"0\" is not WAV, MP3 or FLAC audio, the formats """ +
                        """llama.cpp decodes","type":"invalid_request_error"}}""",
                    status = 400,
                )
                r.done()
            }
            val error =
                runCatching {
                    model.conversation().send("hi", Content.Audio(MediaSource.file(NOTE.path))).toList()
                }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertTrue(error!!.message!!, error.message!!.contains("record WAV"))
        }

    @Test
    fun aMissingFileIsInvalidRequestAndNothingIsAdded() =
        runTest {
            val (engine, model) = setup()
            val chat = model.conversation()
            val error =
                runCatching {
                    chat.send("hi", Content.Audio(MediaSource.file("/clips/gone.wav"))).toList()
                }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertTrue(error!!.message!!, error.message!!.contains("/clips/gone.wav"))
            assertTrue(chat.messages.value.isEmpty())
            assertTrue(engine.chats().isEmpty())
        }

    @Test
    fun aClipFileInAnotherFormatIsInvalidRequestAndNothingIsAdded() =
        runTest {
            val (engine, model) = setup()
            val chat = model.conversation()
            val m4a = File.createTempFile("note", ".m4a").apply { deleteOnExit() }
            m4a.writeBytes(M4A)
            // named a WAV by its MIME type, so only the file's first bytes tell
            val error =
                runCatching {
                    chat
                        .send(
                            "hi",
                            Content.Audio(MediaSource.file(m4a.path)),
                        ).toList()
                }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertTrue(error!!.message!!, error.message!!.contains("WAV, MP3 or FLAC"))
            assertTrue(chat.messages.value.isEmpty())
            assertTrue(engine.chats().isEmpty())
        }

    @Test
    fun aPictureTheDecoderRefusesLeavesTheChatAsItWas() =
        runTest {
            val (engine, model) = setup()
            val chat = model.conversation()
            engine.model.script(Replies.text("Hello."))
            chat.send("hi").toList()
            val before = chat.messages.value
            engine.model.failWith(500, UNDECODABLE)
            val error =
                runCatching {
                    chat.send("What is this?", Content.Image(MediaSource.file(CAT.path), "image/heic")).toList()
                }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertEquals(before, chat.messages.value)
            // the chat still works
            engine.model.script(Replies.text("Sure."))
            chat.send("Never mind.").toList()
            assertEquals(before.size + 2, chat.messages.value.size)
        }

    @Test
    fun aDescriptorClipTheBridgeRefusesIsRemoved() =
        runTest {
            val (engine, model) = setup()
            engine.model.onRequest = { r ->
                r.chunk(
                    """{"error":{"code":400,"message":"attachment \"0\" is not WAV, MP3 or FLAC audio, the formats """ +
                        """llama.cpp decodes","type":"invalid_request_error"}}""",
                    status = 400,
                )
                r.done()
            }
            val chat = model.conversation()
            val error =
                runCatching {
                    chat
                        .send(
                            "hi",
                            Content.Audio(MediaSource.bytes(WAV)),
                        ).toList()
                }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.InvalidRequest)
            assertTrue(chat.messages.value.isEmpty())
        }

    @Test
    fun otherFailuresKeepTheMessageForResume() =
        runTest {
            val (engine, model) = setup()
            val chat = model.conversation()
            // a text-only send keeps its message on any failure, a media one too; resume() sends it again
            for (attachments in listOf(emptyList(), listOf<Content>(Content.Image(MediaSource.file(CAT.path))))) {
                engine.model.failWith(400, Replies.exceed(9000, 8192))
                val error = runCatching { chat.send("hi", *attachments.toTypedArray()).toList() }.exceptionOrNull()
                assertTrue("$error", error is LlamaBroException.ContextExceeded)
                assertTrue(chat.messages.value.last() is Message.User)
            }
            // a decoder error on a text-only send isn't the new message's fault (the history's media is): it stays
            engine.model.failWith(500, UNDECODABLE)
            val before = chat.messages.value.size
            runCatching { chat.send("again").toList() }
            assertEquals(before + 1, chat.messages.value.size)
        }

    @Test
    fun aClosedDescriptorIsInvalidRequest() =
        runTest {
            val (engine, model) = setup()
            // what a closed ParcelFileDescriptor's getFd() throws
            val closed = MediaSource.Descriptor { throw IllegalStateException("Already closed") }
            val chat = model.conversation()
            val inChat = runCatching { chat.send("hi", Content.Audio(closed)).toList() }.exceptionOrNull()
            val direct =
                runCatching {
                    model.chat(ChatRequest { messages(Message.user(Content.Audio(closed))) }).toList()
                }.exceptionOrNull()
            val transcribed = runCatching { model.transcribe(closed) }.exceptionOrNull()
            for (e in listOf(inChat, direct, transcribed)) {
                assertTrue("$e", e is LlamaBroException.InvalidRequest)
                assertTrue(e!!.message!!, e.message!!.contains("descriptor is closed"))
            }
            assertTrue(chat.messages.value.isEmpty())
            assertTrue(engine.chats().isEmpty())
        }

    @Test
    fun toJsonReadsAHistoryDescriptorOnce() =
        runTest {
            val (engine, model) = setup()
            engine.fdContents[FD] = WAV
            engine.model.script(Replies.text("a"))
            val chat = model.conversation(history = listOf(Message.user(Content.Audio(MediaSource.Descriptor { FD }))))
            val saved = chat.toJson()
            assertTrue(saved, saved.contains(Base64.getEncoder().encodeToString(WAV)))
            engine.fdContents.remove(FD) // closed by the app after toJson
            chat.send("more").toList()
            assertEquals(1, engine.fdReads.size)
            assertArrayEquals(
                WAV,
                bytesOf(
                    engine
                        .chats()
                        .single()
                        .attachments
                        .single(),
                ),
            )
        }

    @Test
    fun toJsonDuringASendLosesNoMessages() =
        runTest {
            val (engine, model) = setup()
            engine.fdContents[FD] = WAV
            val toJsonReading = CountDownLatch(1)
            val sendDone = CountDownLatch(1)
            // toJson's read (the first) is slow: it finishes only after the send has appended its messages
            engine.onReadFd = { before ->
                if (before == 0) {
                    toJsonReading.countDown()
                    assertTrue(sendDone.await(10, TimeUnit.SECONDS))
                }
            }
            engine.model.script(Replies.text("reply"))
            val chat = model.conversation(history = listOf(Message.user(Content.Audio(MediaSource.Descriptor { FD }))))
            var saved: String? = null
            val saver = thread { saved = chat.toJson() }
            assertTrue(toJsonReading.await(10, TimeUnit.SECONDS))
            chat.send("more").toList()
            sendDone.countDown()
            saver.join(10_000)
            val messages = chat.messages.value
            assertEquals(messages.toString(), 3, messages.size)
            assertEquals(Message.Assistant("reply"), messages.last())
            val audio = (messages.first() as Message.User).content.single() as Content.Audio
            assertArrayEquals(WAV, (audio.source as MediaSource.Bytes).bytes)
            // toJson saved its own snapshot: the history, read
            assertTrue(saved!!, saved!!.contains(Base64.getEncoder().encodeToString(WAV)))
        }

    @Test
    fun mediaSourcesCompareByContent() {
        assertEquals(MediaSource.file("/a.wav"), MediaSource.file("/a.wav"))
        assertEquals(MediaSource.bytes(byteArrayOf(1)), MediaSource.bytes(byteArrayOf(1)))
        assertEquals(Content.Image(byteArrayOf(1, 2)), Content.Image(MediaSource.bytes(byteArrayOf(1, 2))))
        assertEquals(Content.Audio(MediaSource.file("/a.wav")), Content.Audio(MediaSource.file("/a.wav")))
        assertNotEquals(Content.Audio(MediaSource.file("/a.wav")), Content.Image(MediaSource.file("/a.wav")))
    }

    internal companion object {
        const val FD = 7

        /** The first bytes of a WAV, a FLAC, an MP4/M4A and an ADTS AAC file, as the magic-byte check reads them. */
        val WAV = ascii("RIFF") + byteArrayOf(36, 0, 0, 0) + ascii("WAVEfmt ")
        val FLAC = ascii("fLaC") + byteArrayOf(0, 0, 0, 34, 16, 0, 16, 0)
        val M4A = byteArrayOf(0, 0, 0, 32) + ascii("ftypM4A ") + ByteArray(4)
        val ADTS =
            byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x02, 0x1F, 0xFC.toByte()) +
                ByteArray(8)

        private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

        /** Upstream's decoder refusal (server-common.cpp), a `server_error`. */
        const val UNDECODABLE =
            """{"error":{"code":500,"message":"Failed to load image or audio file","type":"server_error"}}"""

        /** Files that exist, as a file source must when its message is added. */
        val CAT: File = File.createTempFile("cat", ".png").apply { deleteOnExit() }
        val NOTE: File =
            File.createTempFile("note", ".wav").apply {
                deleteOnExit()
                writeBytes(WAV)
            }
    }
}
