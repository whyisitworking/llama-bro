package com.suhel.llamabro.demo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold start to the first frame and to home fully drawn (`reportFullyDrawn` once the chats are on screen). */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldWithTheBaselineProfile() = start(CompilationMode.Partial())

    @Test fun coldWithoutTheBaselineProfile() = start(CompilationMode.None())

    private fun start(mode: CompilationMode) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            compilationMode = mode,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            waitForHome()
        }

    companion object {
        @JvmStatic @BeforeClass
        fun seed() = seedTheApp()

        const val ITERATIONS = 10
    }
}
