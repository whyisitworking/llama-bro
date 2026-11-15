package com.suhel.llamabro.demo.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.chat.TurnOptions
import com.suhel.llamabro.demo.chat.sdkStream
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.testing.LocalRecompositionCounter
import com.suhel.llamabro.demo.designsystem.testing.RecompositionCounter
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.CachedEntry
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FakeHubGateway
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import com.suhel.llamabro.demo.ui.chat.ChatScreen
import com.suhel.llamabro.demo.ui.chat.LocalIntentSink
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.shell.ChatChrome
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

/**
 * The chat screen on the phone, over a fake engine and hub: fast and exact, no model needed. Replies come from
 * `FakeLlamaBro`, or from [script] when a test needs to control timing. The screen is hosted bare in an activity so a
 * test can supply the share sink, the link handler and the recomposition counter.
 */
@OptIn(ExperimentalTestApi::class)
class ChatScreenTest {
    private val fake = FakeLlamaBro()
    private val hub = FakeHubGateway()
    private var script: ((String) -> Flow<ChatEvent>)? = null
    private val app =
        TestAppRule(config = {
            TestConfig.fresh(llamaBro = fake.llamaBro, hub = hub, streamer = {
                convo,
                text,
                images,
                options,
                ->
                scripted(convo, text, images, options)
            })
        })
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")

    /** The catalog's model that can neither think nor see. */
    private val plain: HfModel =
        Catalog.suggested
            .first { !it.sees && !it.thinks }
            .key

    /** The engine's streamer: the test's [script] when it set one, otherwise the SDK as the fake serves it. */
    private fun scripted(
        convo: Conversation,
        text: String,
        images: Array<Content.Image>,
        options: TurnOptions,
    ): Flow<ChatEvent> = script?.invoke(text) ?: sdkStream(convo, text, images, options)

    private var wentToModels = false
    private var newChats = 0

    private fun onPhone(vararg models: HfModel) {
        hub.cachedEntries += models.map { CachedEntry(it, 700_000_000) }
        app.container.models.changed()
        runBlocking { app.container.settings.setLastModel(models.first().toString()) }
    }

    private fun reply(text: String) =
        fake.reply {
            content(text)
            timings(promptTokens = 10, predictedTokens = 40)
        }

    private fun show(
        style: MessageStyle = MessageStyle.Open,
        chatId: Long = 0,
        counter: RecompositionCounter? = null,
        intents: ((Intent) -> Unit)? = null,
        links: UriHandler? = null,
        registry: ActivityResultRegistry? = null,
        theme: ThemeMode = ThemeMode.Light,
    ) {
        compose.withAccessibilityChecks()
        // like MainActivity: the window draws edge to edge and the screen pads for the keyboard itself; a bare
        // activity would also resize the window, lifting the message bar twice as far
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            DemoTheme(theme) {
                val locals =
                    buildList {
                        add(LocalRecompositionCounter provides counter)
                        add(LocalIntentSink provides intents)
                        if (links != null) add(LocalUriHandler provides links)
                        if (registry != null) {
                            add(
                                LocalActivityResultRegistryOwner provides
                                    object : ActivityResultRegistryOwner {
                                        override val activityResultRegistry: ActivityResultRegistry = registry
                                    },
                            )
                        }
                    }
                CompositionLocalProvider(*locals.toTypedArray()) {
                    Box(Modifier.background(LocalDemoColors.current.canvas)) {
                        val layout = if (style == MessageStyle.Open) AppLayout.Sidebar else AppLayout.ChatList
                        ChatScreen(
                            app.container,
                            chatId,
                            ChatChrome(layout, {}, {}, { newChats++ }, {
                                wentToModels =
                                    true
                            }),
                            style = style,
                        )
                    }
                }
            }
        }
        settleWindow()
    }

    /**
     * A sheet opens in its own window. On this phone the system shrinks the screen behind it for a moment, and an
     * accessibility check made then reads every button on it as too small. Wait for the window to settle first.
     */
    private fun settleSheet() {
        compose.waitForIdle()
        Thread.sleep(700)
    }

    /**
     * The system zooms a freshly started activity open. An accessibility check made during that zoom reads every button
     * at about three quarters of its size and fails it as too small, so a test starts after the window has settled.
     */
    private fun settleWindow() {
        compose.waitForIdle()
        Thread.sleep(900)
    }

    private fun type(text: String) =
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer"))).performTextInput(text)

    private fun send() = compose.onNodeWithContentDescription("Send").performClick()

    private fun ask(text: String) {
        type(text)
        send()
    }

    private fun waitFor(text: String, substring: Boolean = false, timeout: Long = 15_000, ignoreCase: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring, ignoreCase = ignoreCase), timeout)

    private fun clipboardText(): String? {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text =
                cm.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
        }
        return text
    }

    private fun matchesTime() =
        SemanticsMatcher("shows a time") { node ->
            node.config
                .getOrNull(
                    SemanticsProperties.Text,
                )?.any { Regex("\\d{1,2}:\\d{2}").containsMatchIn(it.text) } ==
                true
        }

    // ---- the empty chat --------------------------------------------------------------------------

    @Test fun emptyChatShowsGreetingAndChips() {
        onPhone(swift)
        show()
        compose.onNodeWithText("What's on your mind?").assertIsDisplayed()
        for (chip in listOf(
            "Plan my week",
            "Explain something simply",
            "Help me write a message",
        )) {
            compose.onNodeWithText(chip).assertIsDisplayed()
        }
        compose.onNodeWithText("Qwen3.5 0.8B").assertIsDisplayed() // the model's name in the top bar
    }

    @Test fun starterChipFillsAndSends() {
        reply("Here's a plan.")
        onPhone(swift)
        show()
        compose.onNodeWithText("Plan my week").performClick()
        waitFor("Here's a plan.")
        compose.onNodeWithTag("user_message").assertIsDisplayed()
        compose.onAllNodesWithText("Plan my week").assertCountEquals(1) // the chips are gone, the message stays
    }

    // ---- sending and streaming -------------------------------------------------------------------

    @Test fun sentMessageAppearsAndReplyStreams() {
        script =
            {
                flow {
                    emit(ChatEvent.ContentDelta("Hello "))
                    delay(400)
                    emit(ChatEvent.ContentDelta("from the phone."))
                }
            }
        onPhone(swift)
        show()
        ask("Say hi")
        waitFor("Say hi")
        waitFor("Hello", substring = true)
        compose.onNodeWithTag("streaming_reply").assertIsDisplayed()
        waitFor("Hello from the phone.")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("streaming_reply")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reply_message").assertIsDisplayed()
    }

    @Test fun stopButtonStops() {
        script = {
            flow {
                emit(ChatEvent.ContentDelta("Starting to write"))
                delay(60_000)
            }
        }
        onPhone(swift)
        show()
        ask("Write a lot")
        waitFor("Starting to write", substring = true)
        compose.onNodeWithContentDescription("Stop").performClick()
        waitFor("Stopped")
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        // what was written is kept
        compose.onAllNodesWithText("Starting to write", substring = true).assertCountEquals(1)
    }

    @Test fun heldMessageShowsTheWaitLine() {
        settingsLastModel(swift)
        hub.status(swift).value = DownloadStatus.Downloading(100, 1_000, 10, 90)
        show()
        ask("Hello when you're ready")
        waitFor("Qwen3.5 0.8B will answer the moment it's here")
        compose.onNodeWithTag("download_ring").assertIsDisplayed()
    }

    @Test fun wakingShowsTheHairlineNotASpinner() {
        hub.onSource = {
            delay(60_000)
            throw IllegalStateException("never loaded")
        }
        onPhone(swift)
        show()
        ask("Hi")
        // the hairline is always in the tree; while the model wakes it says so (and is not a progress indicator)
        compose.waitUntilAtLeastOneExists(
            hasTestTag("ds_wake_hairline") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Waking up"),
            10_000,
        )
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test fun aReplysDetailsSheetShowsItsSpeed() {
        reply("one")
        onPhone(swift)
        show()
        ask("first")
        waitFor("one")
        // the speed is no longer a line under the reply: the info action opens it with the rest of the measurements
        compose.onAllNodesWithText("words/s", substring = true).assertCountEquals(0)
        compose.waitUntil(10_000) { details().fetchSemanticsNodes().isNotEmpty() }
        details().onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reply_details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Speed").assertExists()
        compose.onNodeWithText("words/s", substring = true).assertExists()
    }

    private fun details() = compose.onAllNodesWithContentDescription("Reply details")

    @Test fun openStyleHasNoModelBubble() {
        reply("a reply")
        onPhone(swift)
        show(MessageStyle.Open)
        ask("hello")
        waitFor("a reply")
        compose.waitUntil(10_000) { details().fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(matchesTime()).assertCountEquals(0) // no time in a corner: replies sit on the page
        compose.onAllNodesWithText("·", substring = true).assertCountEquals(0)
    }

    @Test fun bubbleStyleShowsTimes() {
        reply("a reply")
        onPhone(swift)
        show(MessageStyle.Bubbles)
        ask("hello")
        waitFor("a reply")
        compose.waitUntil(10_000) { details().fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(matchesTime()).fetchSemanticsNodes().let {
            assertTrue(
                "a time in each bubble: ${it.size}",
                it.size >= 2,
            )
        }
        waitFor("Today")
    }

    @Test fun dayLabelsSeparateDays() {
        onPhone(swift)
        val now = System.currentTimeMillis()
        val id =
            runBlocking {
                val chat = app.container.chats.createChat(swift.toString())
                app.container.chats.append(
                    chat,
                    NewMessage(
                        MessageKind.User,
                        "from yesterday",
                        sentAt =
                            now - 24 * 3_600_000,
                    ),
                )
                app.container.chats.append(chat, NewMessage(MessageKind.User, "from today", sentAt = now))
                chat
            }
        show(MessageStyle.Bubbles, id)
        waitFor("Yesterday")
        waitFor("Today")
    }

    // ---- thinking --------------------------------------------------------------------------------

    @Test fun thinkMenuOffersFourLevels() {
        onPhone(swift)
        show()
        compose.onNodeWithContentDescription("Think").performClick() // the bulb in the bar's button row
        settleSheet()
        for (level in listOf("Off", "Quick", "Balanced", "Deep")) compose.onNodeWithText(level).assertIsDisplayed()
        compose.onNodeWithText("Balanced").performClick()
        // the chosen level is in the bulb's description
        compose.waitUntilAtLeastOneExists(hasContentDescription("Think · Balanced"), 15_000)
    }

    @Test fun foldedThoughtExpands() {
        script =
            {
                flow {
                    emit(ChatEvent.ReasoningDelta("weighing the question"))
                    delay(300)
                    emit(ChatEvent.ContentDelta("The answer."))
                }
            }
        onPhone(swift)
        show()
        ask("Why?")
        waitFor("The answer.")
        compose.waitUntilAtLeastOneExists(hasText("Thought for", substring = true), 10_000)
        compose.onAllNodesWithText("weighing the question").assertCountEquals(0)
        compose.onNodeWithText("Thought for", substring = true).performClick()
        waitFor("weighing the question")
    }

    @Test fun liveThoughtsOpenWhileThinking() {
        script = {
            flow {
                emit(ChatEvent.ReasoningDelta("still deciding"))
                delay(60_000)
            }
        }
        onPhone(swift)
        show()
        ask("Hmm")
        waitFor("Thinking")
        compose.onNodeWithText("Thinking").performClick()
        waitFor("still deciding")
    }

    // ---- actions ---------------------------------------------------------------------------------

    @Test fun copyReplyPutsTextOnClipboard() {
        reply("Copy me please.")
        onPhone(swift)
        show()
        ask("hi")
        waitFor("Copy me please.")
        compose.waitUntilAtLeastOneExists(
            hasText("Copy reply").or(
                androidx.compose.ui.test
                    .hasContentDescription("Copy reply"),
            ),
            10_000,
        )
        compose.onNodeWithContentDescription("Copy reply").performClick()
        assertEquals("Copy me please.", clipboardText())
    }

    @Test fun codeBlockCopyPutsCodeOnClipboard() {
        reply("Try this:\n```kotlin\nprintln(\"hi\")\n```")
        onPhone(swift)
        show()
        ask("code?")
        waitFor("println", substring = true)
        compose.onNodeWithContentDescription("Copy").performClick()
        assertEquals("println(\"hi\")", clipboardText())
    }

    @Test fun regenerateShowsNewReply() {
        reply("first draft")
        reply("second draft")
        onPhone(swift)
        show()
        ask("write")
        waitFor("first draft")
        compose.waitUntilAtLeastOneExists(
            androidx.compose.ui.test
                .hasContentDescription("Regenerate reply"),
            10_000,
        )
        compose.onNodeWithContentDescription("Regenerate reply").performClick()
        waitFor("second draft")
        compose.onAllNodesWithText("first draft").assertCountEquals(0)
    }

    @Test fun linkOpensTheBrowser() {
        reply("See [the docs](https://example.com/docs).")
        onPhone(swift)
        val opened = mutableListOf<String>()
        show(
            links =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        opened += uri
                    }
                },
        )
        ask("link")
        waitFor("the docs", substring = true)
        compose.onNodeWithText("the docs", substring = true).performTouchInput {
            click(
                androidx.compose.ui.geometry
                    .Offset(200f, 45f),
            )
        } // over "the docs" at the line's start
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals("https://example.com/docs", opened.single())
    }

    @Test fun shareProducesAPngIntent() {
        reply("A short reply to share.")
        onPhone(swift)
        val sent = mutableListOf<Intent>()
        show(intents = { sent += it })
        ask("share this")
        waitFor("A short reply to share.")
        compose.waitUntilAtLeastOneExists(
            androidx.compose.ui.test
                .hasContentDescription("Share reply"),
            10_000,
        )
        compose.onNodeWithContentDescription("Share reply").performClick()
        compose.waitUntil(15_000) { sent.isNotEmpty() }
        val intent = sent.single()
        assertEquals("image/png", intent.type)
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        val bitmap =
            compose.activity.contentResolver
                .openInputStream(uri)!!
                .use { BitmapFactory.decodeStream(it) }
        assertNotNull("the card decodes as a PNG", bitmap)
        assertEquals(1080, bitmap.width)
        assertTrue("card height ${bitmap.height}", bitmap.height in 100..2_000)
    }

    @Test fun longPressOnAMessageOpensItsActions() {
        reply("Press me.")
        onPhone(swift)
        show()
        ask("question")
        waitFor("Press me.")
        compose
            .onNode(
                hasText("question") and hasAnyAncestor(hasTestTag("user_message")),
            ).performTouchInput { longClick() }
        settleSheet()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        assertEquals("question", clipboardText())
    }

    // ---- the model switcher ----------------------------------------------------------------------

    @Test fun switcherListsLibraryWithDefaultChip() {
        onPhone(swift, deep)
        runBlocking { app.container.settings.pinDefault(deep.toString()) }
        show()
        compose.onNodeWithTag("model_title").performClick()
        settleSheet()
        waitFor("On this phone", ignoreCase = true)
        waitFor("Qwen3.5 0.8B")
        waitFor("Qwen3.5 4B")
        waitFor("Default")
        compose.onNodeWithText("Get more models").assertIsDisplayed()
    }

    @Test fun longPressPinsDefault() {
        onPhone(swift, deep)
        show()
        compose.onNodeWithTag("model_title").performClick()
        settleSheet()
        waitFor("On this phone", ignoreCase = true)
        compose.onNodeWithText("Qwen3.5 4B").performTouchInput { longClick() }
        compose.onNodeWithText("Use for new chats").performClick() // the long-press says what it will do first
        waitFor("Default")
        assertEquals(
            deep.toString(),
            runBlocking {
                app.container.settings.settings
                    .first()
            }.defaultModel,
        )
    }

    @Test fun switcherGetMoreOpensGetModels() {
        onPhone(swift)
        show()
        compose.onNodeWithTag("model_title").performClick()
        settleSheet()
        compose.onNodeWithText("Get more models").performClick()
        compose.waitUntil(5_000) { wentToModels }
    }

    @Test fun switchingModelsLeavesADivider() {
        reply("hello")
        onPhone(swift, deep)
        show()
        ask("hi")
        waitFor("hello")
        compose.onNodeWithTag("model_title").performClick()
        settleSheet()
        compose.onNodeWithText("Qwen3.5 4B").performClick()
        waitFor("Switched to Qwen3.5 4B")
    }

    @Test fun tappingTheCurrentModelDoesNotSwitch() {
        reply("hello")
        onPhone(swift, deep)
        show()
        ask("hi")
        waitFor("hello")
        compose.onNodeWithTag("model_title").performClick()
        settleSheet()
        compose.onNode(hasText("Qwen3.5 0.8B") and hasAnyAncestor(hasTestTag("switcher"))).performClick()
        compose.waitForIdle()
        Thread.sleep(500)
        compose.onAllNodesWithText("Switched to", substring = true).assertCountEquals(0)
    }

    @Test fun thinkBulbHiddenWhenTheModelCantThink() {
        onPhone(plain)
        show()
        waitFor("Message ${com.suhel.llamabro.demo.models.Catalog.nameOf(plain)}")
        compose.onAllNodesWithContentDescription("Think", substring = true).assertCountEquals(0)
    }

    // ---- limits ----------------------------------------------------------------------------------

    @Test fun limitLineShowsItsAction() {
        // a chat whose model was removed from the phone: one plain line and one button
        val id =
            runBlocking {
                app.container.chats.createChat(deep.toString()).also {
                    app.container.chats.append(it, NewMessage(MessageKind.User, "an old question"))
                }
            }
        onPhone(swift)
        show(chatId = id)
        waitFor("Qwen3.5 4B isn't on this phone any more.")
        compose.onNodeWithText("Switch model").performClick()
        settleSheet()
        waitFor("On this phone", ignoreCase = true)
    }

    @Test fun readOnlyChatHidesTheComposer() {
        val id =
            runBlocking {
                app.container.chats.createChat(deep.toString()).also {
                    app.container.chats.append(it, NewMessage(MessageKind.User, "an old question"))
                }
            }
        onPhone(swift)
        show(chatId = id)
        waitFor("an old question")
        compose.onAllNodes(hasTestTag("composer")).assertCountEquals(0)
    }

    @Test fun contextExceededOffersANewChat() {
        fake.contextExceeded(promptTokens = 5_000, contextSize = 4_096)
        onPhone(swift)
        show()
        ask("a very long message")
        waitFor("This chat is longer than Qwen3.5 0.8B can hold.")
        compose.onNodeWithText("Start a new chat").performClick()
        compose.waitUntil(5_000) { newChats == 1 }
    }

    // ---- photos ----------------------------------------------------------------------------------

    private fun pickerReturning(file: File) =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, Uri.fromFile(file))
            }
        }

    private fun testPicture(): File {
        val file = File(compose.activity.cacheDir, "pick.jpg")
        file.outputStream().use {
            Bitmap
                .createBitmap(300, 200, Bitmap.Config.ARGB_8888)
                .apply {
                    eraseColor(0xFF3366CC.toInt())
                }.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        return file
    }

    @Test fun photoPickerAttachesAndShowsThumbnail() {
        onPhone(swift)
        show(registry = pickerReturning(testPicture()))
        compose.onNodeWithContentDescription("Add a photo").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("attachments"), 10_000)
        compose.waitUntilAtLeastOneExists(
            androidx.compose.ui.test
                .hasContentDescription("Attached photo"),
            10_000,
        ) // decoded and drawn
        compose.onNodeWithContentDescription("Remove photo").assertIsDisplayed()
    }

    @Test fun aPictureSentToAModelThatCantSeeSaysSo() {
        onPhone(plain)
        show(registry = pickerReturning(testPicture()))
        compose.onNodeWithContentDescription("Add a photo").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("attachments"), 10_000)
        type("what is this?")
        send()
        waitFor("can't see images", substring = true)
        compose.onAllNodes(hasTestTag("user_message")).assertCountEquals(0) // nothing was sent, nothing was lost
        compose.onNodeWithContentDescription("Remove photo").assertIsDisplayed()
    }

    @Test fun aPhotoThatCantBeOpenedSaysSoWithoutCrashing() {
        val bad = File(compose.activity.cacheDir, "bad.jpg").apply { writeText("this is not an image") }
        onPhone(swift)
        show(registry = pickerReturning(bad))
        compose.onNodeWithContentDescription("Add a photo").performClick()
        waitFor("That photo couldn't be opened.")
        compose.onAllNodes(hasTestTag("attachments")).assertCountEquals(0)
    }

    @Test fun removeAttachedImageBeforeSending() {
        onPhone(swift)
        show(registry = pickerReturning(testPicture()))
        compose.onNodeWithContentDescription("Add a photo").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("attachments"), 10_000)
        compose.onNodeWithContentDescription("Remove photo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("attachments")).fetchSemanticsNodes().isEmpty() }
    }

    // ---- performance ----------------------------------------------------------------------------

    /** [pairs] questions and answers: few enough that every row stays on screen, so none leaves and re-enters the list. */
    private fun seedLongChat(pairs: Int = 5): Long =
        runBlocking {
            val chat = app.container.chats.createChat(swift.toString())
            repeat(pairs) {
                app.container.chats.append(chat, NewMessage(MessageKind.User, "question $it"))
                app.container.chats.append(
                    chat,
                    NewMessage(MessageKind.Assistant, "answer $it", modelKey = swift.toString()),
                )
            }
            chat
        }

    @Test fun onlyTheStreamingReplyRecomposes() {
        val counter = RecompositionCounter()
        script = {
            flow {
                repeat(30) {
                    emit(ChatEvent.ContentDelta("w$it "))
                    delay(80)
                }
            }
        }
        onPhone(swift)
        show(counter = counter, chatId = seedLongChat(pairs = 2))
        waitFor("answer 1")
        ask("one more")
        compose.waitUntilAtLeastOneExists(hasTestTag("streaming_reply"), 10_000)
        compose.waitForIdle() // the list has finished scrolling to the new rows: only what recomposes from here counts
        counter.reset()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("streaming_reply")).fetchSemanticsNodes().isEmpty() }
        val earlier = counter.counts.filterKeys { it != "row-streaming" }.filterKeys { k -> k.startsWith("row-m-") }
        // the rows that existed before the turn don't recompose (the finished reply's row is new, so it may compose once)
        // four seeded rows and the new question
        val old = earlier.filterKeys { k -> k.removePrefix("row-m-").toLong() <= 5L }
        // the one exception: the reply that was the latest loses its Copy, Share and Regenerate actions when the new
        // reply starts, so it recomposes a couple of times, never once per token (30 tokens arrive in this window)
        val previousLatest = "row-m-4"
        // (a row may recompose once for a reason outside the stream, such as the list settling: seen once in about
        // five full runs; what is excluded is a recomposition per token, which would be 30)
        assertTrue("earlier rows recomposed: $old", old.filterKeys { it != previousLatest }.values.all { it <= 1 })
        assertTrue("the previous latest reply recomposed ${old[previousLatest]} times", (old[previousLatest] ?: 0) <= 2)
        assertTrue(
            "the streaming row recomposed ${counter.counts["row-streaming"]} times",
            (counter.counts["row-streaming"] ?: 0) <= 32,
        ) // 30 tokens, plus its start and end
    }

    @Test fun typingDoesNotRecomposeTheMessages() {
        val counter = RecompositionCounter()
        onPhone(swift)
        show(counter = counter, chatId = seedLongChat())
        waitFor("answer 4")
        // a message partly scrolled under the top bar is clipped to less than 48 dp: not what this test is about
        compose.disableAccessibilityChecks()
        counter.reset()
        repeat(20) { type("x") }
        val rows = counter.counts.filterKeys { it.startsWith("row-m-") }
        assertTrue("rows recomposed while typing: $rows", rows.values.all { it == 0 })
    }

    // ---- type ------------------------------------------------------------------------------------

    @Test fun serifOnlyWhereTheSpecSays() {
        reply("A plain reply.")
        onPhone(swift)
        show()

        fun familyOf(text: String) =
            SemanticsActions.GetTextLayoutResult.let {
                val results = mutableListOf<TextLayoutResult>()
                compose
                    .onNodeWithText(text)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(it)
                    ?.action
                    ?.invoke(results)
                results
                    .single()
                    .layoutInput.style.fontFamily
            }
        assertEquals(DemoType.displayHero.fontFamily, familyOf("What's on your mind?"))
        ask("hello")
        waitFor("A plain reply.")
        assertEquals(DemoType.body.fontFamily, familyOf("A plain reply."))
        assertFalse(DemoType.body.fontFamily == DemoType.displayHero.fontFamily)
    }

    private fun settingsLastModel(m: HfModel) = runBlocking { app.container.settings.setLastModel(m.toString()) }
}
