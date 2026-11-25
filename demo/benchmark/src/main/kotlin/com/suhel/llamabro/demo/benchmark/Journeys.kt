package com.suhel.llamabro.demo.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * The app under test (its benchmark build, beside the person's own release: D149), and the steps the benchmarks and
 * the Baseline Profile share.
 */
const val PACKAGE = "com.suhel.llamabro.demo.benchmark"
const val BIG_CHAT_TITLE = "Big chat of 300 messages"
private const val WAIT_MILLIS = 30_000L
private const val SEED_WAIT_MILLIS = 180_000L
private const val SEED_POLL_MILLIS = 3_000L

/**
 * The first launch seeds the database (300 chats, one of 300 messages) off the main thread; every benchmark starts from
 * the seeded app, so the first one waits for it here, once, before anything is measured.
 */
fun seedTheApp() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val launch =
        instrumentation.context.packageManager
            .getLaunchIntentForPackage(PACKAGE)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    instrumentation.context.startActivity(launch)
    // chats arrive while the list is on screen and it drifts down with them: keep pulling it back to the top, where the
    // last chat to be made (the big one) is, until it shows
    val deadline = System.currentTimeMillis() + SEED_WAIT_MILLIS
    var seeded = false
    while (!seeded && System.currentTimeMillis() < deadline) {
        seeded = device.wait(Until.hasObject(By.text(BIG_CHAT_TITLE)), SEED_POLL_MILLIS)
        if (seeded) break
        // the first launch decides its start screen before the seed has chosen the Chat list layout, so it opens on a
        // new chat: Back goes to the list. Once there, keep pulling it to the top while chats arrive.
        val list = device.findObject(By.res("chat_list"))
        // (UI Automator names a fling by where the content goes: UP reveals the top)
        if (list == null) device.findObject(By.desc("Back"))?.click() else list.fling(Direction.UP)
    }
    check(seeded) { "the app never finished seeding" }
    device.pressHome()
}

/** The Chat list home is up: its list is on screen with its chats. */
fun MacrobenchmarkScope.waitForHome() {
    check(device.wait(Until.hasObject(By.res("chat_list")), WAIT_MILLIS)) { "the Chat list never appeared" }
    device.wait(Until.hasObject(By.text(BIG_CHAT_TITLE)), WAIT_MILLIS)
}

/** Opens the chat of 300 messages from the Chat list and waits for its messages. */
fun MacrobenchmarkScope.openBigChat() {
    // the list may have been flung away from the top, where the big chat is
    var flings = 0
    while (!device.hasObject(By.text(BIG_CHAT_TITLE)) && flings++ < MAX_FLINGS_TO_TOP) {
        device.findObject(By.res("chat_list"))?.fling(Direction.UP)
        device.waitForIdle()
    }
    device.findObject(By.text(BIG_CHAT_TITLE)).click()
    check(device.wait(Until.hasObject(By.res("message_list")), WAIT_MILLIS)) { "the chat never opened" }
    device.waitForIdle()
}

/** Sends a question in the open chat: the fake engine answers with a long reply of markdown at 60 tokens a second. */
fun MacrobenchmarkScope.askAndWaitForTheReply(streamMillis: Long = STREAM_MILLIS) {
    val field = device.findObject(By.res("composer")).findObject(By.clazz("android.widget.EditText"))
    field.text = "Tell me more"
    device.findObject(By.desc("Send")).click()
    // the reply streams for about 20 s; it is over when the Send button is back
    device.wait(Until.hasObject(By.desc("Stop")), WAIT_MILLIS)
    device.wait(Until.gone(By.desc("Stop")), streamMillis * 2)
}

fun MacrobenchmarkScope.flingBothWays(resource: String, rounds: Int = 3) {
    val list = device.findObject(By.res(resource))
    list.setGestureMargin(device.displayWidth / MARGIN_DIVISOR)
    repeat(rounds) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}

/**
 * Back from an open chat to the Chat list: a sent message can leave the keyboard up, and the first Back closes only
 * that, so Back is pressed until the list shows.
 */
fun MacrobenchmarkScope.backToTheChatList() {
    var presses = 0
    while (!device.wait(Until.hasObject(By.res("chat_list")), BACK_WAIT_MILLIS) && presses++ < MAX_BACKS) {
        device.pressBack()
    }
    check(device.hasObject(By.res("chat_list"))) { "Back never reached the Chat list" }
}

/** Models tab → the first model's sheet: the Models page lists the phone's and the Hub's models on one page. */
fun MacrobenchmarkScope.openAModelSheet() {
    check(device.wait(Until.hasObject(By.text("Models")), WAIT_MILLIS)) { "the Models tab never showed" }
    device.findObject(By.text("Models")).click()
    check(device.wait(Until.hasObject(By.res("model_row")), WAIT_MILLIS)) { "the Models page showed no model" }
    device.findObject(By.res("model_row")).click()
    device.wait(Until.hasObject(By.text("Advanced")), WAIT_MILLIS)
    device.waitForIdle()
}

private const val STREAM_MILLIS = 20_000L
private const val MAX_FLINGS_TO_TOP = 8
private const val MAX_BACKS = 3
private const val BACK_WAIT_MILLIS = 2_000L
private const val MARGIN_DIVISOR = 5
