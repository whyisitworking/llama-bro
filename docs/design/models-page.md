# One Models page, offline first: design

Status: built, 2026-11-21; the decision is D130 in [../decisions.md](../decisions.md). Follows
[models-screen.md](models-screen.md) (D116) and answers the audit in [models-audit.md](models-audit.md) (findings
M1-M27, R1-R15, F1-F11, S1-S30, each marked there fixed or ruled out; the measured jitter is F177). It replaced first
run, folded into [demo-app.md](demo-app.md) §4.3, which is rewritten to match. Search's paging and filters are
[models-search.md](models-search.md).

Where the text below differs from what was built, these decisions hold, each a departure made while building:

- D122: the cards' original look is kept: no edges on cards or the search field (none on the switcher's cards either),
  the original surfaces, so §5's "one more step of contrast" on card and field edges and §7's hairline edge are void.
- D123: the ultra subtle rows and card (two-line rows with the fit centred, the card's serif name and quiet line, the
  page muted under "About" with its source at the end); §3-§5 already follow it.
- D124: no push off the fold: the page follows the size choice at one gap in every state, so §4's rule that the
  overline never sits alone above the foot is gone.
- D125: Geist type and one monochrome accent, a quiet grey; one selection outline (2 dp in the accent) for a chosen
  card, tier, quant or segment, so §4's 1.5 dp outline and §5's "the accent is the ink" are void.
- D126: the sidebar is a quiet list, its Models a labelled row at the foot.
- D127, D128: search is paged with Paging 3 over Room in the Hub's order and has filters, so the non-goal "no paging,
  filters or sorting" and §2's ranking (M27) are void.
- D129: the page's and card's state is derived from sources with operators, a mutable holder only for a source.

## Why

The merged Models screen fixed what was true but not how it reads. On the phone:

- **It jitters.** Every card open refetches the files, the page and the base model, and the card draws each as it
  lands: "Downloading Q4_K_M" becomes "Downloading Recommended", the maker appears, the page pushes in, and the sheet
  re-expands up to five times. Storage lands after the list and pushes it down. Search keeps the groups up under the
  query, stacks a loading bar on the storage bar, then swaps the whole body in one frame.
- **It overwhelms.** One model looks four ways (library row, suggestion, result, first-run pick). Name, state, options
  and page text all sit at one weight; chips and bars outweigh names; errors shout in the hero serif.
- **First run is a second, weaker models page**, with a search box that reacts to nothing and leaves for another screen,
  and picks that can include a model too big for the phone.

The maintainer's words: every model card should behave the same; layout should never jitter; things shouldn't load
repeatedly; no shortcuts and no half-baked work.

Success: a person opens Models or a card and sees the final layout in the first frame, from what the phone already
knows; fresh facts change text in place, never positions. Every model looks and behaves the same wherever it appears.
Each screen has one obvious primary element.

## Goals

1. **One Models page** is the only place for models, and where a fresh install starts (first run and its pick sheet
   go).
2. **Offline first.** Everything the Hub told us is kept in Room and shown at once; each screen or card refreshes
   quietly when it opens; a refresh changes text in place, never the layout.
3. **One model row** everywhere: suggestions, results, downloads, models on the phone, the chat's model switcher.
4. **One model card** for every model, with a fixed anatomy whose parts sit in the same place in every state.
5. **Search narrows in place.** Focusing the field enters search mode at once; typing filters local models instantly;
   Hugging Face results add below; one loading signal, owned by the field.
6. **A hierarchy budget and colour roles**, fixed in the shared design system, app-wide.
7. **Large text works**: at font scale 2.0 the model's name is never what gets cut.
8. **Designed transitions**: no hard swaps, no height snaps, sheets slide out.

## Non-goals

- No change to how downloads run (the queue, the worker, notifications) or to fit estimates and thresholds.
- No paging, filters or sorting controls in search.
- No new SDK API unless the cache needs a fact the SDK doesn't return (none is expected).

## Anti-goals

- Any frame that shows a value which a later frame contradicts (a quant then a tier, one size then another).
- Any layout shift after first paint caused by data arriving (only the person's own actions move things, and those
  animate).
- A refresh that blocks the screen, or a spinner where a cached value exists.
- Words replacing the fit symbols on rows (the maintainer chose symbols; they are taught, not replaced).
- The SDK deciding policy for the app (picks, filtering by fit, ranking are the app's).

## Approaches considered

- **Data: Room cache with stale-while-revalidate (chosen)** over an in-memory cache (lost on process death, so the
  first open of the day still jitters) and over a time-to-live cache (fewer requests, but a new upload can take a day to
  show; the maintainer chose refresh on open).
- **Card: render once from local data, fixed slots (chosen)** over skeletons for every slot (still five swaps, only
  quieter) and over holding the sheet until the network answers (slow on a bad connection).
- **First run: the Models page's empty state (chosen)** over keeping a separate greeting page with live search (still
  two pages for one job) and over dropping search from first run (hides what experts came for).
- **Fit on rows: symbols, taught (maintainer's choice)** over symbols with words (all three critics' preference).
- **Design-system fixes app-wide (maintainer's choice)** over forked components for the models screens.

## The design

### 1. Data: offline first

A new **`HubRepository`** in the demo (`demo/app/.../models/`) owns every fact the Hub gives, backed by Room tables in
the app's database (a migration adds them):

| Table | Key | Holds |
|---|---|---|
| `hub_search` | normalised query | the merged result rows (as `SearchModel` JSON), when fetched |
| `hub_repo` | repo id | the repo's facts as a search row knows them (maker, capabilities, parameters, downloads, base model, can chat), when fetched |
| `hub_files` | repo id | the listing (`RepoFile`s), when fetched |
| `hub_page` | repo id | the page markdown (null when none), when fetched |
| `recent_search` | query (trimmed, lower case; the text as last typed beside it) | when last searched; the last eight kept |

- Each read is a `Flow<Cached<T>>`: the stored value at once (or none), `refreshing` while a fetch runs, and the fetch's
  failure if it failed while a value is shown (the value stays). Concurrent refreshes of one key share one request.
- **Refresh on open**: a screen or card that opens asks for a refresh of what it shows; nothing refreshes on a timer.
- **Suggestions** are rows like search results: their `hub_repo` facts (downloads, parameters, maker) come from one
  search per catalog family, cached; until first fetched, the catalog's own facts fill the same slots, so the row's
  shape never changes.
- **A download records what was chosen.** Get stores the display name, the maker, the total bytes (model and image
  reader) and the model file's bytes (the fit's speed, D121; database version 6) with the pending download (a Room
  table `download_record`, replacing the bare keys in settings); the quant is the key's own tag. The card's heading and the progress read these, so they are final from the first frame (S1).
- **Labels name the quant, never a tier.** "Q4_K_M" (or the upload's own variant name, such as "UD-Q4_K_XL") wherever a
  label says which file is downloading, on the phone or about to be got; the words Smaller, Recommended and Best
  quality appear only as the Basic choice's titles. *(The maintainer's call.)*
- **Progress covers the whole download** (model plus image reader): the repository sums the hub's per-file progress
  against the recorded total.
- `ModelRepository` keeps the library and the queue; `HubRepository` is the only path to `ModelDiscovery` for screens.

### 2. The Models page

One route, `models`, is the start destination on a fresh install (section 7); otherwise the app starts as today. The
page has two modes on one surface.

**Browse** (field not focused):

1. Top bar "Models" (Back when something is under it).
2. On a fresh install only: one muted line, "Pick a model to talk to. It runs on this phone, private and offline.", and
   "What's a model?".
3. The search field.
4. **Downloading** (when any), **On this phone** (when any), **Suggested for this phone**. Storage is the "On this phone"
   section's trailing note ("6.1 GB · 120 GB free"), not a bar under the field; no storage with no models.
5. Suggested lists only catalog models that run great or well here, best first; when none does, one line says the phone
   is short on memory and names the smallest model.

**Search** (field focused, the moment it is tapped, before any typing):

- The intro folds away (animated, back with Back); the body shows **recent searches** as one-tap chips, then browse's
  own groups (Downloading, On this phone, Suggested for this phone) under the same labels, so a row in both modes stays
  put as one item; only what enters or leaves animates (one list with stable keys across both modes, D119). In search
  the suggestions group lists every catalog model not on the phone whose name has the query, whatever its fit here,
  with the catalog's sizes and its fit symbol: a person who searches a model by name finds it, offline too.
  Browse's Suggested keeps the runs-great-or-well rule. The suggestions are listed in browse's order (best first; within
  a fit, the catalog's), the ones browse leaves out after them, so focusing the empty field moves no row: it lists
  everything browse lists, where browse lists it (models acceptance B1). The field
  reads "Search models": it filters your own models first, and the "From Hugging Face" label says where the extra rows
  come from.
- Typing filters your models and suggestions by name at once (local, no debounce). After 2 characters and the
  debounce, Hugging Face is asked; its rows add under a "From Hugging Face" label, the cached answer for that query first
  if there is one.
- One loading signal: a thin indeterminate line in the field's bottom edge while a fetch runs. Nothing under the field
  moves when it starts or stops.
- No results, failure and offline are one plain line under the "From Hugging Face" label, with the action inline; your
  matching models stay above it.
- Back or clearing and leaving the field returns to Browse (animated), at the list's top: a mode change never carries
  the other mode's scroll (B3). The field shows its focus with one more step of contrast on its edge, and a quiet ✕
  clears it whenever it holds text.
- A remove or a cancel that didn't happen is one plain line under the field, its failure word in danger, closed by its
  own OK; never a row's card in the list (P2-5).
- Ranking: Hub rows whose own name contains the query come before rows that matched only through a grouped upload
  (M27); within each, most downloaded first.

**First Get on a fresh install** (no chats yet): the download starts and a new chat opens on that model, showing the
download and letting the person type when it lands (today's first-run behaviour). Every later Get stays on the page.

### 3. One model row

One component, `ModelRow` in the design system, replaces `ModelCard`, `LibraryRow`, `DownloadRow` and the pick card.
Two lines and the fit symbol, typed slots, one meaning per slot (the maintainer's "ultra subtle" design, D123: "the less text there is, the better"):

| Line | Content |
|---|---|
| 1 | the name (`titleRegular`, 16 sp Regular, ink, never the first thing cut) |
| 2 | one muted line: "maker · size" (bodySmall, muted), then the capability icons (light outline weight, soft, no words; none for a model that can't chat, whose symbol says so), then, quietest, a Hugging Face result's downloads ("· 1.2M downloads") and the state's small mark ("· Default" for a model on the phone, "· Current" in the switcher, "· On phone" for a row not under "On this phone" whose repo has a file here; soft bodySmall, never a pill; none while the download footer is there, which says the state itself) |
| right | the fit symbol (reserved box, filled when known), centred on both lines, so the row is balanced; the switcher's info action sits just before it |
| footer | the download panel at row size, reserved in every download state: the ring around Pause / Resume / an hourglass / Try again, "0.9 of 3.4 GB · 4 min" or the reason, and a quiet ✕ (the card's pinned panel, smaller) |

No row says when a model was last used, and only a Hugging Face result says its downloads. Rows are a little roomier
than a card (an 18 dp inset, 16 dp corners, 12 dp apart).

- Line 2's size is the suggested file's (or the file on the phone, or the one downloading), with its image reader; a
  Hub row's is guessed whenever its parameter count is known (the GGUF header's, else the one its name carries, "7B").
  With no maker and no size known, line 2 says the repo's owner, so it is never empty (P2-2).
- Line 2's fact and mark fade through when they change (a landing's footer folds away and "Default" fades in).
- A model on the phone swipes to remove; a download's row offers its controls in the footer's ring panel.
- Heights never change within a mode: a row in a download state has the footer from the first frame; changes between
  states fade through (the old words out, then the new in; text never crossfades, D119); a footer that comes or goes
  unfolds or folds with the row's height on the spec the list moves its neighbours with.
- One failure word everywhere: "Stopped" (the footer's and the card's panel), never "Failed".
- The name is in the ink on every row, also for a model that can't chat.
- At large font scales the fit symbol moves under the name and the icons and marks take a line under "maker ·
  size", which may take two lines; symbols and icons are sized in sp, capped. At font
  scale 1.3 and up the footer's two lines keep two lines each and grow past them rather than cut ("Downloading ·
  Q4_K_M", the bytes, the reason), so the footer is one height within the mode.
- Names are cleaned (upload tokens such as `q4_0`, `FP8`, `qat`, `GGUF` stripped; the catalog's name when known);
  makers come from one map (catalog maker, else the original's owner, with a known-organisation casing table).

### 4. One model card

Every model opens the same sheet (first run's pick sheet is removed). Fixed anatomy, top to bottom; each part sits in
the same place in every state, and its height is reserved from the first frame:

1. **Header** (D123): a top row (D156): Back (a small icon) on a card a page's link opened, nothing there on the
   root, and at the row's end "Open on Hugging Face" (a small open-in-new icon) on every card, opening the card's repo
   in the browser; then the name (`cardTitle`, the serif at 24 sp Regular), and one quiet line under it: the maker
   (bodyMedium, muted), the capabilities as soft light icons with no words (a screen reader hears them), and the fit
   symbol in its colour (its words in a long-press and to a screen reader). The line's height is held from the first
   frame. The repo is not in the header: it is the page's source, at its end (4).
2. **State** (one line, bodyMedium, muted, after a clear gap): "Not on this phone", "On this phone · Q4_K_M · 3.4
   GB", "Not for chat: this is an embedding model". While a download runs, its state is said in the pinned download
   panel (5), not here; this line is dropped (a maintainer ruling in acceptance): the panel is the only
   place the download is described, and the line returns, folding in, when the model is on the phone or not here.
3. **Choice**: the size choice (Basic/Advanced, its unchosen segment Regular) when getting is possible; nothing for a
   model that can't chat. Basic tiers are titled Smaller, Recommended and Best quality (bodyMedium, Regular), with the
   quant under the title ("Q4_K_M · 3.4 GB", muted), the fit symbol centred on the right, and the selection with a
   radio mark; Advanced lists every quant in the sheet's one scroll (no nested list),
   its fit column titled "Runs here", "Recommended" and "On phone" as words under the quant, not chips in the name column;
   the quant is plain label text on its size's baseline (a pill is a state's), and the image reader is one flat row,
   "Image reader" with "Included", not a card like the choices. The selected row's outline is 1.5 dp, the radio
   carrying the selection. Basic and Advanced fade through while the part's height eases between them.
4. **Page**, after a larger gap (D123): a quiet "About" (`quietLabel`, soft, sentence case, no rule) on the gutter
   like every line of the card (one inset), then the page in page mode, muted and small (`pageBody`, 13/24 sp): every
   heading a soft `quietLabel`, Regular; links muted with their underline; a table's header a quiet label with no
   fill; and a leading heading that repeats the name dropped. Before it is drawn, what the page carries for GitHub
   rather than a reader is left out: note boxes ("> [!Note]" and its kin), paragraphs that are only links or badge
   images, and HTML blocks that are only links and images (logos, badge rows). Its source closes it: "Source:
   unsloth/Qwen3.5-4B-GGUF" (bodySmall, soft). Tables span the width and scroll sideways, fading at their end while
   they can. While the page is unknown, nothing (no bar). Chat replies keep their own scale. A page's relative links
   point into the repo the page is from. A tapped link to a model with GGUF uploads opens that model's card in the
   same sheet, "Opening <repo>…" saying so under the state line while the Hub is asked; any other link opens the
   browser (D156).
5. **Pinned foot**, outside the scroll, 64 dp in every state, so the page scrolls under it and nothing above it moves
   when a download starts or ends:
   - **Not here:** Get, naming the file: "Get Q4_K_M · 3.4 GB · about 8 min" (the same formula in Basic and Advanced).
   - **Downloading, the ring panel** (the maintainer's choice of the sketched A Strip, B Ring and C Seam): the progress is a
     ring drawn around the Pause icon, so the control and the amount are one object; then two lines, "Downloading ·
     Q4_K_M" (label) over "0.9 of 3.4 GB · 4 min left" (bodySmall, muted); then Cancel as a quieter ✕ icon at the end,
     set apart. Paused: the ring frozen in muted ink around a Resume icon, "Paused · Q4_K_M" over "0.9 of 3.4 GB".
     Waiting its turn: an empty ring around an hourglass, "Waiting · Q4_K_M" over "after Gemma 3 4B". Failed: the ring
     frozen at the bytes kept around a Try again icon, "Stopped" in the failure colour over the reason and the bytes kept,
     "The connection dropped · 0.9 of 3.4 GB kept". Every progress figure is one string: both numbers in
     the total's unit, "0.9 of 3.4 GB", or "35 of 740 MB" for a total under a gigabyte; the panel, the row's footer and
     the cancel dialog ("0.9 of 3.4 GB has arrived") say it alike, and the ring's arc moves in the same frame as its
     words, starting with the new stage's words, never under the old ones. Cancel asks
     first once more than 300 MB have arrived, whether or not the total is known ("420 MB has arrived" without one). Both icons are 48 dp targets with descriptions, offset so the ring's
     edge and the ✕'s glyph meet the gutters; the ring's progress is announced. The ✕ stays quiet (muted); the
     confirm that deletes ("Cancel download") is in danger.
   - **On this phone:** "Start a chat" (primary) and "Another size" (bordered by the hairline), one height, both
     labels Regular (D123). "Another size" unfolds the choice with one muted line, "Q4_K_M is already on this phone.", and a bordered Done that returns the
     foot to "Start a chat"; a size on the phone can't be picked in Basic or Advanced. It is one motion (D121): when
     the size Get would fetch isn't in view, the sheet grows to its full height with its scroll held while the choice
     unfolds, and only then, if that size is still out of view, the least scroll brings it there, in Basic and in
     Advanced.
   - **Couldn't get:** not a separate state: a failed download is the ring panel's Stopped.
   - A foot that changes kind (Get, the ring panel, "Start a chat") fades through.
   - **At large font** (scale 1.3 and up) the foot grows rather than cut: Get's time is a line of its own ("Get
     Q4_K_M · 3.4 GB" over "about 8 min"), the ring panel's two lines keep two lines each, the on-phone buttons stack;
     and in every state the foot is the height of its tallest kind, measured beside the one shown, so a download
     starting or landing moves nothing above it.
   - **Can't chat:** the state line says so; the foot holds its height with nothing in it, and the header has no
     "Embeddings, not chat" word: one statement.
   Buttons one height; secondary actions bordered, never underlined text.
   - The model row's download footer uses the same ring, text and icons at row size, so a download looks the same in
     the list and in the card.

- A size on the phone shows the file on the phone's own size (its model file and image reader), in the state line, in
  its Basic tier and in its Advanced row alike, and its fit from the same file (B5); it can't be picked, drawn in the
  unavailable look in Basic and Advanced alike. A size not yet known is a placeholder with the unselected edge.
- Offline (or before the listing) a catalog model's own file is its Recommended tier, shown and selected ("Q4_K_M · 3.4
  GB"); the other tiers say "Needs the network" once the listing fails, with one notice, "Other sizes need the
  network.", and Try again (card 1).
- The sheet opens at its final compact height from local data: the repository state, the recorded download, the cached
  listing and the cached maker are all local. When a listing has never been fetched, the choice part holds its final
  shape (three tier rows, values blank) until it lands. The pinned foot's state is local from the first frame.
- The overline never sits alone right above the pinned foot: at the compact height at least two of the page's lines
  show under it, or it starts below the first view.
- The ring panel's 48 dp targets stay inside the foot and the row (F130); the foot and the row's footer give the panel
  room past the gutter, so the ring's edge and the ✕'s glyph meet the gutter.
- The sheet opens at the compact height (`DemoSize.sheetCompactHeight`, or the screen's, if less) from its first
  frame, whatever its content (it takes no top inset, which Material added until it knew the sheet's offset, and its
  body measures nothing until the window has room for the compact height, so Material's show targets the compact top
  from its first frame, B4), so it rises straight to its top and the page lands in room already there; the page
  landing never moves it. Scrolled or pulled up, it grows to the status bar's edge over the enter duration, never in
  one frame; that is the only other height it takes. The scroll's foot fades while there is more below. The scrim's
  shade follows the sheet's position, so it leaves with the sheet, and a closing sheet keeps its foot.
- Every action that closes the sheet slides it down first.
- The cards a page's links open are a stack (D156): each slides the shown card down and rises in its place; Back, the
  top row's or the system's, does the same back to the card under it, and only the root's Back closes the sheet. A
  link to a model already in the stack returns to its card. A rotation keeps the stack and each card's state; closing
  the sheet clears them all.
- The drag handle shows no tooltip and takes focus only from a keyboard.

### 5. Visual system (shared, app-wide)

- **Hierarchy budget per surface**: one display or title element; one state line; everything else body or smaller.
  On the Models page and card (D123) Medium appears only where something is emphasised (the chosen segment); names,
  labels and buttons are Regular, the screen title Light. Model pages in page mode are muted, their headings soft.
- **Three inks for text**: ink, muted and soft (D123), the soft grey for the least important text (section labels,
  marks, a field's hint, the page's headings and source), each at 4.5:1 on the canvas and the surface in both
  themes. Section labels on the Models page are sentence case ("On this phone"), Regular, soft, with no tracking.
- **Colour roles**: accent only for the primary action and an active download's bar; status pills on accentSoft with
  accent text (never solid); failures in `danger` (and `onErrorContainer` stops being `slowInk`); fit inks only for fit;
  too-big ink raised to at least muted contrast; card and field edges get one more step of contrast against the canvas
  in both themes.
- **Notices**: errors, empty and no-results are one plain bodyMedium line where the content would be, never the hero
  serif, never centred in empty space; an action is a bordered quiet button of the button height at the line's end
  (under it at large font), never underlined text. A list's notice starts at the inner inset, a sheet's on the gutter.
- **Insets**: one inner inset for every line inside the gutter (labels, notes, row text).
- **Large font**: every row, section label and action row has a stacked layout at font scale ≥ 1.3; text that can be
  cut ellipsises; no `maxLines = 1` without overflow.
- **Buttons**: one height; the confirms that delete (Remove, Cancel download) in danger, bordered; their Keep a
  bordered quiet button; a dialog's buttons stack full width at large font, labels wrapping, never cut. The ✕ beside a
  download's ring stays quiet (muted). A dialog's title is a step over its line at every font scale (the screen title's size over bodyMedium), and its line
  scrolls with a fading foot so the buttons stay on screen.
- **Status pills**: accentSoft fill with accent labelSmall text. The accent is the ink (D69), so a pill
  reads as a neutral tone with ink text in both themes; that is the role, not a missing colour.
- **Motion** (D119): text never crossfades: words that change in place (a state, a pill, a foot, a label) fade
  through, out in 90 ms then in over 120 ms, and the value fading out is the last one shown; only icons and fills
  crossfade. Lists have one motion policy (D120): exit, then place, then enter. A leaving item fades out in 90 ms; the
  items that stay move only then, over 250 ms; an arriving one fades in once they are placed; so no moving row crosses
  a row leaving or arriving. Rows are drawn above labels; a row's size change and its neighbours' moves share the place
  step. A part that comes or goes (a footer, the intro, the state line, the choice) is in the same order: it fades out
  before it folds, and unfolds from its top before its content fades in, from halfway through the unfolding. Screens fade through too. Heights animate; all respect the
  animator scale (0 is instant).

These changes land in the shared components, so the chat, settings and voice screens pick them up; each of those
screens is re-checked in light, dark and font scale 2.0.

### 6. What's a model?

The dialog adds the five fit symbols with their words, so the symbols are taught where the newcomer asks.

### 7. Settled while writing (the builder check)

- **One size everywhere: what the download takes**, the model file plus its image reader when it has one (the catalog's
  `modelBytes + mmprojBytes`, or the listing's file plus `encoderBytes`; a file on the phone is its model file plus its
  image reader, the catalog's figure, else its cached listing's). Row line 2, the state line, the Get label and
  its time, the progress and `download_record`'s total all use it; the fit beside it is computed by one function,
  `Fits.quick` (this size for memory, the model file alone for speed), on the row, the switcher and the card alike
  (models acceptance P1-1). One file has one fit (D121): the speed is the model file's bytes times the share a token
  reads (`Fits.speedBytes`: a mixture-of-experts model's active parameters over all of them, from its name, else the
  Hub's counts), the same for a suggestion, a Hub row's guess, a download row (the record's model bytes), the file on
  the phone, the card's sizes and the switcher; the page keeps a fit per file, never per model. Settings' default
  model list and the model in memory say the same one size. Makers come from one map keyed by the Hub owner, spelled as the catalog spells them, the
  catalog's maker winning for its repos (P2-3). A stored Hub row keeps the Hub's own name; the shown name is cleaned
  when read, so a refresh never relabels a row (B2). So the 4B reads 3.4 GB in every place, never 2.7. *(Answered by the anti-goal.)*
- **A search row's size before its files are known is the guess** from the parameter count and quantisation that
  already gives its fit (F175, the maintainer's "no extra call beyond the list"), written "about 3.4 GB". When the card has
  read the repo's files (cached), the row shows the exact size without "about": the same slot, a more precise value.
  No call per row. *(Answered by an earlier decision; the "about" marking is a design choice.)*
- **Fresh install** keeps today's rule: Models is the start screen while first run hasn't been seen and the library is
  empty (`seenFirstRun`, kept). The intro line shows while `seenFirstRun` is false. The first Get while it is false opens
  the waiting chat and marks it seen; searching no longer marks it seen. With models but no chats, the app starts as it
  does today. *(My call: the doc's "no chats" was loose.)*
- **A row moves with its state, animated.** Browse keeps Downloading, On this phone and Suggested; rows are keyed by
  repo, so Get moves the row from Suggested to Downloading as one animated item move, and a landing moves it to On this
  phone. In search mode a Hugging Face row for a model already listed under your models or the suggestions is left out
  (the local row carries its state). *(My call; the person's own action moves things, and it animates.)*
- **The chat's model switcher uses `ModelRow`** with "Current" as its state word and the fit symbol in place of the fit
  words; tapping a row still switches the chat's model, long-press still sets "Use for new chats"; an info action on the
  row opens the model's card. *(My call: same row, the switcher keeps its job.)* The switcher's sheet is on the page's
  ground, so its cards are the page's (surface on canvas, hairline edge, both themes), and the info action sits on
  line 2 under the fit symbol, its 48 dp target over the lines, so the row's right edges are the page's.
- **A Basic tier the upload lacks stays as a disabled row**, "Not in this upload", so the choice block keeps its height
  when the listing lands. *(My call.)*

## Constraints

- Project rules (CLAUDE.md): design-system tokens only (D113 lint); new D and F entries at the end; `apiDump` for any
  SDK API change; no inference on an emulator; device runs short and attended; nothing private committed.
- Room migration from the current schema, with a migration test.
- Every audit finding is resolved or ruled out in writing; the audit capture is rerun on the phone as acceptance.

## Left to the builder

- The exact Room schema and JSON shapes; how `Cached<T>` is spelled.
- The organisation-casing table's contents; the upload-token list for name cleaning.
- Animation specs, within the theme's motion tokens.
- Test design, including a layout-stability test (a row's and the sheet's bounds don't change across data emissions).
