package com.suhel.llamabro.demo.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.suhel.llamabro.rag.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** A picked file the library can't take; the message says why, in the page's words. */
class ImportRefused(reason: String) : Exception(reason)

/**
 * Reads a file the system picker gave as a library [Document] (docs/design/rag.md): text and Markdown, by the type its
 * provider gives or, when that says nothing useful (`application/octet-stream`), by its name. PDF is refused for now:
 * Android's text extraction wasn't checked on the phone (the PDF spike was skipped). The title is the file's name
 * without its extension, the source its name, and the id a hash of its URI, so importing the same file again replaces
 * it in the library.
 */
class TextImport(private val resolver: ContentResolver) {
    /** @throws ImportRefused for a type it doesn't read, a file over [MAX_BYTES], or one with no text. */
    suspend fun read(uri: Uri): Document =
        withContext(Dispatchers.IO) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "The file"
            refuseUnlessText(name, resolver.getType(uri))
            val bytes =
                resolver.openInputStream(uri)?.use { it.readAtMost(MAX_BYTES + 1) }
                    ?: throw ImportRefused("$name couldn't be opened.")
            if (bytes.size > MAX_BYTES) throw ImportRefused("$name is larger than ${MAX_BYTES / BYTES_PER_MIB} MB.")
            val text = bytes.toString(Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK)
            if (text.isBlank()) throw ImportRefused("$name has no text.")
            Document(id = idOf(uri), title = name.substringBeforeLast('.').ifBlank { name }, text = text, source = name)
        }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }

    companion object {
        /** Larger than a book; a file over it is refused rather than read into memory whole. */
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val BYTES_PER_MIB = 1024 * 1024

        // written as an escape: the character itself is invisible, and was once lost in an edit
        private const val BYTE_ORDER_MARK = "\uFEFF"
        private const val ID_HEX_CHARS = 16
        private const val HEX = 16
        private val TEXT_NAMES = setOf("txt", "text", "md", "markdown")

        /** The types the system picker offers: text, and the label some providers give any file. */
        val PICKER_TYPES = arrayOf("text/*", "text/markdown", "application/octet-stream")

        /** @throws ImportRefused unless [type] (or, without a useful one, [name]) is text or Markdown. */
        internal fun refuseUnlessText(name: String, type: String?) {
            val extension = name.substringAfterLast('.', "").lowercase()
            when {
                type == "application/pdf" || extension == "pdf" -> {
                    throw ImportRefused("$name is a PDF: only text and Markdown files can be added for now.")
                }

                type != null && type.startsWith("text/") -> {
                    Unit
                }

                (type == null || type == "application/octet-stream") && extension in TEXT_NAMES -> {
                    Unit
                }

                else -> {
                    throw ImportRefused("$name isn't a text or Markdown file.")
                }
            }
        }

        /** The first 16 hex characters of the URI's SHA-256: one id per file the picker names. */
        internal fun idOf(uri: Uri): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(uri.toString().toByteArray())
                .joinToString("") { it.toUByte().toString(HEX).padStart(2, '0') }
                .take(ID_HEX_CHARS)
    }
}

// at most [limit] bytes (InputStream.readNBytes is API 33)
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
