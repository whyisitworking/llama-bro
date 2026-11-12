package com.suhel.llamabro.demo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.benchmark.HeapSampler.sampling
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A reply streaming at 60 tokens a second for 20 seconds into the chat of 300 messages: the frames, the two trace
 * sections that run per update, and the app's own memory while it does.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class StreamingBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun streamAReply() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics =
                listOf(
                    FrameTimingMetric(),
                    TraceSectionMetric("ChatEngine.onDelta", TraceSectionMetric.Mode.Sum),
                    TraceSectionMetric("Markdown.parse", TraceSectionMetric.Mode.Sum),
                    TraceSectionMetric("ChatEngine.onDelta", TraceSectionMetric.Mode.Count, label = "onDeltaCount"),
                    TraceSectionMetric("Markdown.parse", TraceSectionMetric.Mode.Count, label = "parseCount"),
                ),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait()
                waitForHome()
                openBigChat()
            },
        ) {
            askAndWaitForTheReply()
        }

    @Test fun memoryWhileStreaming() =
        rule.measureRepeated(
            packageName = PACKAGE,
            // the footprint is reported; the budget is on what the heap holds, sampled by HeapSampler
            metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait()
                waitForHome()
                openBigChat()
            },
        ) {
            sampling { askAndWaitForTheReply() }
        }

    companion object {
        @JvmStatic @BeforeClass
        fun seed() = seedTheApp()

        const val ITERATIONS = StartupBenchmark.ITERATIONS
    }
}
