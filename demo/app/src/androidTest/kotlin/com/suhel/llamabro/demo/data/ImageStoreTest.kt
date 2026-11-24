package com.suhel.llamabro.demo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ImageStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "image-store-${UUID.randomUUID()}")
    private val source = File(context.cacheDir, "source-${UUID.randomUUID()}")
    private val store = ImageStore(File(dir, "images"))

    @After fun cleanUp() {
        dir.deleteRecursively()
        source.delete()
    }

    private fun picture(width: Int, height: Int, format: Bitmap.CompressFormat): Uri {
        val bitmap =
            Bitmap
                .createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888,
                ).apply { eraseColor(Color.rgb(200, 80, 40)) }
        source.outputStream().use { bitmap.compress(format, 95, it) }
        bitmap.recycle()
        return Uri.fromFile(source)
    }

    private fun bounds(file: File) =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }

    @Test fun aLargePictureIsSavedWithItsLongSide1024AsJpeg() =
        runBlocking<Unit> {
            val name = store.save(picture(4000, 3000, Bitmap.CompressFormat.JPEG), context.contentResolver)
            assertTrue(name.endsWith(".jpg"))
            val saved = bounds(store.draftFile(name))
            assertEquals("image/jpeg", saved.outMimeType)
            assertEquals(1024, saved.outWidth)
            assertEquals(768, saved.outHeight)
        }

    @Test fun aTallPngIsScaledAndBecomesJpeg() =
        runBlocking<Unit> {
            val name = store.save(picture(1500, 3000, Bitmap.CompressFormat.PNG), context.contentResolver)
            val saved = bounds(store.draftFile(name))
            assertEquals("image/jpeg", saved.outMimeType)
            assertEquals(512, saved.outWidth)
            assertEquals(1024, saved.outHeight)
        }

    @Test fun aSmallPictureKeepsItsSize() =
        runBlocking<Unit> {
            val name = store.save(picture(400, 300, Bitmap.CompressFormat.JPEG), context.contentResolver)
            val saved = bounds(store.draftFile(name))
            assertEquals(400, saved.outWidth)
            assertEquals(300, saved.outHeight)
        }

    @Test fun deleteRemovesTheFiles() =
        runBlocking<Unit> {
            val a = store.save(picture(100, 100, Bitmap.CompressFormat.JPEG), context.contentResolver)
            val b = store.save(picture(100, 100, Bitmap.CompressFormat.JPEG), context.contentResolver)
            assertTrue(a != b)
            store.deleteDrafts(listOf(a, "never-existed.jpg"))
            assertFalse(store.draftFile(a).exists())
            assertTrue(store.draftFile(b).exists())
        }
}
