# Asking Your Own Documents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `llamabro-rag` lets an app add documents, search them with an on-device embedder (hybrid with keywords, an
optional reranker) and hand the passages to a chat, and the demo's Library page and chat toggle show it.

**Architecture:** A pure-Kotlin module over `Model.embed` and `Model.rerank`: a chunker, a folder-backed store (float16
vectors scanned exactly, chunks as JSON lines), BM25 in memory, reciprocal rank fusion, and a typed `search_library`
tool. The demo adds a second small host for the embedder, a Library page and source chips in chat.

**Tech Stack:** Kotlin 2.3.10, coroutines, kotlinx.serialization, Okio 3, `llamabro-typed`; Compose with
`:demo:designsystem`; Room only in the demo if needed for library metadata (prefer the module's own manifest).

**Spec:** [docs/design/rag.md](../../design/rag.md) (approved 2026-11-26). Read it before any task.

## Global Constraints

- No `android.*` and no `java.io`/`java.nio` file APIs in `sdk/llamabro-rag/src/main`: Okio `FileSystem` and `Path`.
- Core `llamabro` does not change unless Task 1 shows `/tokenize` must be allowed on an embedding load to match
  upstream; then that is a separate commit citing the finding.
- An index refuses (`IndexMismatch`) any embedder or format other than the one it was built with.
- Defaults until Task 7 replaces them from its finding: chunk about 256 tokens, overlap 15%, dense and BM25 top 50
  each, RRF k = 60, rerank the top 20, `limit` 6.
- The SDK makes no policy: no size caps or refusals on what is indexed.
- Public API changes: `./gradlew -p sdk :llamabro-rag:apiDump` and commit the `.api` diff.
- Every SDK task ends with `sdk/scripts/check.sh --only jvm` (and `api`, `lint`, `quality` once the module exists)
  passing; the docs task with the full `sdk/scripts/check.sh`; demo tasks with `demo/scripts/check.sh`. No inference
  on an emulator; device runs only in Task 11, attended.
- IDs: take the next free D and F numbers at the time of writing (the server plan reserves D163-D165, F189-F190).
  Commit after each task; do not push.

## Review Focus

1. **The app is killed mid-ingest** of a long document: on reopen the library holds every earlier document intact and
   none of the half-added one (Task 3 `crashBetweenWritesKeepsLastGoodIndex`, Task 4 `cancelMidDocumentCommitsNothing`).
2. **Text with no headings or paragraph breaks** (a pasted wall of text, a single 5,000-word line, CJK without spaces):
   chunks stay under the input limit and offsets still point at the right characters (Task 2
   `wallOfTextSplitsBySentenceThenCharacters`, `cjkWithoutSpacesStaysUnderLimit`).
3. **Adding the same document id twice** replaces it rather than duplicating its passages (Task 4
   `addingAnExistingIdReplacesIt`).
4. **A query with no good match** returns few or no hits and the tool says so plainly, so the model doesn't invent
   (Task 5 `unrelatedQueryReturnsLowScores`, Task 6 `emptyResultSaysNothingFound`).
5. **The embedding model changes** (the person picks another embedder): opening the old library fails with
   `IndexMismatch` naming both models, and the demo offers to rebuild (Task 3 `otherEmbedderIsIndexMismatch`, Task 9
   `mismatchOffersRebuild`).

---

### Task 1: Spikes that decide two details

**Files:**
- Create: `sdk/llamabro/src/test/kotlin/com/suhel/llamabro/HostTwoModelsTest.kt` (host, real core: a Generate model
  and bge-small loaded at once; both answer)
- Create: `sdk/llamabro/src/test/kotlin/com/suhel/llamabro/HostEmbeddingTokenizeTest.kt` (`/tokenize` through
  `rawRequest` on a bge-small `ModelTask.Embedding` load)
- Create: `research/rag/tokenize-upstream.sh` (upstream `llama-server --embeddings` on bge-small, `POST /tokenize`)
- Create: `research/rag/pdf-text.md` (notes: Android's PDF text extraction on five real PDFs, on a phone the owner
  provides; not run unless asked)

- [ ] **Step 1:** write both host tests; run
  `./gradlew -p sdk :llamabro:testDebugUnitTest --tests '*HostTwoModels*' --tests '*HostEmbeddingTokenize*'
  -Pllamabro.host.lib=… -Pllamabro.host.model=…`.
- [ ] **Step 2:** run `research/rag/tokenize-upstream.sh`.
- [ ] **Step 3:** record one finding: whether two models load at once, and whether upstream and the SDK answer
  `/tokenize` on an embedding load. If upstream answers and the SDK refuses, the SDK follows upstream in a separate
  commit and `ModelTask`'s KDoc is corrected.
- [ ] **Step 4:** keep the tests that pass as regression tests; commit
  `tests: two models at once, and tokenize on an embedding load; F<n>`.

**Decides:** `Chunker`'s token counter in Task 2 is `TokenCounter.Model(embedder)` when tokenize works, else
`TokenCounter.Estimate` only.

### Task 2: Module, documents and the chunker

**Files:**
- Create: `sdk/llamabro-rag/build.gradle.kts` (as `llamabro-typed`'s: android library, `llamabro.library`,
  `llamabro.api-check`, kotlin serialization, `llamabro.publish`; namespace `com.suhel.llamabro.rag`; `explicitApi()`;
  `api(project(":llamabro"))`, `api(project(":llamabro-typed"))`, Okio)
- Modify: `sdk/modules.gradle.kts`, `gradle/libs.versions.toml` (Okio 3, latest stable), `sdk/llamabro-bom`,
  `sdk/scripts/check.sh` (`:llamabro-rag` in `jvm`, `api`, `lint`)
- Create: `sdk/llamabro-rag/src/main/kotlin/com/suhel/llamabro/rag/` `Document.kt`, `Chunk.kt`, `Chunker.kt`,
  `TokenCounter.kt`, `EmbeddingFormat.kt`
- Test: `ChunkerTest.kt`, `EmbeddingFormatTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public data class Document(val id: String, val title: String, val text: String, val source: String? = null)
  public data class Chunk(val documentId: String, val index: Int, val text: String, val start: Int, val end: Int,
                          val headings: List<String>)
  public sealed interface TokenCounter { suspend fun count(text: String): Int
      public data object Estimate : TokenCounter      // ceil(chars / 3.0), conservative for code and CJK
      public class Model(embedder: com.suhel.llamabro.Model) : TokenCounter }
  public class Chunker(maxTokens: Int = 256, overlap: Float = 0.15f, counter: TokenCounter = TokenCounter.Estimate) {
      public suspend fun chunks(document: Document): List<Chunk> }
  public data class EmbeddingFormat(val queryPrefix: String, val documentPrefix: String) {
      public companion object { val None; fun of(info: ModelInfo): EmbeddingFormat } }
  ```
  Split order: Markdown headings (`#`-`######`, keeping the heading path), blank-line paragraphs, sentences
  (`. ! ? 。 ！ ？` followed by space or end), then characters. `start`/`end` are offsets into `Document.text`.
  `EmbeddingFormat.of` presets: nomic-embed (`search_query: ` / `search_document: `), EmbeddingGemma
  (`task: search result | query: ` / `title: none | text: `), Qwen3-Embedding (`Instruct: Given a question, retrieve
  passages that answer it\nQuery: ` / empty), everything else `None`. Read the family from `ModelInfo`'s architecture
  and name; check each preset against its model card when implementing.

- [ ] **Step 1: Write the failing tests.** `ChunkerTest`: `headingsBecomeThePath`, `paragraphsKeptWhole`,
  `overlapRepeatsTheTail`, `offsetsPointAtTheText` (for every chunk, `text == document.text.substring(start, end)`),
  `wallOfTextSplitsBySentenceThenCharacters`, `cjkWithoutSpacesStaysUnderLimit` (Review Focus 2),
  `emptyDocumentHasNoChunks`. `EmbeddingFormatTest`: each preset by architecture and name; unknown is `None`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-rag:testDebugUnitTest`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS. Then `./gradlew -p sdk :llamabro-rag:apiDump` and
  `sdk/scripts/check.sh --only jvm,api,lint,quality`. Expected: `ALL PASS`.
- [ ] **Step 5: Commit** `rag: llamabro-rag, documents, embedding formats and a heading-aware chunker`.

### Task 3: The store

**Files:**
- Create: `sdk/llamabro-rag/src/main/kotlin/com/suhel/llamabro/rag/store/` `LibraryStore.kt`, `Manifest.kt`,
  `Float16.kt`, `VectorStore.kt`
- Test: `LibraryStoreTest.kt`, `Float16Test.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public data class EmbedderIdentity(val modelName: String, val fileBytes: Long, val dimensions: Int,
                                     val pooling: String, val normalization: String, val format: EmbeddingFormat)
  public class IndexMismatch(public val built: EmbedderIdentity, public val wanted: EmbedderIdentity) : Exception()
  public interface VectorStore {                 // exact scan in v1; an ANN store can replace it later
      suspend fun nearest(query: FloatArray, k: Int): List<Pair<Int, Float>>  // row, cosine
  }
  internal class LibraryStore(fs: FileSystem, dir: Path) {
      suspend fun open(wanted: EmbedderIdentity)            // creates, or throws IndexMismatch
      suspend fun commit(documentId: String, title: String, source: String?, chunks: List<Chunk>, vectors: List<FloatArray>)
      suspend fun remove(documentId: String)
      fun chunk(row: Int): Chunk
      val vectors: VectorStore
  }
  ```
  Folder: `manifest.json` (format version 1, `EmbedderIdentity`, documents with their row ranges), `chunks.jsonl`,
  `vectors.f16` (rows of `dimensions` little-endian halves), `deleted` (tombstoned rows). Every write goes to `*.tmp`
  then `atomicMove`; the manifest is written last, so it names only rows that exist. Compact (rewrite without
  tombstones) when tombstones pass 25% of rows.

- [ ] **Step 1: Write the failing tests** on Okio's `FakeFileSystem`: `commitThenReopenReadsTheSame`,
  `crashBetweenWritesKeepsLastGoodIndex` (Review Focus 1: fail the manifest write; reopen sees the previous state),
  `otherEmbedderIsIndexMismatch` (Review Focus 5), `removeTombstonesRows`, `compactsPastAQuarter`,
  `nearestIsExactCosine`; `Float16Test`: round trip within 1e-3 relative, zero, subnormals, ±65504.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-rag:testDebugUnitTest --tests '*Store*' --tests '*Float16*'`.
  Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `rag: a crash-safe folder store with exact float16 vectors`.

### Task 4: `Library` and ingest

**Files:**
- Create: `sdk/llamabro-rag/src/main/kotlin/com/suhel/llamabro/rag/Library.kt`, `Ingest.kt`
- Test: `IngestTest.kt` (over `FakeLlamaBro.embeddings`), `HostLibraryTest.kt` (host, bge-small; skipped without the
  host properties)

**Interfaces:**
- Consumes: `Chunker`, `LibraryStore`, `Model.embed(texts, normalization = Euclidean)`,
  `LlamaBroException.InputTooLong(tokens, limit)`.
- Produces:
  ```kotlin
  public class Library private constructor(…) : AutoCloseable {
      public companion object {
          public suspend fun open(fs: FileSystem, dir: Path, embedder: Model, format: EmbeddingFormat,
                                  chunker: Chunker = Chunker()): Library   // throws IndexMismatch
      }
      public fun add(document: Document): Flow<IngestProgress>
      public suspend fun remove(documentId: String)
      public suspend fun documents(): List<DocumentInfo>
  }
  public sealed interface IngestProgress { data class Chunked(val chunks: Int); data class Embedded(val done: Int, val of: Int); data object Saved }
  public data class DocumentInfo(val id: String, val title: String, val source: String?, val chunks: Int)
  ```
  Embeds in batches of 16 chunks with `documentPrefix` prepended. On `InputTooLong` for a chunk, split it in two at a
  sentence or the middle and retry; lower the chunker's limit to `limit` for the rest of the document.

- [ ] **Step 1: Write the failing tests:** `addEmbedsAndSaves`, `progressCountsBatches`,
  `documentPrefixIsPrepended`, `inputTooLongSplitsAndRetries`, `cancelMidDocumentCommitsNothing` (Review Focus 1),
  `addingAnExistingIdReplacesIt` (Review Focus 3), `removeDropsIt`; `HostLibraryTest.addSearchReopenOnBgeSmall`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-rag:testDebugUnitTest --tests '*Ingest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the unit tests, then `HostLibraryTest` with the host properties. Expected: PASS.
- [ ] **Step 5: Commit** `rag: Library, batched ingest with split-and-retry and progress`.

### Task 5: Search

**Files:**
- Create: `sdk/llamabro-rag/src/main/kotlin/com/suhel/llamabro/rag/search/` `Bm25.kt`, `Fusion.kt`, `Search.kt`
- Modify: `Library.kt` (`search`)
- Test: `Bm25Test.kt`, `FusionTest.kt`, `SearchTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public data class Hit(val documentId: String, val title: String, val text: String, val start: Int, val end: Int,
                        val headings: List<String>, val score: Float, val rank: Int)
  public suspend fun Library.search(query: String, limit: Int = 6, reranker: Model? = null): List<Hit>
  ```
  BM25: k1 = 1.2, b = 0.75; tokens are lowercased runs of letters and digits, each CJK character its own token; no
  stemming. Fusion: RRF with k = 60 over dense top 50 and BM25 top 50. With `reranker`, `Model.rerank(query, top 20
  texts)` orders them and `score` is the reranker's; without, `score` is the fused score. The query gets the format's
  `queryPrefix`.

- [ ] **Step 1: Write the failing tests:** `Bm25Test.exactNameBeatsParaphrase`, `idfFavoursRareWords`;
  `FusionTest.rrfMergesTwoLists` (hand-computed scores); `SearchTest.hybridFindsANumberDenseMisses`,
  `rerankerReordersTopTwenty`, `queryPrefixIsUsed`, `unrelatedQueryReturnsLowScores` (Review Focus 4),
  `limitIsRespected`, `tombstonedRowsNeverReturned`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-rag:testDebugUnitTest --tests '*Bm25*' --tests '*Fusion*' --tests '*Search*'`.
  Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS. `apiDump`.
- [ ] **Step 5: Commit** `rag: hybrid search, BM25 and dense fused by RRF, with an optional reranker`.

### Task 6: The conversation tool and prompt context

**Files:**
- Create: `sdk/llamabro-rag/src/main/kotlin/com/suhel/llamabro/rag/LibraryTool.kt`
- Test: `LibraryToolTest.kt` (a `Conversation` over `FakeLlamaBro` whose reply calls `search_library`)

**Interfaces:**
- Consumes: `tool(name, description, …)` from `llamabro-typed` (`TypedTools.kt`).
- Produces:
  ```kotlin
  public fun Library.searchTool(name: String = "search_library", limit: Int = 6, reranker: Model? = null): TypedTool<SearchArgs, String>
  @Serializable public data class SearchArgs(val query: String)
  public fun List<Hit>.asContext(): String
  ```
  Output for both: one block per hit, `[n] <title> › <headings joined by " › ">\n<text>`, blocks separated by a blank
  line; with no hits, exactly `No passages in the library match this query.`

- [ ] **Step 1: Write the failing tests:** `toolSearchesAndNumbersPassages`, `emptyResultSaysNothingFound`
  (Review Focus 4), `asContextMatchesToolOutput`, `toolNameAndSchema` (`query` is the only, required, string field).
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-rag:testDebugUnitTest --tests '*LibraryTool*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS. `apiDump`; `sdk/scripts/check.sh --only jvm,api`. Expected: `ALL PASS`.
- [ ] **Step 5: Commit** `rag: a search_library tool and numbered passages for the prompt`.

### Task 7: Retrieval evaluation

**Files:**
- Create: `research/rag/eval/questions.jsonl` (about 40 questions over `docs/`, each with the document and a quoted
  span its answer sits in), `research/rag/eval/RagEval.kt` as a host test in
  `sdk/llamabro-rag/src/test/kotlin/com/suhel/llamabro/rag/HostRagEvalTest.kt`, `research/rag/eval/README.md`

- [ ] **Step 1:** write the questions by reading the docs (not by asking a model), spread across architecture,
  decisions and findings; include exact-name and number questions.
- [ ] **Step 2:** the eval indexes `docs/*.md` and `docs/research/findings.md` and reports recall at 5 for dense,
  BM25, hybrid and hybrid with jina-reranker-tiny, on bge-small and on one multilingual embedder chosen with the owner;
  plus chunk sizes 128, 256 and 512.
- [ ] **Step 3: Run** it on the host. Record the finding with the table.
- [ ] **Step 4:** set the defaults in `Chunker` and `search` from the finding (Global Constraints' numbers change
  only here), with the finding cited in KDoc.
- [ ] **Step 5: Commit** `rag: retrieval evaluated on our own docs; defaults from F<n>`.

### Task 8: SDK docs and decisions

**Files:** `docs/decisions.md` (the module and its scope beside "expose llama-server"; exact search behind
`VectorStore`; hybrid by default; the folder format), `docs/architecture.md` (a section), `docs/release-notes.md`,
`README.md` modules table, `docs/design/rag.md` (status: building).

- [ ] **Step 1:** write the entries and cite them from KDoc.
- [ ] **Step 2: Run** `sdk/scripts/check.sh`. Expected: `ALL PASS`.
- [ ] **Step 3: Commit** `docs: llamabro-rag's decisions, architecture and release notes`.

### Task 9: Demo library (no chat yet)

**Files:**
- Create: `demo/app/src/main/kotlin/com/suhel/llamabro/demo/library/` `EmbedderHost.kt`, `LibraryRepository.kt`,
  `TextImport.kt`
- Create: `demo/app/src/main/kotlin/com/suhel/llamabro/demo/ui/library/` `LibraryScreen.kt`, `LibraryViewModel.kt`,
  `LibraryPreviews.kt`
- Modify: `models/Catalog.kt` (a suggested embedder and an optional reranker), `ui/shell/Routes.kt`
  (`const val LIBRARY = "library"`), the sidebar and chat-list layouts, `ui/Screens.kt`, `AppContainer.kt`,
  `demo/app/build.gradle.kts`
- Test: `EmbedderHostTest.kt`, `LibraryRepositoryTest.kt`, `TextImportTest.kt`, `LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: `Library`, `ModelHost`'s pattern (a second instance or a small class with the same lease contract),
  `LlamaBro.estimateFit`.
- Produces: `class EmbedderHost` (loads the embedder with `ModelTask.Embedding` beside the chat model, releases it
  after 2 minutes idle); `class LibraryRepository { val documents: Flow<List<DocumentInfo>>; fun import(uri: Uri):
  Flow<IngestProgress>; suspend fun remove(id: String); suspend fun search(query: String): List<Hit> }`;
  `TextImport` reads `text/plain` and `text/markdown` (and PDF only if Task 1's notes say Android's extraction is good).

- [ ] **Step 1: Write the failing tests:** `embedderLoadsBesideChatModel`, `embedderReleasedWhenIdle`,
  `importShowsProgressThenListsTheDocument`, `removeDropsIt`, `mismatchOffersRebuild` (Review Focus 5),
  `doesNotFitSaysSo`, `markdownAndTextImport`, `unsupportedTypeIsRefusedWithReason`.
- [ ] **Step 2: Run** `./gradlew :demo:app:testDebugUnitTest --tests '*library*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the screen from `:demo:designsystem` components only (D113), stateless, previews for
  empty, importing, listed, mismatch and does-not-fit states.
- [ ] **Step 4: Run** `demo/scripts/check.sh`. Expected: `ALL PASS`.
- [ ] **Step 5: Commit** `demo: a Library page that imports, embeds and lists documents`.

### Task 10: Library in chat

**Files:**
- Modify: the message bar's attachments (a "Use my library" toggle), `chat/ChatEngine.kt` (add the tool on a model
  that calls tools, else put `asContext()` before the turn), the reply row (source chips)
- Create: `demo/app/src/main/kotlin/com/suhel/llamabro/demo/ui/chat/PassageSheet.kt`
- Test: `ChatLibraryTest.kt`; a journey over the fake engine: toggle on, ask, see chips, open a passage.

**Interfaces:**
- Consumes: `Library.searchTool`, `List<Hit>.asContext`, `ModelInfo`'s tool support.
- Produces: hits used in a reply are saved with the message (their document id, offsets and title), so chips survive
  a restart.

- [ ] **Step 1: Write the failing tests:** `toolModelGetsTheTool`, `noToolModelGetsPassagesInTheTurn`,
  `chipsListTheHitsUsed`, `chipsSurviveRestart`, `passageSheetHighlightsTheSpan`, `toggleOffUsesNothing`.
- [ ] **Step 2: Run** `./gradlew :demo:app:testDebugUnitTest --tests '*ChatLibrary*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `demo/scripts/check.sh`. Expected: `ALL PASS`.
- [ ] **Step 5: Commit** `demo: a chat can answer from the library, with sources you can open`.

### Task 11: Phone run, attended (owner present)

Only when the owner asks for it, short.

- [ ] **Step 1:** ingest speed on the NPU for a 300-page text; search time at 1k, 10k and 50k chunks (synthetic rows
  for the larger two); memory with Qwen3.5 4B and the embedder loaded.
- [ ] **Step 2:** record the finding; update `docs/design/rag.md` and `docs/design/demo-app.md`.
- [ ] **Step 3: Commit** `docs: F<n>, the library on the phone`.
