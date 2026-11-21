package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The size guess from the Hub's parameter count (F175). */
class GuessTest {
    private val phone = DeviceFacts(8L shl 30, DeviceKind.Npu)

    private fun model(parameters: Long?, active: Long? = null, sees: Boolean = false) =
        SearchModel(
            groupKey = "x",
            name = "X",
            repo = "someone/X-GGUF",
            author = "someone",
            downloads = 1,
            sees = sees,
            catalog = null,
            parameters = parameters,
            activeParameters = active,
        )

    @Test fun recommendedSizeIsWithinFifteenPercentOfEveryCatalogFile() {
        Catalog.suggested.forEach { entry ->
            val guess = Guess.recommendedBytes(entry.parameters)
            assertTrue(
                "${entry.realName}: guessed $guess, file ${entry.modelBytes}",
                abs(guess - entry.modelBytes) <= 0.15 * entry.modelBytes,
            )
        }
    }

    @Test fun activeParametersComeFromTheNameOfAMoe() {
        assertEquals(3_000_000_000L, Guess.activeParameters("Qwen3.6-35B-A3B", "qwen35moe"))
        assertNull(Guess.activeParameters("Qwen3.5-4B", "qwen35"))
        assertNull(Guess.activeParameters("X-A3B", "llama"))
    }

    /** F176: the "Fits this phone" cap is the largest count whose guessed file is not Too big here. */
    @Test fun theFitCapIsTheLargestParameterCountThatIsNotTooBig() {
        val phones =
            listOf(
                DeviceFacts(4L shl 30, DeviceKind.Npu),
                DeviceFacts(6L shl 30, DeviceKind.Npu),
                DeviceFacts(4L shl 30, DeviceKind.Cpu),
                DeviceFacts(300_000_000, DeviceKind.Cpu),
            )
        phones.forEach { facts ->
            val cap = Guess.fitCap(facts)
            assertNotEquals("$facts at $cap", FitLabel.TooBig, Guess.fit(cap, null, false, facts)?.label)
            assertEquals("$facts at ${cap + 1}", FitLabel.TooBig, Guess.fit(cap + 1, null, false, facts)?.label)
        }
        // an 8 GiB phone (a 4 GiB budget): a Q4_K_M file at 5.2 bits per weight fits up to about 6.6 billion
        assertEquals(6_607_641_993L, Guess.fitCap(DeviceFacts(4L shl 30, DeviceKind.Npu)))
    }

    @Test fun noGgufSummaryGivesNoFit() = assertNull(Guess.fit(model(parameters = null), phone))

    @Test fun aMoeIsSizedByAllItsParametersAndTimedByItsActiveOnes() {
        val moe = model(parameters = 35_000_000_000, active = 3_000_000_000)
        assertEquals(
            Fits.quick(Guess.recommendedBytes(35_000_000_000), Guess.recommendedBytes(3_000_000_000), phone),
            Guess.fit(moe, phone),
        )
    }

    @Test fun aModelThatSeesCountsItsReader() {
        val params = 4_000_000_000L
        assertEquals(
            Fits.quick(
                Guess.recommendedBytes(params) + Guess.readerBytes(params),
                Guess.recommendedBytes(params),
                phone,
            ),
            Guess.fit(model(params, sees = true), phone),
        )
    }
}
