package com.suhel.llamabro.hexagon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.LlamaBro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** On the OnePlus 13 the artifact alone makes HTP0 available (F36, F61). */
@RunWith(AndroidJUnit4::class)
class HexagonDeviceTest {
    @Test
    fun hexagonBackendLoads() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val llama = LlamaBro.create(context)
            val backends = llama.backends()
            val hexagon = backends.single { it.name == "hexagon" }
            assertTrue("$backends", hexagon.loaded)
            assertTrue("$hexagon", "HTP0" in hexagon.devices)
            val htp0 = llama.devices().single { it.name == "HTP0" }
            // from its backend, though ggml calls it a GPU (F32)
            assertEquals(DeviceKind.Npu, htp0.kind)
            // one verified skeleton set, all five versions
            val sets = File(context.noBackupFilesDir, "llamabro/htp").listFiles { f: File -> f.isDirectory }!!.toList()
            assertEquals(1, sets.size)
            assertEquals(
                listOf("v73", "v75", "v79", "v81", "v85").map { "libggml-htp-$it.so" },
                sets.single().list()!!.sorted(),
            )
        }
}
