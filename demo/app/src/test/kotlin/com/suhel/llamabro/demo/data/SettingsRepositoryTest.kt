package com.suhel.llamabro.demo.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
}
