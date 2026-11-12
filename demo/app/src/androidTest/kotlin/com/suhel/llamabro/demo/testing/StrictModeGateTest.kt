package com.suhel.llamabro.demo.testing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The StrictMode gate must catch what it claims to: a test that never fails proves nothing. */
class StrictModeGateTest {
    @get:Rule val app = TestAppRule()

    @Test fun mainThreadDiskIoOfOursIsCaught() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val before = StrictViolations.count()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { File(dir, "strict-probe").writeText("x") }
        // StrictMode delivers violations a moment after the call: wait until the count stops moving
        var last = -1
        while (StrictViolations.count() != last) {
            last = StrictViolations.count()
            Thread.sleep(300)
        }
        assertTrue("a main-thread write by test code of ours is recorded", StrictViolations.count() > before)
        StrictViolations.reset() // this test caused them on purpose
    }

    @Test fun onlyTheKnownForeignSourcesAreExempt() {
        assertTrue(
            StrictViolations.isForeign(listOf("java.io.File", "com.oplus.uifirst.Utils", "android.view.ViewRootImpl")),
        )
        assertTrue(
            StrictViolations.isForeign(
                listOf(
                    "java.util.zip.ZipFile",
                    "com.google.android.apps.common.testing.accessibility.framework.strings.X",
                ),
            ),
        )
        assertTrue(StrictViolations.isForeign(listOf("libcore.io.Os", "com.android.server.am.Foo")))
        assertFalse(
            "unknown sources fail",
            StrictViolations.isForeign(listOf("java.io.File", "androidx.room.RoomDatabase")),
        )
        assertFalse(
            "our frame always fails, even under a foreign one",
            StrictViolations.isForeign(listOf("com.oplus.uifirst.Utils", "com.suhel.llamabro.demo.MainActivity")),
        )
        assertEquals(0, StrictViolations.count())
    }
}
