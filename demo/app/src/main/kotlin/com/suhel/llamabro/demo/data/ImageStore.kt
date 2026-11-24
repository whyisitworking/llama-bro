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

/**
 * Pictures attached to messages: downscaled to a long side of at most 1024 px, saved as JPEG quality 85. A picture
 * picked for a message not yet sent is staged in `draft/` under [dir]; sending the message [commit]s it into [dir]
 * itself, where a message's pictures live. A draft's names are held only in the chat screen's saved state, which this
 * class cannot read, so the draft folder is swept only of pictures far older than a draft is kept
 * ([deleteStaleDrafts]).
 */
class ImageStore(private val dir: File) {
    private val drafts = File(dir, DRAFT_DIR)

    /** Decodes [uri] (EXIF rotation applied), saves it in the draft folder under a new name and returns the name. */
    suspend fun save(uri: Uri, resolver: ContentResolver): String =
        withContext(Dispatchers.IO) {
            drafts.mkdirs()
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
                File(
                    drafts,
                    name,
                ).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) }
            } finally {
                bitmap.recycle()
            }
            name
        }

    /** A message's picture. */
    fun file(name: String): File = File(dir, name)

    /** A picture in a draft that is not sent yet. */
    fun draftFile(name: String): File = File(drafts, name)

    /** Deletes messages' pictures. */
    suspend fun delete(names: List<String>) {
        withContext(Dispatchers.IO) { names.forEach { file(it).delete() } }
    }

    /** Deletes a draft's pictures: removed from the bar, or left with a draft nobody sent. */
    suspend fun deleteDrafts(names: List<String>) {
        withContext(Dispatchers.IO) { names.forEach { draftFile(it).delete() } }
    }

    /**
     * Moves a sent draft's pictures into the messages' folder and returns the names it moved. Each is stamped [now]
     * before the move (a rename keeps the time), so a sweep never takes one between the move and the row naming it.
     * When a name has no draft file (deleted meanwhile, or one a sent message already holds) nothing is moved and the
     * answer is null: the message would name a picture that is gone, or share another message's file.
     */
    suspend fun commit(names: List<String>, now: Long): List<String>? =
        withContext(Dispatchers.IO) {
            if (names.any { !draftFile(it).exists() }) return@withContext null
            dir.mkdirs()
            names.filter { name ->
                val from = draftFile(name)
                if (!from.exists()) return@filter false
                val stamped = from.setLastModified(now)
                val moved = from.renameTo(file(name))
                // a stamp that failed before the move is tried once more on the moved file
                if (moved && !stamped) file(name).setLastModified(now)
                moved
            }
        }

    /** Undoes [commit] for a message that could not be stored, with the names it moved: the draft has them again. */
    suspend fun uncommit(names: List<String>) {
        withContext(Dispatchers.IO) {
            drafts.mkdirs()
            names.forEach { name -> if (file(name).exists()) file(name).renameTo(draftFile(name)) }
        }
    }

    /**
     * Deletes the draft pictures last written before [cutoff] (epoch millis): left by a draft nobody sent or discarded
     * (the task swiped away, the process killed before the screen closed).
     */
    suspend fun deleteStaleDrafts(cutoff: Long) {
        withContext(Dispatchers.IO) {
            drafts.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    /** Deletes the messages' pictures not in [referenced] that were last written before [cutoff] (epoch millis). */
    suspend fun deleteUnreferenced(referenced: Set<String>, cutoff: Long) {
        withContext(Dispatchers.IO) {
            dir
                .listFiles()
                ?.filter { it.isFile && it.name !in referenced && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val DRAFT_DIR = "draft"
        const val MAX_SIDE = 1024
        const val QUALITY = 85
    }
}
