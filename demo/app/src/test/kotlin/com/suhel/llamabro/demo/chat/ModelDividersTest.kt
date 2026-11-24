package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDividersTest {
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")

    private fun row(position: Int, kind: MessageKind, model: HfModel? = null) =
        StoredMessage(
            id = position + 1L,
            chatId = 1,
            position = position,
            kind = kind,
            text = "",
            reasoning = null,
            reasoningMillis = null,
            images = emptyList(),
            modelKey = model?.toString(),
            sentAt = 0,
            wordsPerSecond = null,
            state = MessageState.Done,
            failure = null,
        )

    @Test fun choosingAModelInAnEmptyChatSwitchesFromNothing() {
        assertFalse(isSwitch(emptyList()))
        assertTrue(isSwitch(listOf(row(0, MessageKind.User))))
    }

    @Test fun aCutAwayDividerIsStatedAgainWhenTheModelInUseDiffers() {
        // question, Qwen3.5 0.8B's reply, "Switched to Qwen3.5 4B", question, Qwen3.5 4B's reply; Qwen3.5 0.8B is in
        // use again: cut at the second question
        val rows =
            listOf(
                row(0, MessageKind.User),
                row(1, MessageKind.Assistant, swift),
                row(2, MessageKind.Switch, deep),
                row(3, MessageKind.User),
                row(4, MessageKind.Assistant, deep),
            )
        assertEquals(swift, modelToRestate(rows, 3, swift))
    }

    @Test fun nothingIsStatedWhenWhatIsLeftNamesTheModelInUse() {
        val rows = listOf(row(0, MessageKind.User), row(1, MessageKind.Assistant, swift), row(2, MessageKind.User))
        assertNull(modelToRestate(rows, 2, swift))
    }

    @Test fun nothingIsStatedWhenTheCutLeavesNoModelToCompareWith() {
        assertNull(modelToRestate(listOf(row(0, MessageKind.User)), 0, deep))
    }
}
