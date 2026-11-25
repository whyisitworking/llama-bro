package com.suhel.llamabro.demo.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.chat.Think
import com.suhel.llamabro.demo.chat.Thinking
import com.suhel.llamabro.demo.chat.contextLabel
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.data.settings.LoadConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The context length (Settings › Inference) and the reply length (Settings › Inference): what is stored and sent. */
class LengthSettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val file by lazy { File(folder.root, "settings.preferences_pb") }

    /** A new DataStore instance on the same file, as after a process restart. */
    private fun repository(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After fun stop() = scopes.forEach { it.cancel() }

    @Test fun bothStartAutomaticAndSurviveARestart() =
        runBlocking {
            val first = repository()
            assertEquals(ContextChoice.Automatic, first.settings.first().context)
            assertEquals(ReplyLength.Automatic, first.settings.first().replyLength)
            first.setContext(ContextChoice.UpTo32k)
            first.setReplyLength(ReplyLength.NoLimit)
            scopes.forEach { it.cancel() }
            val again = repository().settings.first()
            assertEquals(ContextChoice.UpTo32k, again.context)
            assertEquals(ReplyLength.NoLimit, again.replyLength)
        }

    @Test fun eachContextChoiceIsAutoOrACeiling() {
        assertEquals(ContextSize.Auto, ContextChoice.Automatic.size)
        assertEquals(ContextSize.UpTo(4_096), ContextChoice.UpTo4k.size)
        assertEquals(ContextSize.UpTo(8_192), ContextChoice.UpTo8k.size)
        assertEquals(ContextSize.UpTo(16_384), ContextChoice.UpTo16k.size)
        assertEquals(ContextSize.UpTo(32_768), ContextChoice.UpTo32k.size)
    }

    @Test fun theLoadConfigIsTheChipAndTheContext() {
        assertEquals(LoadConfig(), AppSettings().loadConfig)
        assertEquals(
            LoadConfig(DevicePolicy.Prefer(DeviceKind.Gpu), ContextSize.UpTo(8_192)),
            AppSettings(chip = ChipChoice.Gpu, context = ContextChoice.UpTo8k).loadConfig,
        )
    }

    @Test fun automaticFollowsTheThinkLevelAndTheOthersReplaceItsTotal() {
        for (level in listOf(null) + Think.entries) {
            assertEquals(Thinking.replyTokens(level), Thinking.replyTokens(level, ReplyLength.Automatic))
            assertEquals(1_024, Thinking.replyTokens(level, ReplyLength.Short))
            assertEquals(8_192, Thinking.replyTokens(level, ReplyLength.Long))
            assertNull(Thinking.replyTokens(level, ReplyLength.NoLimit))
        }
        // the level's thinking budget stays as it is under every length
        assertEquals(512, Thinking.reasoning(Think.Quick).budgetTokens)
    }

    @Test fun noLimitSendsNoMaxTokens() {
        val layers = SamplingLayers(card = null, model = Knobs.None)
        assertNull(layers.sampling(thinks = false, maxTokens = null).maxTokens)
        assertEquals(1_024, layers.sampling(thinks = false, maxTokens = 1_024).maxTokens)
    }

    @Test fun theContextReadsInWholeKRoundedDown() {
        assertEquals("16k context", contextLabel(16_384))
        assertEquals("4k context", contextLabel(4_096))
        // never more than there is
        assertEquals("15k context", contextLabel(15_872))
    }
}
