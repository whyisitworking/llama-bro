package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.Speculative
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.models.DeviceFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a model runs against the chip chosen (D72), from the SDK's own `LoadReport` and attempts. */
class RunsOnTest {
    // the SDK builds these; its constructors are internal, so the test reaches them as the runtime does
    private fun attempt(kind: DeviceKind, deviceName: String?, reason: String): LoadReport.Attempt =
        LoadReport.Attempt::class.java
            .getDeclaredConstructor(DeviceKind::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .newInstance(kind, deviceName, reason)

    private fun report(device: DeviceKind, vararg attempts: LoadReport.Attempt): LoadReport {
        val constructor =
            LoadReport::class.java.declaredConstructors.single {
                it.parameterCount == REPORT_FIELDS && it.parameterTypes[0] == DeviceKind::class.java
            }
        constructor.isAccessible = true
        return constructor.newInstance(
            device,
            device.label,
            device.name.lowercase(),
            attempts.toList(),
            4096,
            "fits",
            Speculative.Off,
            "off",
            null,
            null,
            null,
        ) as LoadReport
    }

    private val npu = DevicePolicy.Prefer(DeviceKind.Npu)

    @Test fun automaticNeverFallsBack() {
        val r = report(DeviceKind.Cpu, attempt(DeviceKind.Npu, "HTP0", "load failed"))
        assertNull(r.fallbackFrom(DevicePolicy.Auto))
    }

    @Test fun theChipChosenIsNoFallback() {
        assertNull(report(DeviceKind.Npu).fallbackFrom(npu))
    }

    @Test fun anNpuThatFailedOnAPhoneWithAGpuLandsOnTheGpu() {
        val r = report(DeviceKind.Gpu, attempt(DeviceKind.Npu, "HTP0", "llama_model_load: unsupported op"))
        assertEquals(ChipFallback(DeviceKind.Gpu, DeviceKind.Npu, "the NPU couldn't load it"), r.fallbackFrom(npu))
        assertEquals("Running on the GPU: the NPU couldn't load it.", r.fallbackFrom(npu)!!.line)
    }

    @Test fun onlyAnotherKindsAttemptMeansThePhoneHasNone() {
        val r = report(DeviceKind.Cpu, attempt(DeviceKind.Gpu, "GPUOpenCL", "load failed"))
        assertEquals("this phone has no NPU", r.fallbackFrom(npu)!!.why)
    }

    @Test fun aBackendThatDidNotLoadIsNotAvailable() {
        val r = report(DeviceKind.Cpu, attempt(DeviceKind.Npu, null, "hexagon didn't load: dlopen failed"))
        assertEquals("the NPU isn't available here", r.fallbackFrom(npu)!!.why)
    }

    @Test fun theModelsOwnAttemptWinsOverTheEncoderLineInEitherOrder() {
        val encoder = attempt(DeviceKind.Npu, "HTP0", "image encoder on HTP0 failed (x); it follows the model instead")
        val model = attempt(DeviceKind.Npu, null, "hexagon has no device")
        val expected = "the NPU isn't available here"
        assertEquals(expected, report(DeviceKind.Cpu, encoder, model).fallbackFrom(npu)!!.why)
        assertEquals(expected, report(DeviceKind.Cpu, model, encoder).fallbackFrom(npu)!!.why)
        // the encoder's line alone still says the chip was tried
        assertEquals("the NPU couldn't load it", report(DeviceKind.Cpu, encoder).fallbackFrom(npu)!!.why)
    }

    @Test fun estimatesUseTheChosenChipWhenThePhoneHasIt() {
        val phone = DeviceFacts(8L shl 30, DeviceKind.Npu, setOf(DeviceKind.Npu, DeviceKind.Cpu))
        assertEquals(DeviceKind.Cpu, phone.preferring(ChipChoice.Cpu.kind).chip)
        assertEquals(DeviceKind.Npu, phone.preferring(ChipChoice.Gpu.kind).chip)
        assertEquals(DeviceKind.Npu, phone.preferring(ChipChoice.Automatic.kind).chip)
    }

    @Test fun unknownFactsMarkNoChipMissing() {
        assertEquals(DeviceKind.entries.toSet(), DeviceFacts.Fallback.kinds)
    }

    private companion object {
        const val REPORT_FIELDS = 11
    }
}
