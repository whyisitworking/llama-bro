package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.rag.Hit
import org.junit.Test

/** A chat that answers from the person's library (docs/design/rag.md): the toggle, the sources, a passage opened. */
@OptIn(ExperimentalTestApi::class)
class LibraryJourneys : Journey() {
    private val swift = Catalog.picks[0].key
    private val robot = Robot()

    private val guide =
        "# Care guide\n\n## Watering\n\nWater the fern when the top inch of soil is dry, about once a week.\n\n" +
            "## Light\n\nBright, indirect light."
    private val watering = "Water the fern when the top inch of soil is dry, about once a week."

    @Test fun answerFromTheLibraryAndOpenASource() {
        val start = guide.indexOf(watering)
        world.library.hits =
            listOf(
                Hit(
                    "care-guide",
                    "Fern care guide",
                    watering,
                    start,
                    start + watering.length,
                    listOf("Care guide", "Watering"),
                    0.03f,
                    1,
                ),
            )
        world.library.texts["care-guide"] = guide
        world.library.hasDocuments.value = true
        onPhone(swift)
        world.engine.reply { content("About once a week, when the top inch is dry [1].") }
        see("What's on your mind?")

        // the + is a menu now: turn the library on for this chat
        tapDescription("Add to your message")
        seeTag("attach_menu")
        Thread.sleep(SHEET_SETTLE)
        tapTag("use_library")
        robot.pressBack()
        seeTag("library_on")
        shot("1-library-on")

        send("How often should I water my fern?")
        see("About once a week", substring = true)
        tap("[1] Fern care guide")
        seeTag("passage_sheet")
        see("Watering", substring = true)
        see("Bright, indirect light.", substring = true)
        shot("2-passage")
    }

    private companion object {
        const val SHEET_SETTLE = 700L
    }
}
