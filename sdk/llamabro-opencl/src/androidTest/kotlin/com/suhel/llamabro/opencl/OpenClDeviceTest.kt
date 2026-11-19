package com.suhel.llamabro.opencl

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.LlamaBro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On the OnePlus 13 the artifact alone makes the Adreno GPU available. */
@RunWith(AndroidJUnit4::class)
class OpenClDeviceTest {
    @Test
    fun openclBackendLoads() =
        runBlocking {
            val llama = LlamaBro.create(InstrumentationRegistry.getInstrumentation().targetContext)
            val backends = llama.backends()
            val opencl = backends.single { it.name == "opencl" }
            assertTrue("$backends", opencl.loaded)
            assertTrue("$opencl", "GPUOpenCL" in opencl.devices)
            val gpu = llama.devices().single { it.name == "GPUOpenCL" }
            assertTrue("$gpu", "Adreno" in gpu.description && gpu.memoryTotal > 0)
        }
}
