package com.suhel.llamabro.demo.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.chat.Knob
import com.suhel.llamabro.demo.chat.Knobs
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val file by lazy { File(folder.root, "settings.preferences_pb") }

    /** A new DataStore instance on the same file, as after a process restart. */
    private fun repository(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After fun stop() = scopes.forEach { it.cancel() }

    @Test fun defaultsAreSidebarSystemNoModelsEmptyInstructionsNotSeen() =
        runBlocking {
            val s = repository().settings.first()
            assertEquals(AppLayout.Sidebar, s.layout)
            assertEquals(ThemeMode.System, s.theme)
            assertNull(s.defaultModel)
            assertNull(s.lastModel)
            assertEquals("", s.instructionsAbout)
            assertEquals("", s.instructionsStyle)
            assertFalse(s.seenFirstRun)
        }

    @Test fun newChatUsesPinnedOverLast() =
        runBlocking {
            val repo = repository()
            repo.setLastModel("repo/S:Q4_K_M")
            repo.pinDefault("repo/W:Q8_0")
            assertEquals("repo/W:Q8_0", repo.settings.first().modelForNewChat())
            repo.pinDefault(null)
            assertEquals("repo/S:Q4_K_M", repo.settings.first().modelForNewChat())
        }

    @Test fun newChatWithNeitherHasNoModel() =
        runBlocking {
            assertNull(repository().settings.first().modelForNewChat())
        }

    @Test fun everySetterRoundTrips() =
        runBlocking {
            val repo = repository()
            repo.setLayout(AppLayout.ChatList)
            repo.setTheme(ThemeMode.Dark)
            repo.pinDefault("a/b:Q4")
            repo.setLastModel("c/d#e.gguf")
            repo.setInstructions("I am a nurse", "Short answers")
            repo.markFirstRunSeen()
            assertEquals(
                AppSettings(
                    AppLayout.ChatList,
                    ThemeMode.Dark,
                    "a/b:Q4",
                    "c/d#e.gguf",
                    "I am a nurse",
                    "Short answers",
                    true,
                ),
                repo.settings.first(),
            )
            repo.setTheme(ThemeMode.Light)
            assertEquals(ThemeMode.Light, repo.settings.first().theme)
            repo.setLayout(AppLayout.Sidebar)
            assertEquals(AppLayout.Sidebar, repo.settings.first().layout)
            repo.setTheme(ThemeMode.System)
            assertEquals(ThemeMode.System, repo.settings.first().theme)
        }

    @Test fun settingsSurviveProcessRestart() =
        runBlocking {
            val first = repository()
            first.setLayout(AppLayout.ChatList)
            first.setInstructions("about", "style")
            first.markFirstRunSeen()
            scopes.forEach { it.cancel() }
            val second = repository().settings.first()
            assertEquals(AppLayout.ChatList, second.layout)
            assertEquals("about", second.instructionsAbout)
            assertEquals("style", second.instructionsStyle)
            assertTrue(second.seenFirstRun)
        }

    @Test fun howLongTheModelStaysLoadedIsAMinuteUntilChosen() =
        runBlocking {
            val settings = repository()
            assertEquals(KeepLoaded.OneMinute, settings.settings.first().keepLoaded)
            settings.setKeepLoaded(KeepLoaded.UntilNeeded)
            assertEquals(KeepLoaded.UntilNeeded, settings.settings.first().keepLoaded)
            assertNull(KeepLoaded.UntilNeeded.millis)
        }

    @Test fun voiceModeTakesTheModelsFirstVoiceAndCaptionsUntilChosen() =
        runBlocking {
            val first = repository()
            assertNull(first.settings.first().voice)
            assertTrue(first.settings.first().captionYou)
            first.setVoice("male")
            first.setCaptionYou(false)
            scopes.forEach { it.cancel() }
            val again = repository()
            assertEquals("male", again.settings.first().voice)
            assertFalse(again.settings.first().captionYou)
            again.setVoice(null)
            assertNull(again.settings.first().voice)
        }

    @Test fun aModelThatTalksIsRememberedWithItsVoicesUntilItStops() =
        runBlocking {
            val settings = repository()
            assertEquals(emptyMap<String, List<String>>(), settings.talkingModels.first())
            settings.setVoices("a/omni:Q4_K_M", listOf("female", "male"))
            settings.setVoices("b/other:Q8_0", listOf("calm"))
            assertEquals(
                mapOf("a/omni:Q4_K_M" to listOf("female", "male"), "b/other:Q8_0" to listOf("calm")),
                settings.talkingModels.first(),
            )
            settings.setVoices("a/omni:Q4_K_M", listOf("female"))
            assertEquals(listOf("female"), settings.talkingModels.first()["a/omni:Q4_K_M"])
            settings.setVoices("a/omni:Q4_K_M", emptyList())
            assertEquals(setOf("b/other:Q8_0"), settings.talkingModels.first().keys)
        }

    @Test fun theChipIsAutomaticUntilChosenAndSurvivesARestart() =
        runBlocking {
            val first = repository()
            assertEquals(ChipChoice.Automatic, first.settings.first().chip)
            first.setChip(ChipChoice.Gpu)
            assertEquals(ChipChoice.Gpu, first.settings.first().chip)
            scopes.forEach { it.cancel() }
            assertEquals(ChipChoice.Gpu, repository().settings.first().chip)
        }

    @Test fun aModelsOwnSamplingValuesAreKeptPerModelAndResetToNone() =
        runBlocking {
            val settings = repository()
            val a = "unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"
            val b = "unsloth/Qwen3.5-0.8B-GGUF:Q8_0"
            assertEquals(emptyMap<String, Knobs>(), settings.modelSampling.first())
            settings.updateModelSampling(a) { Knobs.of(Knob.Temperature to 0.6f) }
            settings.updateModelSampling(b) { Knobs.of(Knob.TopK to 30f) }
            assertEquals(Knobs.of(Knob.Temperature to 0.6f), settings.modelSampling.first()[a])
            assertEquals(Knobs.of(Knob.TopK to 30f), settings.modelSampling.first()[b])
            settings.updateModelSampling(a) { Knobs.None }
            assertEquals(setOf(b), settings.modelSampling.first().keys)
        }

    @Test fun twoChangesToOneModelAtOnceBothLand() =
        runBlocking {
            val settings = repository()
            val key = "unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"
            Knob.entries
                .map { knob -> async(Dispatchers.IO) { settings.updateModelSampling(key) { it.with(knob, 0.5f) } } }
                .awaitAll()
            val own = settings.modelSampling.first().getValue(key)
            assertEquals(Knob.entries, Knob.entries.filter { own[it] != null })
        }
}
