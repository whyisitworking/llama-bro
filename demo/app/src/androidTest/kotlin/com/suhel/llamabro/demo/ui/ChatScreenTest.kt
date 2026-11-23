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
import androidx.compose.ui.geometry.Offset
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
import androidx.test.uiautomator.UiDevice
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.chat.TurnOptions
import com.suhel.llamabro.demo.chat.sdkStream
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.designsystem.testing.LocalRecompositionCounter
import com.suhel.llamabro.demo.designsystem.testing.RecompositionCounter
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.testing.settleWindow
import com.suhel.llamabro.demo.testing.withAccessibilityChecks
import com.suhel.llamabro.demo.ui.chat.ChatScreen
import com.suhel.llamabro.demo.ui.chat.LocalIntentSink
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.shell.ChatChrome
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
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
    private val hub = TestHub()
    private var script: ((String) -> Flow<ChatEvent>)? = null
    private val app =
        TestAppRule(config = {
            TestConfig.fresh(llamaBro = fake.llamaBro, hub = hub.hub, streamer = {
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

    @After fun closeHub() = hub.close()

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
        media: Array<Content>,
        options: TurnOptions,
    ): Flow<ChatEvent> = script?.invoke(text) ?: sdkStream(convo, text, media, options)

    private var wentToModels = false
    private var newChats = 0

    private fun onPhone(vararg models: HfModel) {
        hub.install(*models)
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
                            styleOverride = style,
                        )
                    }
                }
            }
        }
        compose.settleWindow()
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
        // the reply waits for the test to have seen it streaming: a fixed pause can pass before a slow device looks
        val seen = CompletableDeferred<Unit>()
        script =
            {
                flow {
                    emit(ChatEvent.ContentDelta("Hello "))
                    seen.await()
                    emit(ChatEvent.ContentDelta("from the phone."))
                }
            }
        onPhone(swift)
        show()
        ask("Say hi")
        waitFor("Say hi")
        waitFor("Hello", substring = true)
        compose.onNodeWithTag("streaming_reply").assertIsDisplayed()
        seen.complete(Unit)
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

    /** A reply of [lines] short paragraphs, taller than the screen, arriving one every 40 ms. */
    private fun longReply(lines: Int) {
        script = {
            flow {
                repeat(lines) {
                    emit(ChatEvent.ContentDelta("line $it\n\n"))
                    delay(40)
                }
            }
        }
    }

    /**
     * Where the reply's row starts, above the screen once the reader is inside it (unclipped, so it moves with the
     * list): the streaming row, or the finished reply that took its place.
     */
    private fun replyTop(): Float {
        val streaming = compose.onAllNodes(hasTestTag("streaming_reply")).fetchSemanticsNodes()
        val node = streaming.firstOrNull() ?: compose.onAllNodesWithTag("reply_message").fetchSemanticsNodes().last()
        return node.positionInRoot.y
    }

    /**
     * The reader drags the list toward older messages and holds still before lifting the finger, so no fling follows:
     * the list rests where the finger left it, and what a test reads next is not a list still moving.
     */
    private fun dragTowardOlderMessages() {
        compose.onNodeWithTag("message_list").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, height / 30f), delayMillis = 30) }
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
    }

    @Test fun scrollingUpDuringAReplyStopsFollowingIt() {
        longReply(400)
        onPhone(swift)
        show()
        ask("Write a lot")
        waitFor("line 40", substring = true)
        dragTowardOlderMessages()
        val top = replyTop()
        // tokens keep arriving, and the list stays where the reader left it
        waitFor("line 80", substring = true)
        assertEquals(top, replyTop())
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
        // back to the latest, and the reply is followed again: the button stays away while it grows
        compose.onNodeWithTag("jump_to_latest").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("jump_to_latest")).fetchSemanticsNodes().isEmpty() }
        waitFor("line 140", substring = true)
        compose.onAllNodes(hasTestTag("jump_to_latest")).assertCountEquals(0)
        compose.onNodeWithContentDescription("Stop").performClick()
    }

    @Test fun aReplyFinishingLeavesAReaderWhoScrolledUpWhereTheyAre() {
        longReply(120)
        onPhone(swift)
        show()
        ask("Write a lot")
        waitFor("line 40", substring = true)
        dragTowardOlderMessages()
        val top = replyTop()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("streaming_reply")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertEquals(top, replyTop())
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
    }

    @Test fun heldMessageShowsTheWaitLine() {
        settingsLastModel(swift)
        hub.downloading(swift)
        show()
        // the ring comes once the library, the chat's model and the download's state have all arrived: the top bar
        // must have it before the message is sent, or the send's accessibility check meets a bar still laying out
        compose.waitUntilAtLeastOneExists(hasTestTag("download_ring"), 10_000)
        ask("Hello when you're ready")
        waitFor("Qwen3.5 0.8B will answer the moment it's here")
        compose.onNodeWithTag("download_ring").assertIsDisplayed()
    }

    @Test fun wakingShowsTheHairlineNotASpinner() {
        fake.loadMillis = LONG_LOAD_MILLIS // the model wakes for longer than the test looks
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
        compose.settleWindow()
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
        compose.settleWindow()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        assertEquals("question", clipboardText())
    }

    // ---- the model switcher ----------------------------------------------------------------------

    @Test fun switcherSaysCurrentOnTheChatsModelAndDefaultOnThePinnedOne() {
        // the chat runs swift while new chats use deep: each row's state word says which (models-page design §7)
        onPhone(swift, deep)
        runBlocking { app.container.settings.pinDefault(deep.toString()) }
        val id =
            runBlocking {
                app.container.chats.createChat(swift.toString()).also {
                    app.container.chats.append(it, NewMessage(MessageKind.User, "a question"))
                }
            }
        show(chatId = id)
        compose.onNodeWithTag("model_title").performClick()
        compose.settleWindow()
        waitFor("On this phone", ignoreCase = true)
        val inSwitcher = hasAnyAncestor(hasTestTag("switcher"))
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(hasText("Qwen3.5 0.8B") and hasText("Current") and inSwitcher)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNode(hasText("Qwen3.5 4B") and hasText("Default") and inSwitcher).assertExists()
        // the fit is a symbol, its words only what a screen reader says, on every row
        val fitWords = listOf("Runs great", "Runs well", "Slow here", "Too big")
        for (name in listOf("Qwen3.5 0.8B", "Qwen3.5 4B")) {
            val fit = fitWords.map { hasContentDescription(it) }.reduce { x, y -> x or y }
            compose.onNode(hasText(name) and fit and inSwitcher).assertExists()
        }
        compose.onAllNodesWithText("Runs great").assertCountEquals(0)
        compose.onNodeWithContentDescription("About Qwen3.5 4B").assertIsDisplayed()
        compose.onNodeWithText("Get more models").assertIsDisplayed()
    }

    @Test fun longPressPinsDefault() {
        onPhone(swift, deep)
        show()
        compose.onNodeWithTag("model_title").performClick()
        compose.settleWindow()
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

    @Test fun switcherGetMoreOpensModels() {
        onPhone(swift)
        show()
        compose.onNodeWithTag("model_title").performClick()
        compose.settleWindow()
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
        compose.settleWindow()
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
        compose.settleWindow()
        compose.onNode(hasText("Qwen3.5 0.8B") and hasAnyAncestor(hasTestTag("switcher"))).performClick()
        compose.waitForIdle()
        Thread.sleep(500)
        compose.onAllNodesWithText("Switched to", substring = true).assertCountEquals(0)
    }

    @Test fun thinkBulbHiddenWhenTheModelCantThink() {
        onPhone(plain)
        show()
        waitFor("Message ${com.suhel.llamabro.demo.models.Names.of(plain)}")
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
        compose.settleWindow()
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

    /** A chat whose one message carries two photos. */
    private fun seedPhotoChat(): Long =
        runBlocking {
            val names =
                listOf(0xFF3366CC, 0xFFCC6633).mapIndexed { i, colour ->
                    val name = "viewer-$i.jpg"
                    app.container.images.file(name).apply { parentFile?.mkdirs() }.outputStream().use {
                        Bitmap
                            .createBitmap(400, 300, Bitmap.Config.ARGB_8888)
                            .apply { eraseColor(colour.toInt()) }
                            .compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    name
                }
            val chat = app.container.chats.createChat(swift.toString())
            app.container.chats.append(chat, NewMessage(MessageKind.User, "which one?", images = names))
            chat
        }

    private fun closeButtons() = compose.onAllNodesWithContentDescription("Close photo").fetchSemanticsNodes()

    @Test fun tappingAPhotoOpensItFullScreenAndTheCrossOrBackClosesIt() {
        onPhone(swift)
        show(chatId = seedPhotoChat())
        compose.waitUntilAtLeastOneExists(hasContentDescription("Photo 2 of 2"), 10_000)
        compose.onNodeWithContentDescription("Photo 2 of 2").performClick()
        compose.waitUntil(5_000) { closeButtons().isNotEmpty() }
        compose.settleWindow()
        // the bubble's photo and the viewer's page, opened at the photo tapped
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Photo 2 of 2").fetchSemanticsNodes().size == 2
        }
        compose.onNodeWithContentDescription("Close photo").performClick()
        compose.waitUntil(5_000) { closeButtons().isEmpty() }

        compose.onNodeWithContentDescription("Photo 1 of 2").performClick()
        compose.waitUntil(5_000) { closeButtons().isNotEmpty() }
        compose.settleWindow()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(5_000) { closeButtons().isEmpty() }
        compose.onNodeWithText("which one?").assertIsDisplayed() // back closed the viewer, not the chat
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
        // what is excluded is a recomposition per token, which would be 30. A row may recompose a few times for
        // reasons outside the stream (the list settling, frames dropped on a loaded device), so the bound is well
        // under the token count, not exact
        assertTrue("earlier rows recomposed: $old", old.filterKeys { it != previousLatest }.values.all { it <= 3 })
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

    private companion object {
        /** A load that outlasts the test's look at the wake line (the fake engine's load sleeps in four steps). */
        const val LONG_LOAD_MILLIS = 20_000L
    }
}
