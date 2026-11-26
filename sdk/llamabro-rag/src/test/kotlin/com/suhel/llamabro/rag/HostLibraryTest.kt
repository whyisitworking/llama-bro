@file:OptIn(InternalLlamaBroApi::class)

package com.suhel.llamabro.rag

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.Pooling
import com.suhel.llamabro.embed
import com.suhel.llamabro.hostLlamaBro
import com.suhel.llamabro.rag.store.LibraryStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A library over the real core on this machine (CPU) with bge-small, run by sdk/scripts/check.sh's e2e step: a document
 * is chunked by the model's own tokenizer, embedded in batches, committed, and read back after reopening. Skipped
 * without `-Pllamabro.host.lib` and `-Pllamabro.host.models`. Task 5 adds search to it.
 */
class HostLibraryTest {
    @Test
    fun addReopenOnBgeSmall() =
        runBlocking<Unit> {
            val lib = System.getProperty("llamabro.host.lib")
            val models = System.getProperty("llamabro.host.models")
            assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null && models != null)
            val tmp = Files.createTempDirectory("llamabro-rag").toFile()
            val dir = File(tmp, "library").toOkioPath()
            val fs = FileSystem.SYSTEM
            hostLlamaBro(lib, tmp)
                .loadModel(ModelSource.file(File(models!!, "bge-small-f16.gguf").path)) {
                    task = ModelTask.Embedding(Pooling.Mean)
                }.use { model ->
                    val format = EmbeddingFormat.of(model.info)
                    val chunker = Chunker(maxTokens = 48, counter = TokenCounter.Model(model))
                    val library = Library.open(fs, dir, model, format, chunker, Pooling.Mean)
                    val progress = library.add(Document("guide", "Guide", guide, "guide.md")).toList()
                    println("progress: $progress")

                    val chunked = progress.first() as IngestProgress.Chunked
                    val embedded = progress.filterIsInstance<IngestProgress.Embedded>()
                    assertTrue("one batch of 16 or more: $progress", embedded.size >= 2 && chunked.chunks > 16)
                    assertEquals(IngestProgress.Saved, progress.last())
                    val rows = embedded.last().of
                    assertEquals(rows, embedded.last().done)
                    assertEquals(listOf(DocumentInfo("guide", "Guide", "guide.md", rows)), library.documents())
                    library.close()

                    val reopened = Library.open(fs, dir, model, format, chunker, Pooling.Mean)
                    assertEquals(listOf(DocumentInfo("guide", "Guide", "guide.md", rows)), reopened.documents())
                    // each row is its passage's vector, as the model gives it with the prefix (float16 on disk)
                    val store = LibraryStore(fs, dir).apply { open(reopened.identity) }
                    for (row in listOf(0, rows - 1)) {
                        val chunk = store.chunk(row)
                        assertEquals(chunk.text, guide.substring(chunk.start, chunk.end))
                        val vector = model.embed(format.documentPrefix + chunk.text)
                        val (nearest, score) = store.vectors.nearest(vector, 1).single()
                        assertEquals(row, nearest)
                        assertTrue("cosine $score", score > 0.999f)
                    }
                    reopened.close()

                    try {
                        Library.open(fs, dir, model, format, chunker, Pooling.Cls)
                        fail("opened a mean-pooled library as cls-pooled")
                    } catch (expected: IndexMismatch) {
                    }
                }
            tmp.deleteRecursively()
        }

    private companion object {
        val guide =
            buildString {
                append("# Field guide\n\n")
                val topics = listOf("Leave", "Travel", "Equipment", "Security", "Onboarding", "Payroll")
                for (topic in topics) {
                    append("## $topic\n\n")
                    repeat(4) { i ->
                        append(
                            "$topic rule ${i + 1}: requests go to your manager at least two weeks ahead, and the " +
                                "team lead confirms them in writing. Exceptions need the director's signature.\n\n",
                        )
                    }
                }
            }
    }
}
