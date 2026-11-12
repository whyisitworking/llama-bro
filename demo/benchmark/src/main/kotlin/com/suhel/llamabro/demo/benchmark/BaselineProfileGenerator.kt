package com.suhel.llamabro.demo.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's Baseline Profile over what a person does first: start to the Chat list, open a chat, send a
 * question and watch the reply stream, open a model's sheet. Committed as `demo/app/src/main/baseline-prof.txt`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            waitForHome()
            flingBothWays("chat_list", rounds = 1)
            openBigChat()
            flingBothWays("message_list", rounds = 1)
            askAndWaitForTheReply()
            device.pressBack()
            device.wait(Until.hasObject(By.res("chat_list")), WAIT)
            openAModelSheet()
        }

    companion object {
        @JvmStatic @BeforeClass
        fun seed() = seedTheApp()

        private const val WAIT = 10_000L
    }
}
