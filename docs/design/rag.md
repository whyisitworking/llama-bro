# Asking your own documents: `llamabro-rag` and the demo's library

Status: **SDK part built (Tasks 1-8); the demo's Library page (step 9) and chat use (step 10) built, the phone run pending** (2026-11-26); the sections below describe the design, and where the
built module differs the note says so (see "As built" at the end). Plan in [docs/superpowers/plans/2026-11-26-llamabro-rag.md](../superpowers/plans/2026-11-26-llamabro-rag.md). Decisions are recorded in
[decisions.md](../decisions.md): D167 to D171 for the SDK part.

## Intent

The most common reason a developer puts a model on a phone is to answer questions over data that must stay on the
phone: notes, manuals, a company handbook, a person's own files. The SDK already has the two model calls this needs,
`Model.embed` and `Model.rerank` (the embeddings decision in decisions.md, F-entries on bge-small and
jina-reranker), and that decision deliberately left out "similarity or search helpers". Everything around those calls,
chunking, storing vectors, searching and citing, is what every app would otherwise write again, badly. `llamabro-rag`
is that layer, as a separate optional artifact, so core stays llama-server's features and nothing more.

## Goals

- Add documents, search them, and get passages with their source and position, in a few lines.
- Good retrieval with small embedding models: hybrid search (vectors and keywords) and an optional reranker, because
  small embedders miss exact names, numbers and rare words that keyword search catches.
- Plug into a `Conversation` as a tool, so a model that calls tools searches when it needs to (D14, D62), and a
  simple "retrieve, then ask" path for models that don't.
- Persist an index on disk and reopen it quickly; detect when it was built with another embedding model.
- Pure Kotlin (the multiplatform soft rule): Okio for files, coroutines, no `android.*`.
- The demo shows it: a library of documents a chat can answer from, with citations you can tap.

## Non-goals

- Reading file formats. The module takes text; the app extracts it (the demo handles plain text and Markdown, and PDF
  if the platform's text extraction is good enough, see the demo section).
- An approximate-nearest-neighbour index (HNSW and the like) in v1. Exact search is fast enough at phone scale (below).
- Sync, sharing, or a server-side store.
- Web search or crawling (the research agent is a separate idea).

## Anti-goals

- An index that silently mixes vectors from two embedding models, or from one model with different query
  instructions. It must refuse and say why.
- Retrieval that reaches the model without the person being able to see what was retrieved: every answer that used the
  library shows its sources.
- Policy in the SDK: no limits on what may be indexed, no refusals. The app decides (the "no app policy" rule).

## Constraints from the code as it is

- **Counting tokens on an embedding model: check first.** `ModelTask`'s doc says an embedding model answers only the
  embedding routes, but upstream's `/tokenize` doesn't depend on generation and likely works on an `--embeddings`
  load. Step 1 checks upstream. If it works, the chunker counts real tokens with the embedder's own tokenizer (and the
  doc is corrected). If it doesn't, chunk sizes are estimated from characters with a margin, and a chunk that still
  exceeds the batch (`LlamaBroException.InputTooLong(tokens, limit)`, which carries the real count) is split and
  retried.
- **The SDK neither splits text nor raises the batch size** (the embeddings decision): the module chooses chunk sizes
  under the model's batch, read from `ModelInfo`/`loadReport`.
- **Speed we know:** bge-small embeds about 108 sentences a second on the NPU, 46 on the GPU, 42 on the CPU;
  jina-reranker-tiny ranks 227 a second on the NPU (findings.md, "Search models"). The NPU moves a reranker's
  near-zero scores but keeps its top results (the F-entry on `TaskDeviceTest`).
- **The demo's `ModelHost` holds one model at a time.** A chat model and an embedding model together need a second,
  small host for the embedder (and the reranker). Whether the runtime takes two models at once needs checking first
  (the runtime is a process singleton, D6).

## Approaches

| | How | For | Against |
|---|---|---|---|
| **A. Pure Kotlin, exact search (my pick)** | Vectors in a flat float16 file, scanned in full; BM25 over the chunks in memory; reciprocal rank fusion; optional rerank | No native code, multiplatform, simple, exact; fast enough to about 100k chunks | Memory and scan time grow with the corpus; past ~100k chunks it needs B or C |
| B. SQLite | Chunks and vectors in SQLite, FTS for keywords | Durable, queryable, familiar | Android's platform SQLite doesn't promise FTS5; a bundled SQLite is a native dependency; not multiplatform as is |
| C. A native ANN index (sqlite-vec, usearch) | HNSW or IVF in C/C++ | Scales to millions | A native build per ABI, more to ship and test; nothing a phone corpus needs yet |

A, behind a `VectorStore` interface so B or C can be plugged in later without changing the API.

**Why exact search is enough.** 50,000 chunks of 768 dimensions in float16 are 77 MB, and one query is 38 million
multiply-adds: tens of milliseconds on a phone core. A 300-page book is about 600 chunks of 256 tokens. To measure,
not to assume (a finding).

## Design

### API

```kotlin
val library = Library.open(
    dir = libraryDir,                         // Okio Path; created if missing
    embedder = embeddingModel,                // a Model loaded with ModelTask.Embedding
    format = EmbeddingFormat.of(model.info),  // query/document instructions; presets for known families
) // throws IndexMismatch if dir was built with another embedder or format

library.add(Document(id = "handbook", title = "Handbook", text = text, source = "content://…"))
    .collect { progress -> }                  // Flow<IngestProgress>: chunked, embedded n of m, saved
library.remove("handbook")
library.documents(): List<DocumentInfo>

val hits: List<Hit> = library.search(
    query = "how many days of leave?",
    limit = 6,
    reranker = rerankModel,                   // optional, a Model loaded with ModelTask.Rerank
)
// Hit(documentId, title, text, start, end, score, rank)

// For a Conversation: a tool the model calls when it needs the library
conversation { tools += library.searchTool(name = "search_library", limit = 6) }

// For models without tools: passages to put in the prompt, numbered for citation
val context: String = hits.asContext()        // "[1] Handbook, §3: …"
```

### Pieces

1. **Chunker.** Splits at headings, then paragraphs, then sentences, into chunks of about 256 tokens with about 15%
   overlap, estimated from characters with a margin, each keeping its document offsets and its heading path (so a hit
   can say "Handbook › Leave"). Markdown headings are recognised; plain text falls back to paragraphs.
2. **Embedding format.** Many embedders want instructions: a query prefix and a document prefix (nomic's
   `search_query:`, EmbeddingGemma's `task: search result | query:`, Qwen3-Embedding's instruct line). `EmbeddingFormat`
   holds them, with presets chosen from the model's architecture and name, and `None` by default. The format is part
   of the index's identity.
3. **Embedding.** Batches of chunks through `Model.embed(texts)`, normalised (Euclidean, so dot product is cosine),
   with a chunk split and retried on `InputTooLong`. Progress per batch, cancellable, resumable: a document is
   committed only when all its chunks are saved.
4. **Store.** A folder per library: `manifest.json` (format version, embedder identity: model file name and size,
   dimensions, pooling, normalisation, format), `chunks.jsonl` (text, offsets, headings), `vectors.f16` (row per
   chunk), `deleted` (tombstones, compacted when they pass a quarter of the rows). Writes go to a temporary file and
   are renamed, so a crash leaves the last good index.
5. **Search.** Dense: the query's vector against every row, top 50. Keywords: BM25 over the chunks (built on open,
   kept in memory), top 50. Reciprocal rank fusion (k = 60) merges them. With a reranker, the top 20 are reranked and
   the best `limit` kept. Without one, the fused top `limit`.
6. **Tool.** `searchTool` is a typed tool (`llamabro-typed`) taking `query: String` and returning the numbered
   passages, so the model can cite `[n]`; an empty result says so plainly so the model doesn't invent.

### Module

`sdk/llamabro-rag`, published, depending on `llamabro` and `llamabro-typed` (for the tool), Okio and
kotlinx.serialization. Tests use `llamabro-testing` and the test set's real small models (bge-small,
jina-reranker-tiny).

### The demo: a library

- **Library page** in the sidebar: documents with their size, chunk count and state; add from the file picker
  (`text/*`, Markdown; PDF if Android's own text extraction works, otherwise not in v1, see below); progress while
  embedding; remove.
- **Embedding model:** a suggested entry in the Models catalog (a small multilingual embedder; bge-small for English
  only), downloaded on first use of the library with the person's consent, like any model. A reranker is optional,
  under Settings.
- **In a chat:** a "Use my library" toggle in the message bar's attachments. On a model that calls tools, the chat
  adds the `search_library` tool; on one that doesn't, the top passages go into the turn. A reply that used the library
  shows source chips under it; tapping one opens the passage in a sheet, highlighted.
- **Memory:** the embedder (about 70-600 MB) runs beside the chat model in its own small host; the library shows the
  fit (`estimateFit`) before the first download.
- **PDF:** check Android's PDF text extraction (newer `PdfRenderer` and `androidx.pdf`) on a few real files first; if
  it's poor, v1 ships text and Markdown and says so.

### Tests and evaluation

- **Host unit tests:** the chunker (headings, offsets, overlap, long sentences, Markdown edge cases), BM25, RRF, the
  store's crash safety (kill between writes), tombstones and compaction, `IndexMismatch`, the tool's output.
- **Host model tests:** bge-small and jina-reranker-tiny end to end: add, search, rerank, reopen.
- **Retrieval evaluation (a finding):** the corpus is our own `docs/` folder (architecture, decisions, findings, a few
  thousand chunks), with about 40 hand-written questions whose answers sit in known passages. Recall at 5 for dense
  only, BM25 only, hybrid, and hybrid with reranking, on bge-small and on one multilingual embedder. This decides the
  defaults (chunk size, overlap, top-k, whether reranking is on by default in the demo).
- **On the phone, attended and short:** ingest speed on the NPU, search time at 1k, 10k and 50k chunks, memory with
  the chat model loaded.

### Risks

- Small chat models use retrieved passages poorly or ignore the tool. The fallback path (passages in the prompt) and
  the evaluation tell us which models to suggest.
- Two models in memory on mid-range phones. The fit estimate decides, and the library says when it can't run beside
  the current chat model.
- Character-based chunk sizes overshoot on some scripts (CJK); the `InputTooLong` split-and-retry covers correctness,
  the margin covers speed.

## Plan

Each step ends with its checks passing and a commit.

1. **Spikes:** two models loaded at once in the runtime (host test); `/tokenize` on an embedding load, upstream and
   SDK (host test); Android PDF text extraction on five real PDFs (notes only).
2. **Module skeleton:** `sdk/llamabro-rag`, `Document`, `Hit`, `EmbeddingFormat` with presets, the chunker and its
   tests.
3. **Store:** the folder format, atomic writes, tombstones, compaction, `IndexMismatch`; tests including crash safety.
4. **Ingest:** batched embedding, split-and-retry, progress, cancel and resume; host model tests on bge-small.
5. **Search:** dense, BM25, RRF, rerank; tests.
6. **Conversation tool and `asContext`**; tests with `FakeLlamaBro`.
7. **Retrieval evaluation** on `docs/`; a finding; defaults set from it.
8. **Docs:** decisions (the module and its scope, exact search, hybrid by default), architecture.md, release notes,
   README modules table; `apiDump`.
9. **Demo:** the embedder's host, the Library page, the catalog entry, ingest progress; previews.
10. **Demo chat:** the toggle, tool or prompt path by model, source chips and the passage sheet; journeys over the fake
    engine.
11. **Phone run, attended:** ingest speed, search time, memory; findings; the demo design doc updated.

Rough size: steps 1-8 about a week and a half, steps 9-10 about a week, step 11 an attended hour.

## Left to the builder

BM25's tokenisation details (lowercasing, simple stemming or none), the JSON layout of `chunks.jsonl`, the exact
progress events, and the passage sheet's layout within the design system.

## As built (SDK part)

The module is in `sdk/llamabro-rag`; [architecture.md](../architecture.md) section 5.10 describes it and D167 to D171
record the choices. Where it differs from the text above, the code is right:

- **Tokens are counted, not estimated.** F190 found that `/tokenize` answers on an embedding load, so the chunker counts
  every chunk with `TokenCounter.Model(embedder)` (special tokens and the document prefix included) and the character
  estimate only guides where to split (D169). The "estimate with a margin" and the CJK risk are gone; the
  `InputTooLong` cut-and-retry remains as the safety net.
- **`Library.open` takes the file system and the pooling:** `Library.open(fs, dir, embedder, format, chunker, pooling)`,
  with Okio's `FileSystem`, since a loaded model reports neither its file name nor its pooling. The identity holds the
  model's name and weight size instead of its file name (D168).
- **The folder** has `manifest.json` written last, append-only data files whose trailing garbage is cut off, and a
  compaction committed by `manifest.next` (D168). It is not "write a temporary file and rename" throughout.
- **`add` is one commit** for a document and the removal of any older one with its id; a compaction that fails
  afterwards does not fail the `add`.
- **Search** is as designed (D170). `Hit.score` is the fusion's score, or the reranker's with one.
- **The tool** is `Library.searchTool(name, limit, reranker, description)`, and `asContext()` is `List<Hit>.asContext()`
  (D171).
- **Evaluation (F191):** the defaults of 256 tokens, 15% overlap, top 50 each, k 60, rerank 20 and limit 6 stayed.
  Reranking with jina-reranker-tiny did not help on the question set, so the demo has no evidence for turning it on. The
  multilingual embedder comparison is still open.
- **Reading and rebuilding without opening:** `Library.contents(fs, dir)` lists a library and the embedder that built
  it without loading one (the demo's page lists before the embedder loads), and `Library.rebuild(...)` embeds a library
  another embedder built again from its stored passages, into a folder beside it that takes its place once whole.
- **The demo's Library page (step 9)** is in [demo-app.md](demo-app.md) §4.2: bge-small-en-v1.5 Q8_0 as the suggested
  embedder, text and Markdown import, the fit beside the chat model, rebuild on a mismatch.
- **The demo's chat use (step 10):** "Use my library" is a switch in the message bar's **+** menu, which is a menu only
  while the library has a document (else the **+** opens the photo picker as before); it is saved per chat (database
  version 8, `chats.useLibrary`). A model whose load reports `supports_tool_calls` gets the search tool; any other gets
  the top six passages (`search`, no reranker, F191) after the chat's instructions in the turn's system prompt, so the
  person's message is stored and sent as written. The tool is the demo's own, with `searchTool`'s name, `SearchArgs`,
  `asContext()` text and D171's failure messages, because the app must see the hits to save them and the embedder is
  leased per search rather than held for the turn. The hits a reply used are saved with it (`messages.sources`, the
  passage's text included), numbered from 1 in the order found, so one search's numbers are the `[n]` the reply cites;
  chips show the cited ones (all of them when none is cited), and a chip opens the passage highlighted in its document's
  text, which the demo keeps beside the library (`library-texts/`), or alone once the document is removed. The
  Library's embedder (and any model named for embedding or reranking) is never offered as a chat model.
- **Still pending:** the PDF text spike (needs the phone), the phone run (step 11) and
  the phone measurements.
