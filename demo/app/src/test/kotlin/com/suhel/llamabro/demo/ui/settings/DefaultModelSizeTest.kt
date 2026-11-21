package com.suhel.llamabro.demo.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings › Default model says each model's one size, its file and its image reader. */
@RunWith(AndroidJUnit4::class)
class DefaultModelSizeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aModelOnThePhoneShowsItsFileAndReader() {
        val gemma =
            LibraryModel(
                HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"),
                "Gemma 3 4B",
                2_490_000_000,
                null,
                false,
                readerBytes = 850_000_000,
            )
        rule.setContent { DemoTheme(ThemeMode.Light) { DefaultModelContent(null, listOf(gemma), {}, {}) } }
        assertEquals(1, rule.onAllNodesWithText("3.3 GB").fetchSemanticsNodes().size)
    }
}
