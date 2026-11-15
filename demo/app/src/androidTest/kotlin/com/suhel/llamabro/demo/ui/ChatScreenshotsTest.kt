package com.suhel.llamabro.demo.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.CachedEntry
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FakeHubGateway
import com.suhel.llamabro.demo.testing.TestAppRule
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.demo.testing.demoRules
import com.suhel.llamabro.demo.ui.chat.ChatScreen
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.shell.ChatChrome
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * A picture of every chat state in both styles and both themes, kept as evidence for the visual review against the
 * design (docs/design/demo-app.md): `adb pull /sdcard/Android/data/com.suhel.llamabro.demo/files/screens/chat
 * screens/chat`. They are looked at, not compared: a pass means each state rendered and a picture was written.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class ChatScreenshotsTest(private val style: MessageStyle, private val theme: ThemeMode) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun cases() =
            listOf(
                arrayOf(MessageStyle.Open, ThemeMode.Light),
                arrayOf(MessageStyle.Open, ThemeMode.Dark),
                arrayOf(MessageStyle.Bubbles, ThemeMode.Light),
                arrayOf(MessageStyle.Bubbles, ThemeMode.Dark),
            )
    }

    private val fake = FakeLlamaBro()
    private val hub = FakeHubGateway()
    private var script: ((String) -> Flow<ChatEvent>)? = null
    private val app =
        TestAppRule(config = {
            TestConfig.fresh(llamaBro = fake.llamaBro, hub = hub, streamer = { c, t, i, _ ->
                script?.invoke(t)
                    ?: c.send(t, *i)
            })
        })
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(demoRules(app)).around(compose)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")

    private fun onPhone(vararg models: HfModel) {
        hub.cachedEntries += models.map { CachedEntry(it, 700_000_000) }
        app.container.models.changed()
        runBlocking { app.container.settings.setLastModel(models.first().toString()) }
    }

    private fun show(chatId: Long = 0) {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            // what AppShell's SystemBars does: the icons follow the app's theme, not the phone's
            val controller =
                WindowCompat.getInsetsController(
                    compose.activity.window,
                    compose.activity.window.decorView,
                )
            controller.isAppearanceLightStatusBars = theme != ThemeMode.Dark
            controller.isAppearanceLightNavigationBars = theme != ThemeMode.Dark
        }
        compose.setContent {
            DemoTheme(theme) {
                Box(Modifier.background(LocalDemoColors.current.canvas)) {
                    val layout = if (style == MessageStyle.Open) AppLayout.Sidebar else AppLayout.ChatList
                    ChatScreen(app.container, chatId, ChatChrome(layout, {}, {}, {}, {}), style = style)
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(900)
    }

    private fun shot(state: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screens"),
                "chat",
            ).apply {
                mkdirs()
            }
        File(dir, "$state-${style.name.lowercase()}-${theme.name.lowercase()}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun ask(text: String) {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer"))).performTextInput(text)
        compose.onNodeWithContentDescription("Send").performClick()
    }

    private fun waitFor(text: String, substring: Boolean = false, ignoreCase: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring, ignoreCase = ignoreCase), 15_000)

    @Test fun everyState() {
        onPhone(swift, deep)
        // an old conversation to look at: a question, a reply with code and a list, a switch, another exchange
        val id =
            runBlocking {
                val chats = app.container.chats
                val now = System.currentTimeMillis()
                val chat = chats.createChat(swift.toString())
                chats.append(
                    chat,
                    NewMessage(MessageKind.User, "Plan a quiet weekend for me", sentAt = now - 26 * 3_600_000),
                )
                val first =
                    chats.append(
                        chat,
                        NewMessage(
                            MessageKind.Assistant,
                            "Here is a **gentle** plan:\n\n- Saturday: a long walk\n- Sunday: read and cook\n\n```text\nSat 9:00 walk\nSun 11:00 cook\n```\n\nSee [the guide](https://example.com).",
                            modelKey = swift.toString(),
                            sentAt =
                                now - 26 * 3_600_000 + 5_000,
                            reasoning = "weighing what a quiet weekend needs",
                        ),
                    )
                chats.update(
                    first,
                    com.suhel.llamabro.demo.data
                        .MessageChange(reasoningMillis = 4_000, wordsPerSecond = 31.0),
                )
                chats.append(
                    chat,
                    NewMessage(
                        MessageKind.Switch,
                        "Switched to Qwen3.5 4B",
                        modelKey = deep.toString(),
                        sentAt =
                            now - 3_600_000,
                    ),
                )
                chats.append(chat, NewMessage(MessageKind.User, "Make it shorter", sentAt = now - 3_000_000))
                chats.append(
                    chat,
                    NewMessage(
                        MessageKind.Assistant,
                        "Walk on Saturday, read on Sunday.",
                        modelKey = deep.toString(),
                        sentAt =
                            now - 2_900_000,
                    ),
                )
                chat
            }
        show(id)
        waitFor("Walk on Saturday", substring = true)
        shot("conversation")
        compose.onNodeWithText("Thought for", substring = true).performClick()
        shot("thought-open")
        compose.onNodeWithTag("model_title").performClick()
        Thread.sleep(900)
        shot("switcher")
    }

    @Test fun emptyAndWaiting() {
        onPhone(swift)
        show()
        shot("empty")
        script = {
            flow {
                emit(ChatEvent.ReasoningDelta("working it out"))
                delay(60_000)
            }
        }
        ask("Why is the sky blue?")
        waitFor("Thinking")
        shot("thinking-live")
        script = {
            flow {
                emit(ChatEvent.ContentDelta("The light scatters, so blue reaches you "))
                delay(60_000)
            }
        }
    }

    @Test fun streamingStoppedAndHeld() {
        onPhone(swift)
        script = {
            flow {
                emit(ChatEvent.ContentDelta("Starting to write a longer answer "))
                delay(60_000)
            }
        }
        show()
        ask("Write something")
        waitFor("Starting to write", substring = true)
        shot("streaming")
        compose.onNodeWithContentDescription("Stop").performClick()
        waitFor("Stopped")
        shot("stopped")
    }

    @Test fun waking() {
        hub.onSource = {
            delay(60_000)
            throw IllegalStateException("never loaded")
        }
        onPhone(swift)
        show()
        ask("Hello")
        compose.waitUntilAtLeastOneExists(hasTestTag("ds_wake_hairline"), 10_000)
        Thread.sleep(500)
        shot("waking")
    }

    @Test fun failedReply() {
        fake.error(500, "server_error", "boom")
        onPhone(swift)
        show()
        ask("This will fail")
        waitFor("That didn't work.")
        shot("failed")
    }

    @Test fun heldWhileDownloading() {
        runBlocking { app.container.settings.setLastModel(swift.toString()) }
        hub.status(swift).value = DownloadStatus.Downloading(300_000_000, 700_000_000, 9_000_000, 40)
        show()
        ask("Hello when you're ready")
        waitFor("Qwen3.5 0.8B will answer the moment it's here")
        shot("held")
    }

    @Test fun limitLine() {
        val id =
            runBlocking {
                app.container.chats.createChat(deep.toString()).also {
                    app.container.chats.append(it, NewMessage(MessageKind.User, "an old question"))
                }
            }
        onPhone(swift)
        show(id)
        waitFor("Qwen3.5 4B isn't on this phone any more.")
        shot("limit-model-gone")
    }
}
