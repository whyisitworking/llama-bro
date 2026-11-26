package com.suhel.llamabro.demo.library

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File

/** A picked file read through the ContentResolver: text and Markdown come in, anything else is refused with why. */
@RunWith(AndroidJUnit4::class)
class TextImportTest {
    private val import =
        TextImport(ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver)

    @Before fun provide() {
        Files.entries.clear()
        Robolectric.setupContentProvider(Files::class.java, AUTHORITY)
    }

    private fun file(name: String, type: String?, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://$AUTHORITY/${Files.entries.size}")
        Files.entries[uri.toString()] = Triple(name, type, bytes)
        return uri
    }

    private fun read(uri: Uri) = runBlocking { import.read(uri) }

    private fun refusal(uri: Uri): String =
        try {
            read(uri)
            fail("$uri was read")
            ""
        } catch (e: ImportRefused) {
            e.message.orEmpty()
        }

    @Test fun markdownAndTextImport() {
        val notes = file("Trip notes.md", "text/markdown", "# Trip\n\nPack light.".toByteArray())
        val plain = file("todo.txt", "text/plain", "Milk, eggs.".toByteArray())

        val markdown = read(notes)
        assertEquals("Trip notes", markdown.title)
        assertEquals("Trip notes.md", markdown.source)
        assertEquals("# Trip\n\nPack light.", markdown.text)
        assertEquals("todo", read(plain).title)
        assertEquals("Milk, eggs.", read(plain).text)
    }

    @Test fun aByteOrderMarkIsNotText() {
        // EF BB BF, the UTF-8 byte-order mark some editors write first
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val notes = file("Trip notes.md", "text/markdown", bom + "# Trip\n\nPack light.".toByteArray())
        assertEquals("# Trip\n\nPack light.", read(notes).text)
    }

    @Test fun theIdIsTheUrisAndStaysTheSame() {
        val a = file("a.md", "text/markdown", "One.".toByteArray())
        val b = file("b.md", "text/markdown", "Two.".toByteArray())
        assertEquals(read(a).id, read(a).id)
        assertNotEquals(read(a).id, read(b).id)
        assertTrue(read(a).id, read(a).id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test fun markdownALabelDoesNotKnowIsReadByItsName() {
        // some file providers call a .md file application/octet-stream
        val notes = file("notes.markdown", "application/octet-stream", "Hello.".toByteArray())
        assertEquals("Hello.", read(notes).text)
    }

    @Test fun unsupportedTypeIsRefusedWithReason() {
        val pdf = file("Lease.pdf", "application/pdf", "%PDF-1.7".toByteArray())
        val photo = file("cat.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val empty = file("blank.txt", "text/plain", " \n ".toByteArray())

        assertEquals("Lease.pdf is a PDF: only text and Markdown files can be added for now.", refusal(pdf))
        assertEquals("cat.jpg isn't a text or Markdown file.", refusal(photo))
        assertEquals("blank.txt has no text.", refusal(empty))
    }

    @Test fun aFileTooLargeIsRefused() {
        val big = file("dump.txt", "text/plain", ByteArray(TextImport.MAX_BYTES + 1) { 'a'.code.toByte() })
        assertEquals("dump.txt is larger than 8 MB.", refusal(big))
    }

    /** A file provider over [entries]: uri to its display name, type and bytes. */
    class Files : ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri): String? = entries[uri.toString()]?.second

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val (name, _, bytes) = entries.getValue(uri.toString())
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>(name, bytes.size.toLong()))
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file =
                File
                    .createTempFile(
                        "import",
                        ".bin",
                    ).apply { writeBytes(entries.getValue(uri.toString()).third) }
            file.deleteOnExit()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            val entries = mutableMapOf<String, Triple<String, String?, ByteArray>>()
        }
    }

    private companion object {
        const val AUTHORITY = "com.suhel.llamabro.demo.test.files"
    }
}
