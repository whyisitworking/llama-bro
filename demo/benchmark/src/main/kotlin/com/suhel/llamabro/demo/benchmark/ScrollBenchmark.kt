package com.suhel.llamabro.demo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Flinging the Chat list (300 chats) and a chat of 300 messages with code blocks. */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun flingTheChatList() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = StartupBenchmark.ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait()
                waitForHome()
            },
        ) {
            flingBothWays("chat_list")
        }

    @Test fun flingAChatOf300Messages() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = StartupBenchmark.ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait()
                waitForHome()
                openBigChat()
            },
        ) {
            flingBothWays("message_list")
        }

    companion object {
        @JvmStatic @BeforeClass
        fun seed() = seedTheApp()
    }
}
