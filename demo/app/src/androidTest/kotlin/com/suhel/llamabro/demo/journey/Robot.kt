package com.suhel.llamabro.demo.journey

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * What a person does outside the app, through UI Automator: answer the system's permission dialog, pull down the
 * notification shade, press Home, bring the app back, and see the system's share sheet. (The test and the app share a
 * process, so the robot cannot kill the app: process death is the journey `downloadSurvivesProcessDeath`'s own story.)
 */
class Robot {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val packageName = instrumentation.targetContext.packageName

    fun allowNotifications() = answer("permission_allow_button")

    /**
     * Grants [permission] as a person who allowed it would have, through the shell; false on a phone whose system
     * refuses the shell that grant (F150). A journey then takes the refused path, or assumes the grant and is skipped.
     */
    fun grant(permission: String): Boolean {
        try {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
        } catch (refused: SecurityException) {
            Log.w(TAG, "the system refused to grant $permission: ${refused.message}")
        }
        return instrumentation.targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Answers yes when the system asks. It asks once per install: a journey that follows another finds the question
     * already answered, and goes on.
     */
    fun allowNotificationsIfAsked() {
        val granted =
            instrumentation.targetContext.checkSelfPermission(NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (device.wait(Until.hasObject(By.res(PERMISSION_UI, "permission_allow_button")), TIMEOUT)) {
            device.findObject(By.res(PERMISSION_UI, "permission_allow_button")).click()
        }
    }

    fun denyNotifications() = answer("permission_deny_button")

    fun notificationDialogIsShowing(): Boolean =
        device.wait(Until.hasObject(By.res(PERMISSION_UI, "permission_allow_button")), TIMEOUT)

    private fun answer(button: String) {
        check(device.wait(Until.hasObject(By.res(PERMISSION_UI, button)), TIMEOUT)) {
            "the system's notification dialog never appeared"
        }
        device.findObject(By.res(PERMISSION_UI, button)).click()
    }

    fun pressHome() {
        device.pressHome()
        device.wait(Until.gone(By.pkg(packageName)), TIMEOUT)
    }

    /**
     * Opens the shade at its notifications. UI Automator's `openNotification` left a ColorOS phone on the Home screen, and its
     * shade has no `notification_stack_scroller`, so the shell's own command asks for the notifications instead.
     */
    fun openNotificationShade() {
        shell("cmd statusbar expand-notifications")
        device.waitForIdle()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    /**
     * Random input pulls the shade down and flips what is in it (airplane mode, Wi-Fi): put the phone back, so the
     * test after this one finds a network.
     */
    fun restoreSystem() {
        for (command in listOf(
            "cmd statusbar collapse",
            "cmd connectivity airplane-mode disable",
            "svc wifi enable",
            "svc data enable",
            "input keyevent KEYCODE_HOME",
        )) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
                it.readBytes()
            }
        }
    }

    /** Closes the shade (and leaves the Home screen in front of whatever was open). */
    fun collapseNotificationShade() {
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse"),
            ).use { it.readBytes() }
    }

    /** True when the shade holds a notification whose text contains [text]. */
    fun shadeShows(text: String): Boolean = findInShade(text) != null

    fun tapInShade(text: String) {
        findInShade(text)?.click() ?: error("no notification mentions \"$text\"")
    }

    /**
     * The notification that mentions [text]. A download's notification is a quiet one (low importance), which a phone
     * with other notifications lists below them, off the screen: the shade is scrolled up to [SHADE_SCROLLS] times.
     */
    private fun findInShade(text: String): UiObject2? {
        device.wait(Until.findObject(By.textContains(text)), TIMEOUT)?.let { return it }
        repeat(SHADE_SCROLLS) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                SWIPE_STEPS,
            )
            device.wait(Until.findObject(By.textContains(text)), SCROLL_WAIT)?.let { return it }
        }
        return null
    }

    /** Brings the app's task back to the front, as its launcher icon would. */
    fun relaunch() {
        val intent =
            instrumentation.targetContext.packageManager
                .getLaunchIntentForPackage(packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        instrumentation.targetContext.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(packageName)), TIMEOUT)
    }

    /** Another app's window (the system's chooser, say) is in front of ours. */
    fun otherAppIsInFront(): Boolean = device.currentPackageName != packageName

    /** What is in front, for a failure's message. */
    fun inFront(): String = device.currentPackageName.orEmpty()

    /** Puts a picture in the phone's gallery, as a camera would, so the system's photo picker has one to offer. */
    fun addPhotoToGallery() {
        val resolver = instrumentation.targetContext.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "journey-${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
            }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { out ->
            Bitmap
                .createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(PHOTO_COLOUR) }
                .compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    /** Whether this phone has a system photo picker at all (an emulator image can lack one). */
    fun hasPhotoPicker(): Boolean =
        instrumentation.targetContext.packageManager
            .queryIntentActivities(Intent(MediaStore.ACTION_PICK_IMAGES), 0)
            .isNotEmpty()

    /**
     * In the system's photo picker: taps the first picture it shows, and Done where the picker asks for it. The picker
     * is two things: AOSP's (a grid of views with the id `icon_thumbnail`) and the Google Photos one of newer phones
     * (no ids; each picture is described "Photo taken on ..."), so the first match of either is taken.
     */
    fun pickFirstPhoto() {
        val aosp = By.res(Pattern.compile(".*:id/icon_thumbnail"))
        val googlePhotos = By.desc(Pattern.compile("Photo taken on .*"))
        val deadline = System.currentTimeMillis() + TIMEOUT
        var shown = device.hasObject(aosp) || device.hasObject(googlePhotos)
        while (!shown && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
            shown = device.hasObject(aosp) || device.hasObject(googlePhotos)
        }
        check(shown) { "the photo picker showed no picture" }
        (device.findObject(aosp) ?: device.findObject(googlePhotos)).click()
        // the Google Photos picker lets several be chosen: a tap ticks the picture, and Done hands it back
        device.wait(Until.findObject(By.text("Done")), CONFIRM_MILLIS)?.click()
        // the picker closes and hands the picture back: the next step is the app's, so wait for it to be in front again
        val app = instrumentation.targetContext.packageName
        check(device.wait(Until.hasObject(By.pkg(app)), TIMEOUT)) { "the app did not come back from the photo picker" }
        device.waitForIdle()
    }

    fun dismissShareSheet() = pressBack()

    /** The system Back, as the gesture or the button sends it. */
    fun pressBack() {
        device.pressBack()
    }

    private companion object {
        const val POLL_MILLIS = 250L
        const val CONFIRM_MILLIS = 2_000L
        const val TIMEOUT = 10_000L
        const val SHADE_SCROLLS = 6
        const val SWIPE_STEPS = 20
        const val SCROLL_WAIT = 1_000L
        const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val PHOTO_WIDTH = 300
        const val PHOTO_HEIGHT = 200
        const val PHOTO_COLOUR = 0xFF3366CC.toInt()
        const val JPEG_QUALITY = 90
        const val POLL = 200L
        const val PERMISSION_UI = "com.android.permissioncontroller"
        const val TAG = "Robot"
    }
}
