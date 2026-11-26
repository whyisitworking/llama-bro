@file:OptIn(InternalLlamaBroApi::class)

package com.suhel.llamabro.rag

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.Pooling
import com.suhel.llamabro.hostLlamaBro
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The retrieval evaluation behind F191 (research/rag/eval/README.md): the repo's own docs indexed at three chunk sizes,
 * and the hand-written questions of research/rag/eval/questions.jsonl searched four ways (dense, BM25, hybrid, hybrid
 * reranked by jina-reranker-tiny). A question is found at k when one of the top k passages comes from its document and
 * overlaps its answer span. It measures and prints a table; it gates nothing, and sdk/scripts/check.sh doesn't run it.
 * Skipped without `-Pllamabro.host.lib` and `-Pllamabro.host.models`.
 */
class HostRagEvalTest {
    @Test
    fun recallOnOurDocs() =
        runBlocking<Unit> {
            val lib = System.getProperty("llamabro.host.lib")
            val models = System.getProperty("llamabro.host.models")
            assumeTrue("the evaluation runs by hand, see research/rag/eval/README.md", lib != null && models != null)
            val root = repoRoot()
            val corpus = corpus(root)
            val questions = questions(root, corpus)
            println(
                "corpus: ${corpus.size} files, ${corpus.values.sumOf { it.length }} chars; ${questions.size} questions",
            )

            val tmp = Files.createTempDirectory("llamabro-rag-eval").toFile()
            val llamaBro = hostLlamaBro(lib!!, tmp)
            val started = System.nanoTime()
            val rows = ArrayList<String>()
            val misses = sortedMapOf<String, MutableList<String>>()
            llamaBro
                .loadModel(ModelSource.file(File(models!!, "bge-small-f16.gguf").path)) {
                    task = ModelTask.Embedding(Pooling.Mean)
                }.use { embedder ->
                    llamaBro
                        .loadModel(ModelSource.file(File(models, "jina-reranker-tiny-f16.gguf").path)) {
                            task = ModelTask.Rerank
                        }.use { reranker ->
                            for (size in SIZES) {
                                val dir = File(tmp, "library-$size").toOkioPath()
                                val chunker =
                                    Chunker(maxTokens = size, overlap = OVERLAP, counter = TokenCounter.Model(embedder))
                                val format = EmbeddingFormat.of(embedder.info)
                                val library =
                                    Library.open(
                                        FileSystem.SYSTEM,
                                        dir,
                                        embedder,
                                        format,
                                        chunker,
                                        Pooling.Mean,
                                    )
                                val indexed = System.nanoTime()
                                corpus.forEach { (path, text) ->
                                    library.add(Document(path, path, text, path)).toList()
                                }
                                val chunks = library.documents().sumOf { it.chunks }
                                val indexSeconds = seconds(indexed)
                                for (mode in Mode.entries) {
                                    val searched = System.nanoTime()
                                    val ranks =
                                        questions.map { q ->
                                            rankOf(q, mode.search(library, q.question, reranker))
                                        }
                                    val at1 = ranks.count { it == 1 }
                                    val at5 = ranks.count { it in 1..K }
                                    rows +=
                                        "| $size | $chunks | ${"%.0f".format(indexSeconds)} | ${mode.label} | " +
                                        "${percent(at1, questions.size)} | ${percent(at5, questions.size)} | " +
                                        "${"%.0f".format(seconds(searched) * MS / questions.size)} |"
                                    questions.zip(ranks).filter { (_, rank) -> rank !in 1..K }.forEach { (q, _) ->
                                        misses.getOrPut(q.question) { ArrayList() } += "$size ${mode.label}"
                                    }
                                }
                                library.close()
                            }
                        }
                }
            println("| chunk tokens | chunks | index s | mode | recall@1 | recall@5 | ms/query |")
            println("|---|---|---|---|---|---|---|")
            rows.forEach(::println)
            println("missed at 5 (of ${SIZES.size * Mode.entries.size} runs):")
            misses.forEach { (question, runs) -> println("  ${runs.size}: $question [${runs.joinToString()}]") }
            println("total ${"%.0f".format(seconds(started))} s")
            tmp.deleteRecursively()
        }

    private class Question(val question: String, val doc: String, val start: Int, val end: Int)

    private enum class Mode(val label: String) {
        Dense("dense") {
            override suspend fun search(library: Library, query: String, reranker: Model) =
                library.embedQuery(query).let { vector ->
                    library.reading { store -> store.vectors.nearest(vector, K).map { store.chunk(it.first) } }
                }
        },
        Keyword("BM25") {
            override suspend fun search(library: Library, query: String, reranker: Model) =
                library.reading { store -> store.bm25.search(query, K).map { store.chunk(it.first) } }
        },
        Hybrid("hybrid") {
            override suspend fun search(library: Library, query: String, reranker: Model) =
                library.search(query, limit = K).map { it.toChunk() }
        },
        Reranked("hybrid + rerank") {
            override suspend fun search(library: Library, query: String, reranker: Model) =
                library.search(query, limit = K, reranker = reranker).map { it.toChunk() }
        }, ;

        abstract suspend fun search(library: Library, query: String, reranker: Model): List<Chunk>
    }

    private companion object {
        val SIZES = listOf(128, 256, 512)
        const val OVERLAP = 0.15f
        const val K = 5
        const val MS = 1000.0
        val DOCS = listOf("docs", "docs/research", "docs/design")

        fun Hit.toChunk() = Chunk(documentId, 0, text, start, end, headings)

        // the 1-based rank of the first passage that overlaps the question's span, or 0
        fun rankOf(question: Question, found: List<Chunk>): Int =
            found.indexOfFirst {
                it.documentId == question.doc && it.start < question.end && question.start < it.end
            } + 1

        fun repoRoot(): File =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "docs/architecture.md").isFile }

        // docs/*.md, docs/research/findings.md and docs/design/*.md, keyed by their path from the repo's root
        fun corpus(root: File): Map<String, String> =
            DOCS
                .flatMap { dir ->
                    File(root, dir).listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().toList()
                }.filter { it.parentFile.name != "research" || it.name == "findings.md" }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
                .toSortedMap()

        // each answer span must sit verbatim in its document; the first place it occurs is where the answer is
        fun questions(root: File, corpus: Map<String, String>): List<Question> =
            File(root, "research/rag/eval/questions.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
                val o = Json.parseToJsonElement(line).jsonObject
                val doc = o.getValue("doc").jsonPrimitive.content
                val span = o.getValue("answer_span").jsonPrimitive.content
                val start = corpus[doc]?.indexOf(span) ?: -1
                assertTrue("span not in $doc: $span", start >= 0)
                Question(o.getValue("question").jsonPrimitive.content, doc, start, start + span.length)
            }

        fun seconds(since: Long) = (System.nanoTime() - since) / 1e9

        fun percent(n: Int, of: Int) = "%.1f%% ($n)".format(n * 100.0 / of)
    }
}
