package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a chat shows of a model that isn't loaded: its last load's senses, else the catalog's (D76, D100, D140). */
class ModelSensesTest {
    private val talker = Catalog.suggested.first { it.talks }.key
    private val quiet = Catalog.suggested.first { !it.talks }.key

    @Test
    fun aSuggestedModelThatTalksShowsTheMicAndVoiceModeBeforeItsFirstLoad() {
        val state = readyOrWaking(talker, "Omni", HostState.Idle) as ChatModelState.Ready
        assertTrue(state.hears)
        assertTrue(state.talks)
    }

    @Test
    fun anotherQuantOfTheSameModelIsTheSameModel() {
        val q8 = HfModel.parse("${talker.repo}:Q8_0")
        val state = readyOrWaking(q8, "Omni", HostState.Idle) as ChatModelState.Ready
        assertTrue(state.hears && state.talks)
    }

    @Test
    fun aModelThatDoesntTalkShowsWhatItsLastLoadHeard() {
        val never = readyOrWaking(quiet, "Quiet", HostState.Idle) as ChatModelState.Ready
        assertFalse(never.hears)
        assertFalse(never.talks)
        val heard =
            readyOrWaking(
                quiet,
                "Quiet",
                HostState.Idle,
                heard = setOf(quiet.toString()),
            ) as ChatModelState.Ready
        assertEquals(true to false, heard.hears to heard.talks)
    }
}
