package com.suhel.llamabro.demo.data

import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.demoRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ChatRepositoryTest {
    private val now = AtomicLong(1_000)
    private val app = TestAppRule(clock = { now.incrementAndGet() })

    @get:Rule val rules = demoRules(app)
    private val chats: ChatRepository get() = app.container.chats

    private suspend fun chatWith(firstMessage: String, images: List<String> = emptyList()): Long {
        val id = chats.createChat("r/m:Q4")
        chats.append(id, NewMessage(MessageKind.User, firstMessage, images))
        return id
    }

    private suspend fun titleOf(id: Long) =
        chats
            .chats()
            .first()
            .first { it.id == id }
            .title

    @Test fun newChatHasAPlaceholderTitleUntilTheFirstUserMessage() =
        runBlocking<Unit> {
            val id = chats.createChat("r/m:Q4")
            assertEquals("New chat", titleOf(id))
            chats.append(id, NewMessage(MessageKind.User, "Plan my week"))
            chats.append(id, NewMessage(MessageKind.User, "second message"))
            assertEquals("Plan my week", titleOf(id))
        }

    @Test fun titleIsFirstLineCutAt40OnAWord() =
        runBlocking<Unit> {
            val long = "Please explain how photosynthesis works in plain words\nsecond line"
            assertEquals("Please explain how photosynthesis works…", titleOf(chatWith(long)))
            assertEquals(
                "exactly forty characters, no cut here!!",
                titleOf(chatWith("exactly forty characters, no cut here!!")),
            )
            assertEquals("a".repeat(40) + "…", titleOf(chatWith("a".repeat(60))))
            assertEquals("Hello", titleOf(chatWith("\n  Hello \nworld")))
        }

    @Test fun imageOnlyMessageTitleIsImage() =
        runBlocking<Unit> {
            val id = chatWith("", listOf("a.jpg"))
            assertEquals("Image", titleOf(id))
            assertEquals(
                "Image",
                chats
                    .chats()
                    .first()
                    .single()
                    .lastText,
            )
        }

    @Test fun blankTitleRenameIsRejected() =
        runBlocking<Unit> {
            val id = chatWith("Hello")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { chats.rename(id, "   ") } }
            assertEquals("Hello", titleOf(id))
            chats.rename(id, "  Groceries ")
            assertEquals("Groceries", titleOf(id))
        }

    @Test fun searchMatchesTitlesAndMessageText() =
        runBlocking<Unit> {
            val bread = chatWith("Sourdough starter help")
            chats.append(bread, NewMessage(MessageKind.Assistant, "Feed it flour and water daily"))
            val trip = chatWith("Trip to Lisbon")
            chats.append(trip, NewMessage(MessageKind.Assistant, "Take the tram 28"))
            assertEquals(
                setOf(bread),
                chats
                    .chats("sourdough")
                    .first()
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                setOf(trip),
                chats
                    .chats("TRAM")
                    .first()
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(2, chats.chats("").first().size)
            assertEquals(2, chats.chats("   ").first().size)
            assertTrue(chats.chats("zzz").first().isEmpty())
        }

    @Test fun searchTreatsPercentAndUnderscoreLiterally() =
        runBlocking<Unit> {
            chatWith("save 50% today")
            chatWith("snake_case names")
            chatWith("plain words")
            assertEquals(listOf("save 50% today"), chats.chats("50%").first().map { it.title })
            assertEquals(listOf("snake_case names"), chats.chats("e_c").first().map { it.title })
            assertTrue(chats.chats("%").first().size == 1)
        }

    @Test fun chatsAreNewestActivityFirstAndPreviewNamesTheLastSender() =
        runBlocking<Unit> {
            val first = chatWith("first")
            val second = chatWith("second")
            assertEquals(listOf(second, first), chats.chats().first().map { it.id })
            chats.append(first, NewMessage(MessageKind.Assistant, "reply to first", modelKey = "r/m:Q8"))
            val top = chats.chats().first().first()
            assertEquals(first, top.id)
            assertEquals("reply to first", top.lastText)
            assertFalse(top.lastFromUser)
            assertEquals("r/m:Q8", top.modelKey)
            assertTrue(
                chats
                    .chats()
                    .first()
                    .last()
                    .lastFromUser,
            )
        }

    @Test fun truncateFromRemovesThatPositionAndAfter() =
        runBlocking<Unit> {
            val id = chatWith("one")
            chats.append(id, NewMessage(MessageKind.Assistant, "two"))
            chats.append(id, NewMessage(MessageKind.User, "three"))
            chats.append(id, NewMessage(MessageKind.Assistant, "four"))
            chats.truncateFrom(id, 2)
            assertEquals(listOf("one", "two"), chats.messages(id).first().map { it.text })
            val next = chats.append(id, NewMessage(MessageKind.User, "again"))
            assertEquals(
                2,
                chats
                    .messages(id)
                    .first()
                    .single { it.id == next }
                    .position,
            )
        }

    @Test fun streamingRowsBecomeStoppedOnOpen() =
        runBlocking<Unit> {
            val id = chatWith("hello")
            val streaming = chats.append(id, NewMessage(MessageKind.Assistant, "", state = MessageState.Streaming))
            chats.update(streaming, MessageChange(text = "Hel"))
            val held = chats.append(id, NewMessage(MessageKind.User, "while waking", state = MessageState.Held))
            chats.recoverInterrupted()
            val byId = chats.messages(id).first().associateBy { it.id }
            assertEquals(MessageState.Stopped, byId.getValue(streaming).state)
            assertEquals("Hel", byId.getValue(streaming).text)
            assertEquals(MessageState.Held, byId.getValue(held).state)
        }

    @Test fun updateChangesOnlyWhatItNamesAndClearsAFailureOnRecovery() =
        runBlocking<Unit> {
            val id = chatWith("hello")
            val reply = chats.append(id, NewMessage(MessageKind.Assistant, "x", state = MessageState.Streaming))
            chats.update(reply, MessageChange(state = MessageState.Failed, failure = "Not enough memory"))
            chats.messages(id).first().last().let {
                assertEquals(MessageState.Failed, it.state)
                assertEquals("Not enough memory", it.failure)
                assertEquals("x", it.text)
            }
            chats.update(
                reply,
                MessageChange(
                    text = "xyz",
                    reasoning = "hmm",
                    reasoningMillis = 1200,
                    wordsPerSecond = 7.5,
                    state = MessageState.Done,
                ),
            )
            chats.messages(id).first().last().let {
                assertEquals(MessageState.Done, it.state)
                assertNull(it.failure)
                assertEquals("xyz", it.text)
                assertEquals("hmm", it.reasoning)
                assertEquals(1200L, it.reasoningMillis)
                assertEquals(7.5, it.wordsPerSecond)
            }
        }

    @Test fun streamingTextDoesNotMoveAChatButAStateChangeDoes() =
        runBlocking<Unit> {
            val a = chatWith("a")
            val b = chatWith("b")
            val reply = chats.append(a, NewMessage(MessageKind.Assistant, "", state = MessageState.Streaming))
            val b2 = chats.append(b, NewMessage(MessageKind.Assistant, "later"))
            assertEquals(
                b,
                chats
                    .chats()
                    .first()
                    .first()
                    .id,
            )
            chats.update(reply, MessageChange(text = "tokens"))
            assertEquals(
                b,
                chats
                    .chats()
                    .first()
                    .first()
                    .id,
            )
            chats.update(reply, MessageChange(state = MessageState.Done))
            assertEquals(
                a,
                chats
                    .chats()
                    .first()
                    .first()
                    .id,
            )
            assertEquals(
                b2,
                chats
                    .messages(b)
                    .first()
                    .last()
                    .id,
            )
        }

    @Test fun deletingAChatDeletesItsImages() =
        runBlocking<Unit> {
            val images = app.container.images
            val kept = images.file("kept.jpg")
            val gone1 = images.file("gone1.jpg")
            val gone2 = images.file("gone2.jpg")
            gone1.parentFile!!.mkdirs()
            listOf(kept, gone1, gone2).forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val doomed = chatWith("look", listOf("gone1.jpg", "gone2.jpg"))
            chatWith("also look", listOf("kept.jpg"))
            chats.delete(doomed)
            assertFalse(gone1.exists())
            assertFalse(gone2.exists())
            assertTrue(kept.exists())
            assertEquals(1, chats.chats().first().size)
            assertTrue(chats.messages(doomed).first().isEmpty())
        }

    @Test fun storedMessagesResolveImageFiles() =
        runBlocking<Unit> {
            val id = chatWith("look", listOf("a.jpg", "b.jpg"))
            val stored = chats.messages(id).first().single()
            assertEquals(listOf(app.container.images.file("a.jpg"), app.container.images.file("b.jpg")), stored.images)
            assertEquals(MessageKind.User, stored.kind)
            assertEquals(MessageState.Done, stored.state)
        }

    @Test fun emojiAndRtlSurviveRoundTrip() =
        runBlocking<Unit> {
            val text = "Hello 👩🏽‍💻🦙 مرحبا بالعالم שלום עולם ‮abc‬ ñ 你好"
            val id = chatWith(text)
            assertEquals(
                text,
                chats
                    .messages(id)
                    .first()
                    .single()
                    .text,
            )
            assertEquals(
                text,
                chats
                    .chats()
                    .first()
                    .single()
                    .lastText,
            )
        }

    @Test fun messageOf100kCharactersIsStoredWhole() =
        runBlocking<Unit> {
            val text = buildString { while (length < 100_000) append("The quick brown fox. ") }.take(100_000)
            val id = chatWith(text)
            val back =
                chats
                    .messages(id)
                    .first()
                    .single()
                    .text
            assertEquals(100_000, back.length)
            assertEquals(text, back)
        }
}
