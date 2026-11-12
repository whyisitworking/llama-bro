package com.suhel.llamabro.demo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Pictures attached to messages: downscaled to a long side of at most 1024 px, saved as JPEG quality 85. */
class ImageStore(private val dir: File) {
    /** Decodes [uri] (EXIF rotation applied), saves it under a new name and returns the name. */
    suspend fun save(uri: Uri, resolver: ContentResolver): String =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val long = max(info.size.width, info.size.height)
                    if (long > MAX_SIDE) {
                        val scale = MAX_SIDE.toFloat() / long
                        decoder.setTargetSize(
                            max(1, Math.round(info.size.width * scale)),
                            max(1, Math.round(info.size.height * scale)),
                        )
                    }
                }
            val name = UUID.randomUUID().toString() + ".jpg"
            try {
                File(dir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) }
            } finally {
                bitmap.recycle()
            }
            name
        }

    fun file(name: String): File = File(dir, name)

    suspend fun delete(names: List<String>) {
        withContext(Dispatchers.IO) { names.forEach { file(it).delete() } }
    }

    private companion object {
        const val MAX_SIDE = 1024
        const val QUALITY = 85
    }
}
