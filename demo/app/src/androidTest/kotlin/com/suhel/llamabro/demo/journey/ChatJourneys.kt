package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Talking to a model: switching, editing, regenerating, losing a model, running out of memory. */
@OptIn(ExperimentalTestApi::class)
class ChatJourneys : Journey() {
    private val swift = Catalog.picks[0].key
    private val deep = Catalog.picks[2].key
    private val robot = Robot()

    private fun openSwitcher() {
        compose.onNodeWithTag("model_title").performClick()
        see("ON THIS PHONE")
        compose.waitForIdle()
        Thread.sleep(SHEET_SETTLE)
    }

    private fun askFirstQuestion(text: String = "hi") {
        see("What's on your mind?")
        send(text)
    }

    @Test fun switchModelMidChat() {
        onPhone(swift, deep)
        world.engine.reply { content("Hello from the first model") }
        world.engine.reply { content("Hello from the second model") }
        askFirstQuestion()
        see("Hello from the first model")
        shot("1-first-reply")

        openSwitcher()
        tap("Qwen3.5 4B")
        see("Switched to Qwen3.5 4B")
        shot("2-divider")
        send("and now?")
        see("Hello from the second model")
        shot("3-second-reply")
    }

    @Test fun editAndRegenerate() {
        onPhone(swift)
        world.engine.reply { content("first draft") }
        world.engine.reply { content("a different first draft") }
        world.engine.reply { content("an answer to the edited question") }
        askFirstQuestion("write a haiku")
        see("first draft")
        send("and another")
        see("a different first draft")

        // edit the first question: everything after it is replaced by a fresh answer
        compose.onNode(hasText("write a haiku") and hasAnyAncestor(hasTestTag("user_message"))).performTouchInput {
            longClick()
        }
        see("Edit")
        Thread.sleep(SHEET_SETTLE)
        tap("Edit")
        see("Editing your message")
        send(" about autumn")
        see("an answer to the edited question")
        gone("a different first draft")
        shot("1-edited")

        // regenerate the reply: another take on the same question
        world.engine.reply { content("a fresh take") }
        tapDescription("Regenerate reply")
        see("a fresh take")
        gone("an answer to the edited question")
        shot("2-regenerated")
    }

    @Test fun removeAModelInUse() {
        onPhone(swift, deep)
        world.engine.reply { content("Hello") }
        askFirstQuestion()
        see("Hello")

        tapDescription("Chats")
        tapDrawerModels()
        see("On this phone")
        compose.onNode(hasTestTag("library_model") and hasAnyDescendant(hasText("Qwen3.5 0.8B"))).performTouchInput {
            swipeLeft()
        }
        see("Remove Qwen3.5 0.8B?")
        tap("Remove")
        gone("Remove Qwen3.5 0.8B?")
        tapDescription("Back")

        see("Qwen3.5 0.8B isn't on this phone any more.")
        compose.onAllNodes(hasTestTag("composer")).assertCountEquals(0) // read-only: nothing to type into
        shot("1-read-only")
        tap("Switch model")
        see("ON THIS PHONE")
        Thread.sleep(SHEET_SETTLE)
        tap("Qwen3.5 4B")
        seeTag("composer")
    }

    @Test fun memoryRefusalJourney() {
        onPhone(swift)
        // Qwen3.5 0.8B's repo offers a smaller file than the one on the phone
        val smaller = RepoFile("Qwen3.5-0.8B-Q3_K_M.gguf", "Q3_K_M", 370_000_000)
        val usual = RepoFile("Qwen3.5-0.8B-Q4_K_M.gguf", "Q4_K_M", 532_517_120)
        val best = RepoFile("Qwen3.5-0.8B-Q8_0.gguf", "Q8_0", 875_000_000)
        world.discovery.repoFiles[swift.repo] = listOf(smaller, usual, best)
        world.engine.failLoad(LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory, times = LOAD_ATTEMPTS)
        askFirstQuestion()
        see("Qwen3.5 0.8B needs more memory than this phone has free right now.", substring = true)
        see("Try again")
        shot("1-the-plain-line")

        // "Smaller size" opens this model's own sheet, on Smaller; Get starts that file
        tap("Smaller size")
        see("Smaller")
        see("Recommended")
        Thread.sleep(SHEET_SETTLE)
        shot("2-the-sheet-on-smaller")
        // the chat already runs this model: no "Start a chat" on it; Get names the Smaller file's quant
        compose.onAllNodes(hasTestTag("chat_with")).assertCountEquals(0)
        tap("Get Q3_K_M", substring = true)
        compose.waitUntil(WAIT) { world.downloads.enqueued.isNotEmpty() }
        assertEquals(listOf(HfModel.parse("${swift.repo}:Q3_K_M")), world.downloads.enqueued)
    }

    @Test fun shareAReply() {
        onPhone(swift)
        world.engine.reply { content("A short reply to share.") }
        askFirstQuestion("share this")
        see("A short reply to share.")
        tapDescription("Share reply")
        // the card is drawn and written first, which takes the compose clock's frames: wait through it, not past it
        compose.waitUntil(SHARE_WAIT) { robot.otherAppIsInFront() }
        robot.dismissShareSheet()
        seeTag("composer") // and the chat is as it was
    }

    @Test fun aPhoneWithNoPhotoPickerSaysSoInsteadOfCrashing() {
        assumeFalse("this phone has a photo picker: sendAnImage runs instead", robot.hasPhotoPicker())
        onPhone(swift)
        see("What's on your mind?")
        tapDescription("Add a photo")
        see("This phone has no photo picker.")
        seeTag("composer")
    }

    @Test fun sendAnImage() {
        assumeTrue("this phone has no photo picker", robot.hasPhotoPicker())
        val llama = Catalog.suggested.first { !it.sees }.key
        onPhone(swift, llama)
        robot.addPhotoToGallery()
        // the fake takes pictures only for a model that sees, as a real one does; set before each load (W7)
        world.engine.supportsVision = true
        world.engine.reply { content("A blue picture.") }
        see("What's on your mind?")

        // a model that sees: the picture is chosen in the system's picker, shows as a thumbnail, and is sent
        tapDescription("Add a photo")
        robot.pickFirstPhoto()
        seeDescription("Attached photo")
        shot("1-attached")
        send("what colour is this?")
        see("A blue picture.")
        shot("2-answered")

        // a model that cannot see: the picture is kept, and one plain line says why
        world.engine.supportsVision = false
        openSwitcher()
        tap(Catalog.nameOf(llama))
        see("Switched to ${Catalog.nameOf(llama)}")
        tapDescription("Add a photo")
        robot.pickFirstPhoto()
        seeDescription("Attached photo")
        send("and this one?")
        see("can't see images", substring = true)
        shot("3-cannot-see")
    }

    private companion object {
        const val SHEET_SETTLE = 700L
        const val SHARE_WAIT = 30_000L
        const val LOAD_ATTEMPTS = 100
    }
}
