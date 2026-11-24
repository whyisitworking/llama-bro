# One Models screen: design

Status: superseded by [models-page.md](models-page.md) (D130), 2026-11-21. Was: built, 2026-11-20. Amended
[demo-app.md](demo-app.md) §4.3 (since rewritten for the Models page); the decision is D116 in
[../decisions.md](../decisions.md#d116-one-models-screen-replaces-get-models-and-the-library-a-rows-fit-is-guessed-from-the-search-list-and-drawn-as-a-pace-figure), which also lists where the build departed from this design (the sheet's height, the fit symbol's target, images in model pages, the kept catalog `about`).

## Why

Finding and keeping models is split across two screens, and neither tells the whole story:

- **Get models** (from the chat's "Get more models" and first run) shows suggestions and search results. A row shows a
  download's progress only when the download was started from that row in this visit (`GetModelsViewModel.startedInput`
  is in memory), so leaving and coming back, or starting it elsewhere, loses it.
- **Models** (the library, from the sidebar) groups On this phone and Downloading, but people coming from a chat never
  see it.
- **A model's sheet** never reads download or install state: its button always says "Get …". Tapping it again restarts a
  running download, queues a second size, or silently does nothing for an installed model.
- **Search rows** say little: outside the six catalog models, the line is guessed from the parameter count in the name
  ("Balanced, a good all-rounder"), and nothing says who made the model, what it is for, or that it can't chat at all
  (embedding models look like any other).

Success: at a glance you know what is downloading, what is on the phone and what you could get; a card never offers
"Get" for a model that is downloading or installed; a search result says what the model is before you download it.

## Goals

1. One **Models** screen replaces Get models and the library, reached from the sidebar, the chat's "Get more models"
   and first run's search.
2. Grouped: **Downloading**, **On this phone**, **Suggested for this phone**; typing a search replaces the groups with
   results.
3. Every row's and card's state comes from the repository (`ModelRepository.downloading`, `state(key)`, `library`),
   never from what happened in this visit.
4. The card follows the model's state: downloading, on the phone, or not here.
5. Rows carry no description: the name, the maker, capability icons, the download count and a fit symbol. What a model
   is lives in its card, which pulls up to full screen and shows the model's Hugging Face page as markdown.
6. A row's fit comes with the search results, guessed from the parameter count the same request returns: no call per
   row.
7. Fit and capabilities are read at a glance from symbols, not words.

## Non-goals

- No change to downloads themselves (queueing, the worker, notifications), to fit estimates, or to Basic/Advanced sizes.
- No paging, filters or sorting in search beyond what exists.
- No description line on rows, not even the catalog's own: the app's guess or its own blurb says less than the model's
  page, which the card shows in full.

## Anti-goals

- A screen that shows the same model twice in the default view (a suggestion that is also on the phone or downloading).
- A state that drifts: a row or card that says "Get" while the repository says downloading or installed.
- The SDK deciding what a model is "for" or hiding results: it reports the Hub's facts; the app labels and marks them
  (SDK makes no app policy).

## Approaches considered

- **Merge into one screen (chosen).** One place, one list of truths; removes the split-brain. Costs a larger change:
  the library's features (storage, remove, Default chip, problem line) move into the merged screen.
- **Keep both, group the store.** Smaller change, but the library stays half-hidden and the two screens still overlap.
- **Search row content:** facts from the Hub's tags on rows (maker, capabilities) and the model's whole page in the
  card, chosen over a sentence on every row (one extra request per result, often unhelpful text) and over today's guessed
  line. An earlier draft showed one sentence in the card; the maintainer asked for the full page instead.
- **Fit symbol:** sketched as signal bars and a filling ring (rejected: they read as network signal and download
  progress), a speed gauge (hard to tell apart at 20 dp), go/caution/stop signs and a model block in a phone (reads as a
  battery). Chosen: **pace figures**. For the slow step, a walker on an amber chip and an amber-tinted row were sketched;
  the maintainer chose the figure with a cane, Material's `elderly`, as the clearest "slower than walking". For too big, a
  no-entry sign, falling, lying flat, hurt, standing still, fallen and a wheelchair were sketched; the maintainer chose a
  neutral figure kneeling with its head low, exhausted.

## The design

### Screen (no query)

Top to bottom, as in the sketch:

1. Top bar "Models", Back.
2. Search field, "Search Hugging Face".
3. Storage: "6.1 GB used · 120 GB free" and its bar (from the library).
4. **Downloading**: each download as today's library `DownloadItem` (title, bar, bytes and speed, time left, Pause or
   Resume, ✕ Cancel). Hidden when empty.
5. **On this phone**: each model as today's library row (name, size, last used, Default chip, swipe to remove after the
   dialog). Hidden when empty; "No models yet" when both this and Downloading are empty.
6. **Suggested for this phone**: the catalog suggestions minus any whose repo is on the phone or downloading, as model
   rows (below). Hidden when all are taken.
7. The problem line (a remove or cancel that failed) as today, above the groups.

Tapping a downloading or installed row opens the same card as a suggestion does.

### Screen (query of two characters or more)

Groups are replaced by results. Searching never empties the screen:

- **A thin indeterminate bar** runs just under the search field from the moment a search starts (after the debounce)
  until its answer arrives. Whatever is on screen (the groups, or the previous results) stays, and is replaced only
  when the new results come in.
- **No layout shift**: the bar's slot is always there, a fixed thin line under the field that is empty when idle, so
  nothing below it moves when it appears or goes.
- **No results, failed, offline**: these replace the list only once the search has answered, as today's states do;
  a search typed over one of them keeps it on screen with the bar running.
- The `Searching` state goes; the view model keeps the last shown content and a separate `searching` flag.

A result's row carries a
small badge from the repository: "On phone" when any quant of that repo is installed, "42%" with a thin bar while
downloading, "Paused" when paused, or the failure line. The badge is derived by repo from `library` and `downloading` +
`state(key)`, not from `startedInput`, which goes away.

### The card

A bottom sheet that pulls up to full screen. Header: the name, repo · by maker, and the capability icons each with its
word ("Thinks first", "Sees images"), so the icons are learned here. Then the state part below, and the Get or state
action pinned at the foot while the rest scrolls.

**The model's page**: pulled up, the sheet shows the Hugging Face page of the original model (the base model's
`README.md`; the repo's own when there is no base model), as markdown, scrolling, under the header and above the pinned
action. Fetched when the card opens, never on the list. Catalog models show the page too (their `about` line goes).
Rendered with the chat's markdown renderer: front matter removed, images shown, HTML blocks flattened to their text,
wide tables scroll sideways. While it loads, the sheet stays usable; on failure, a quiet line with Try again. No page:
nothing is shown.

By state of the repo, from the repository:

- **Downloading or paused**: "Downloading <tier or quant> · <size>", the bar, bytes and time left, and Pause/Resume and
  Cancel. No Get.
- **On this phone**: "On this phone · <tier or quant> · <size>", a primary "Chat with <name>" (opens a new chat with that
  model), and "Get another size" which reveals today's Basic/Advanced choice with the installed file marked "On this
  phone" and not selectable.
- **Neither**: today's size choice (fit shown as symbols) and Get.
- **Failed download**: the failure line and "Try again" (today's `get`).

### Model rows

Every model row (suggested, search result, and the badge-carrying result) has the same shape, and no description line:

```
Qwen3.5 4B                         [fit symbol]
by Qwen
[💡][🖼]                              ↓ 1.2M
```

- **Name** and the **fit symbol** on the first line.
- **Maker**: the owner of the original (`SearchRepo.baseModel`'s owner, e.g. `Qwen` for `Qwen/Qwen2.5-Coder-7B`); the
  catalog's maker for catalog models; no line when there is no base model (the uploader is not the maker).
- **Capability icons** on one quiet line, the download count at its end. A screen reader hears words: "Thinks, sees
  images, 1.2 million downloads".
- Rows are no longer tinted by fit; the symbol carries it.

**Capability icons**, always in this order, muted ink, 14–16 dp:

| Capability | Icon | From |
|---|---|---|
| Thinks (catalog only, as today) | lightbulb, `DemoIcons.Think` | the chat's Think switch |
| Talks (catalog only) | sound bars, `DemoIcons.VoiceMode` | voice mode |
| Sees images | picture, `DemoIcons.Image` | attaching an image |
| Coding (a `code` tag, or "coder"/"code" in the name) | `</>`, new: Material `code` | |
| Embeddings or reranking (task or architecture) | new: Material `select_all` | only on can't-chat rows |

No "Chat" icon: nearly every row would carry it.

**Can't chat**: a model with no chat template in its GGUF metadata, or whose task or architecture is embeddings or
reranking (a template alone is not enough: Qwen3-Embedding ships one), shows the no-entry symbol and a muted name;
still openable, but its card's Get is replaced with a line saying the app chats only.

### The fit symbol

Pace figures, in the fit inks. All four share one 20 dp box and one visual weight (none is bolder or larger); the
three standing ones are Material Symbols (filled, copied into `DemoIcons` as paths), the kneeling one is drawn for the
app in the same style:

| Label | Symbol | Ink |
|---|---|---|
| Runs great | `sprint` | great ink |
| Runs well | `directions_run` | ink |
| Slow here | `elderly` (a figure with a cane) | slow ink |
| Too big | kneeling on one knee, a hand on the ground, head hanging low: exhausted after a long run (new, drawn) | no ink |
| Can't chat | `block` (no-entry) | no ink |

The too-big figure is deliberately neutral: tired, not hurt, fallen or disabled (a fallen figure, a figure with an
injury and a wheelchair were sketched and rejected for that reason).

- No "Runs great" words on rows or in the card's sizes (Basic tiers and the Advanced "On this phone" column show the
  symbol only).
- Each symbol has its words as its content description, and long-press shows them as a tooltip.
- The card's header explains nothing about fit; the long-press and the screen reader carry the words.

### A row's fit, from the search list alone

Today a non-catalog row's fit is one tree call per row (`ModelDiscovery.quickFit` lists the repo, picks the
Recommended file and sizes it), four at a time, so fits arrive late, and a repo without a Q4 file gets none. Instead the
search request asks the Hub for each repo's GGUF metadata (`expand[]=gguf`), which gives the parameter count, the
architecture, the context length and whether a chat template is present, in the same single request. Checked against
the live Hub on 2026-11-19.

- **Size guess**: parameters × bits per weight ÷ 8 for the Recommended tier (Q4_K_M, about 4.85 bits per weight), plus a
  typical image reader for a model that sees images, scaled by parameter count. The bits-per-weight figures and the reader
  sizes are fitted once against the catalog's known files and recorded as a finding.
- **Mixture of experts**: memory counts every parameter; speed counts the active ones, read from an `A<n>B` in the name
  when the architecture is a MoE one; without it, speed uses every parameter (pessimistic).
- **Then today's rules**: the guessed size goes through `Fits.quick` unchanged, so a row shows its fit with the results,
  with no wait and no extra request.
- **The card stays exact**: opening a card lists the real files (as today) and its tiers show measured sizes; where the
  card's Recommended fit differs from the row's guess, the row is updated to the card's.
- **No parameter count** (the Hub didn't read the GGUF): the row shows no fit, as a failed estimate does today.

Cost: `expand` replaces the default fields, so the request names every field it needs (downloads, likes, tags,
pipeline_tag, gguf, lastModified), and the chat templates come along: a 40-result page grew from 23 KB to about 560 KB
(0.36 s to 0.66 s on the Mac). One request instead of up to 40.

Not settled here: whether today's labels are right once sizes are. The maintainer reports fits as "mostly wrong"; likely causes
in the rules themselves (a 20 tokens-per-second bar for "Runs great", one phone's speed constant, MoE speed) are checked
against named examples during the build, and any change to the thresholds is its own finding and decision.

## SDK changes (`sdk/llamabro-hub`)

Facts only, no labels:

- `SearchRepo.task: String?` (the Hub's `pipeline_tag`), `SearchRepo.tags: List<String>` (as returned) and
  `SearchRepo.lastModified`.
- `SearchRepo.gguf: GgufSummary?` with `parameters: Long`, `architecture: String`, `contextLength: Long?` and
  `hasChatTemplate: Boolean` (the template itself is not kept); null when the Hub has no GGUF metadata for the repo.
- The search request sends `expand[]=` for each of those fields, since `expand` replaces the defaults.
- `LlamaBroHub.modelCard(repo): String?`: the repo's `README.md` at its main revision, front matter removed; null when
  absent. Runs on the hub's transport and token like `files`.
- `LlamaBroHub.baseModel(repo): String?`: the original the repo was quantized from (its `base_model:quantized:` tag);
  null when it has none. A card opened from the phone or a download, whose row names no original, asks it once (D116).
- `apiDump` and the `.api` diff; `FakeLlamaBroHub` grows matching fields.

## Demo changes

- New merged screen replacing `GetModelsScreen` and `LibraryScreen` (their previews, view models and tests move with
  them); `Routes.MODELS` takes the optional query that `GET_MODELS` took, and `GET_MODELS` goes.
- A thin indeterminate bar in the design system (a `ThinBar` variant, with a preview), in a fixed slot under the
  search field.
- `ModelSheetViewModel` reads the repo's state from `ModelRepository`; the sheet branches on it as above.
- `SearchRows.of` maps task and tags to capabilities; `ModelRowItem` shows name, fit symbol, maker, capability icons,
  count and the badge. The catalog's `line` and `about` and `SearchRows.plainLine` go.
- The sheet becomes a full-screen-capable sheet with the model's page (`modelCard`) rendered by the chat's markdown
  renderer, extended where the page needs it (images, flattened HTML, sideways-scrolling tables).
- Fit symbols and the two new capability icons (`code`, `select_all`) join `DemoIcons` as paths (Material Symbols,
  Apache 2.0, and the drawn kneeling figure), with a fit-symbol component that carries its words as content description
  and long-press tooltip.
- A row's fit is computed from `SearchRepo.gguf` (above); `ModelDiscovery.quickFit`'s tree call and the
  `fitSlots` semaphore go.
- `demo-app.md` §4.3 rewritten.
- Built from `:demo:designsystem` components and tokens (D113); new pieces (badge, label chip) go into the design system
  with previews.

## Alongside: the sidebar with no chats

In the Sidebar layout, a phone with no chats shows an empty drawer above its actions, with no hint. It gets the same
line the Chat list layout already shows, "No chats yet. Start one.", in the drawer's list area (an `EmptyLine`), with
the New chat action below as today. Not part of the Models screen, but small and found in the same review.

## Left to the builder

- Exact grouping key between catalog suggestions, search results and installed models (repo, lower-cased, as `onPhone`
  does today, is the default).
- How "Chat with" opens a chat on a given model (reuse what the model switcher does).
- How far the chat's markdown renderer must grow for real model pages (try five popular ones; anything it can't draw
  falls back to its text).
- Journey and unit test updates for the removed screen and new states.

## Design calls to confirm

- Search sits above storage and the groups; the sketch shows it that way.
- Suggestions already taken are hidden, not shown with a badge.
- "Get another size" keeps the card open rather than a separate screen.
- Can't-chat models stay in results, marked, rather than filtered out.
- The card shows the original model's page, not the GGUF upload's.
- Catalog models lose their own lines too; their page replaces them.
- Fit has no words anywhere on screen; long-press and the screen reader give them.
- The doc lives in `docs/design/` beside demo-app.md, not in a separate specs folder.
