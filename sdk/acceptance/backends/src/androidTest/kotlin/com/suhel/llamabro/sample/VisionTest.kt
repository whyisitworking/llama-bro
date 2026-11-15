package com.suhel.llamabro.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The model sees a picture on the flavor's own device (F119, F120, F121). Matching upstream is not enough: at b12052
 * upstream llama-server also read Qwen3.5 0.8B's red and blue picture as "blue and black" with the image encoder on the
 * NPU. So this asserts what the model says. The model and its projector are file names in the app's
 * filesDir, passed as the instrumentation arguments `vision` and `visionMmproj`.
 */
@RunWith(AndroidJUnit4::class)
class VisionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The encoder follows the model onto the NPU (upstream's default). */
    @Test
    fun seesAPicture() = seesAPicture("seesAPicture", encoder = null, expected = DeviceKind.Npu)

    /** `encoderDevice = Only(Cpu)` moves the encoder off the NPU (llama-server's `--mmproj-device none`). */
    @Test
    fun seesAPictureWithTheEncoderOnTheCpu() =
        seesAPicture("seesAPictureWithTheEncoderOnTheCpu", DevicePolicy.Only(DeviceKind.Cpu), DeviceKind.Cpu)

    private fun seesAPicture(test: String, encoder: DevicePolicy?, expected: DeviceKind) =
        runBlocking {
            assumeTrue("full flavor only: the NPU", BuildConfig.FULL_SUITE)
            val args = InstrumentationRegistry.getArguments()
            val model = File(context.filesDir, requireNotNull(args.getString("vision")) { "pass vision=<model.gguf>" })
            val mmproj =
                File(context.filesDir, requireNotNull(args.getString("visionMmproj")) { "pass visionMmproj=<file>" })
            assertTrue("missing $model", model.exists())
            assertTrue("missing $mmproj", mmproj.exists())
            val loaded =
                LlamaBro.create(context).loadModel(ModelSource.file(model.path) { this.mmproj = mmproj.path }) {
                    encoderDevice = encoder
                }
            try {
                val chat =
                    loaded.conversation {
                        defaults {
                            sampling { temperature = 0f }
                            reasoning { enableThinking = false }
                        }
                    }
                val started = System.nanoTime()
                val reply =
                    chat
                        .send(
                            "What colour is the left half of this picture, and what colour is the right half? " +
                                "Answer in one short sentence.",
                            Content.Image(redAndBlueJpeg(), "image/jpeg"),
                        ).toList()
                        .filterIsInstance<ChatEvent.ContentDelta>()
                        .joinToString("") { it.delta }
                        .lowercase()
                val ms = (System.nanoTime() - started) / NANOS_PER_MS
                val r = loaded.loadReport
                val line =
                    "flavor=${BuildConfig.FLAVOR} device=${r.device} encoder=${r.encoderDeviceName} ms=$ms reply=\"$reply\""
                Log.i("Acceptance", "model=${model.name} test=$test $line")
                File(context.filesDir, "acceptance.log").appendText("model=${model.name} test=$test $line\n")
                assertEquals(line, expected, r.encoderDevice)
                // whole words, red before blue (left, then right): "coloured" contains "red", and F120's wrong answer
                // was "blue and black"
                assertTrue("the model didn't see the picture: $line", namesInOrder(reply, "red", "blue"))
            } finally {
                loaded.closeAndJoin()
            }
        }

    /** [words] each appear in [text] as whole words, in this order. */
    private fun namesInOrder(text: String, vararg words: String): Boolean {
        val at = words.map { Regex("\\b$it\\b").find(text)?.range?.first ?: return false }
        return at == at.sorted()
    }

    /** 640x480, red on the left and blue on the right, as a camera photo reaches the demo app: a JPEG. */
    private fun redAndBlueJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.rgb(220, 30, 30)
        canvas.drawRect(0f, 0f, WIDTH / 2f, HEIGHT.toFloat(), paint)
        paint.color = Color.rgb(20, 60, 220)
        canvas.drawRect(WIDTH / 2f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            it.toByteArray()
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val JPEG_QUALITY = 90
        const val NANOS_PER_MS = 1_000_000L
    }
}
