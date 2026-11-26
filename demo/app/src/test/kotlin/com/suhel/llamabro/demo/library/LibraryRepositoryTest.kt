package com.suhel.llamabro.demo.library

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.rag.Chunker
import com.suhel.llamabro.rag.Document
import com.suhel.llamabro.rag.DocumentInfo
import com.suhel.llamabro.rag.EmbeddingFormat
import com.suhel.llamabro.rag.IndexMismatch
import com.suhel.llamabro.rag.IngestProgress.Chunked
import com.suhel.llamabro.rag.IngestProgress.Embedded
import com.suhel.llamabro.rag.IngestProgress.Saved
import com.suhel.llamabro.rag.Library
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The demo's library: documents imported, embedded and listed, removed, and rebuilt for a new embedder. */
@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val bge = HfModel.parse("ggml-org/bge-small-en-v1.5-Q8_0-GGUF:Q8_0")
    private val fs = FakeFileSystem()
    private val dir = "/data/no_backup/library".toPath()
    private val fake = FakeLlamaBro()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fits: Boolean? = true
    private val host =
        EmbedderHost(
            fake.llamaBro,
            source = { ModelSource.file("/fake/${it.repo}.gguf") },
            policy = { DevicePolicy.Auto },
            estimate = { _, _, _ -> EmbedderEstimate(fits, needBytes = 90_000_000, budgetBytes = 40_000_000) },
            beside = { ChatBeside("Gemma 3 4B", reserveBytes = 0) },
            scope = scope,
        )

    // one passage per paragraph
    private val chunker = Chunker(maxTokens = 8, overlap = 0f)
    private val files = mutableMapOf<String, Document>()
    private val repository =
        LibraryRepository(fs, dir, host, bge, read = { files.getValue(it.toString()) }, chunker = { chunker })

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
    }

    private fun unit(i: Int, dims: Int = 4) = FloatArray(dims) { if (it == i % dims) 1f else 0f }

    private fun file(name: String, vararg paragraphs: String): Uri {
        val uri = Uri.parse("content://files/$name")
        files[uri.toString()] =
            Document(name.substringBefore('.'), name.substringBefore('.'), paragraphs.joinToString("\n\n"), name)
        return uri
    }

    @Test fun importShowsProgressThenListsTheDocument() =
        runBlocking {
            assertEquals(emptyList<DocumentInfo>(), repository.documents.first())
            val notes = file("notes.md", "Para 00 text.", "Para 01 text.")
            fake.embeddings(unit(0)) // the library's probe
            fake.embeddings(unit(1), unit(2))

            assertEquals(listOf(Chunked(2), Embedded(2, 2), Saved), repository.import(notes).toList())
            assertEquals(listOf(DocumentInfo("notes", "notes", "notes.md", 2)), repository.documents.first())
        }

    @Test fun importingTheSameFileAgainReplacesIt() =
        runBlocking {
            val notes = file("notes.md", "Para 00 text.")
            fake.embeddings(unit(0))
            fake.embeddings(unit(1))
            repository.import(notes).toList()
            file("notes.md", "Para 00 text.", "Para 01 text.")
            fake.embeddings(unit(1), unit(2))
            repository.import(notes).toList()

            assertEquals(listOf(DocumentInfo("notes", "notes", "notes.md", 2)), repository.documents.first())
        }

    @Test fun removeDropsIt() =
        runBlocking {
            fake.embeddings(unit(0))
            fake.embeddings(unit(1))
            repository.import(file("a.md", "Para 00 text.")).toList()
            fake.embeddings(unit(2))
            repository.import(file("b.md", "Para 01 text.")).toList()

            repository.remove("a")
            assertEquals(listOf("b"), repository.documents.first().map { it.id })
            fake.embeddings(unit(2))
            assertEquals(listOf("Para 01 text."), repository.search("para").map { it.text })
        }

    @Test fun mismatchOffersRebuild() =
        runBlocking {
            // a library another embedder built: vectors of 3 floats, where the one on the phone gives 4
            val other = fake.llamaBro.loadModel(ModelSource.file("/fake/old.gguf")) { task = ModelTask.Embedding() }
            fake.embeddings(unit(0, dims = 3))
            Library.open(fs, dir, other, EmbeddingFormat.None, chunker).apply {
                fake.embeddings(unit(1, dims = 3), unit(2, dims = 3))
                add(Document("a", "Handbook", "Para 00 text.\n\nPara 01 text.", "a.md")).toList()
                close()
            }
            other.close()
            assertNull(repository.mismatch.value)

            fake.embeddings(unit(0)) // the probe sees 4 dimensions
            try {
                repository.import(file("b.md", "Para 02 text.")).toList()
                fail("a file went into a library another embedder built")
            } catch (_: IndexMismatch) {
                // the page offers to rebuild
            }
            val mismatch = repository.mismatch.value ?: error("no mismatch")
            assertEquals("BGE Small", mismatch.wanted)
            // nothing was deleted: the document is still listed
            assertEquals(listOf("a"), repository.documents.first().map { it.id })

            fake.embeddings(unit(0)) // the rebuilt library's probe
            fake.embeddings(unit(1), unit(2))
            assertEquals(listOf(Chunked(2), Embedded(2, 2), Saved), repository.rebuild().toList())
            assertNull(repository.mismatch.value)
            assertEquals(listOf(DocumentInfo("a", "Handbook", "a.md", 2)), repository.documents.first())
            fake.embeddings(unit(0)) // the rebuilt library opens on the next import
            fake.embeddings(unit(3))
            repository.import(file("b.md", "Para 02 text.")).toList()
            assertEquals(listOf("a", "b"), repository.documents.first().map { it.id })
        }

    @Test fun doesNotFitSaysSo() =
        runBlocking {
            fits = false
            try {
                repository.import(file("a.md", "Para 00 text.")).toList()
                fail("an embedder that doesn't fit loaded")
            } catch (e: EmbedderDoesNotFit) {
                assertEquals(EmbedderFit.DoesNotFit("Gemma 3 4B", 90_000_000, 40_000_000), e.fit)
            }
            assertEquals(0, fake.openModels)
            assertEquals(emptyList<DocumentInfo>(), repository.documents.first())
        }
}
