# Models audit (2026-11-20)

A critic's audit of the Models screen, the model card (sheet), the model rows, first run and its pick sheet, taken on
the phone after the merged Models screen was built (D116). Every state was drawn in light and dark at font scale 1.0
and 2.0, and four transitions were recorded frame by frame (16 ms) in the order the view models emit. Three
independent reviews were merged here and de-duplicated; what the capture itself got wrong is listed at the end.

Status: answered by the [Models page](models-page.md) (D130), built 2026-11-21. Each finding is marked fixed or ruled
out, with the commit or decision, in [Where each finding stands](#where-each-finding-stands); the measured
jitter, before and after, is F177. The final build's frame-by-frame recapture on the phone is the maintainer's.

Severity: **P0** blocks or misleads, **P1** major, **P2** minor, **P3** polish.

## Systemic causes

These eight causes produce most of the findings below; fixing a cause fixes its findings.

1. **No single model-row contract.** ModelCard, LibraryRow, DownloadRow and the first-run pick card each choose their own
   lines, type sizes and trailing slot, and a slot's meaning changes by place (trailing is size, downloads or nothing;
   the badge is a word, a percent or absent).
2. **Screens render each flow as it arrives.** The sheet is built from five independent flows (repository state,
   listing, maker, page, fits) and re-expands on every height change; the Models screen adds storage after the library.
   Nothing is cached, so every open refetches and every fetch moves the layout.
3. **Labels derived from partial data.** The download heading falls back to the quant until the listing arrives; its
   size is the file in flight, not the download; the maker arrives late and differs by entry point.
4. **State changes are hard swaps.** No crossfades, no item animation, heights snap, the sheet closes without its
   slide.
5. **One treatment, several meanings.** Accent ink fills the storage bar, the search bar, the download bar, the Default
   chip and the badges; sand (`slowInk`) means both "slow" and "failed"; "On this phone" means both "fits" and
   "stored"; underline means both "link" and "action"; "Downloading" heads paused, queued and failed.
6. **No hierarchy budget.** Too much sits at 16 sp Medium (name, state line, tier titles, page body); chips and bars out-
   weigh names; the page's H1 equals the sheet title; error lines use the serif hero voice, larger than the screen
   title.
7. **Built for font scale 1 only.** Fixed-dp symbols, single-line text without ellipsis, trailing parts that never
   shrink, heading rows where the action wins: at 2x the name is what gets cut.
8. **Search replaces instead of narrowing.** The groups stay up, unrelated, under a typed query, then vanish in one
   frame; failed, empty and offline searches drop the person's own models.

## Models screen

| # | Sev | Finding | Fix |
|---|-----|---------|-----|
| M1 | P1 | Two bars under the field while searching: the loading sweep and, 20 dp below, the storage bar, same colour and track. | Storage leaves the top: it goes into the "On this phone" section header. The only bar under the field is the search's. |
| M2 | P1 | The groups stay on screen, unfiltered, ~1 s under a typed query, then the whole body is replaced in one frame by results in another layout. | Focus enters search mode at once; typing filters local models instantly; Hub results append below; the body crossfades and rows animate. |
| M3 | P1 | Storage arrives after the library on every open: every row drops ~20 dp. Before that, ~400 ms of empty screen. | Storage is part of the same state; first frame comes from local data; a still skeleton if anything is pending. |
| M4 | P1 | Same model, three anatomies: library row (raw repo name, size, "Used today", no maker or fit), suggestion row (maker, icons, no size), result row (icons, "↓ 1.2M"); secondary text 14 sp in one, 12 sp in the other; heights 62 vs 72-80 dp. | One row everywhere (see Rows). |
| M5 | P1 | Suggestions and results never show the size; "↓ 1.2M" reads as a 1.2 MB download. | Size on every row; popularity in words, or only in the card. |
| M6 | P1 | Error, no-results and "No models yet" use the serif hero (28 sp), larger than the title, centred in empty space; at 2x three lines. | Plain one-line notices, top-aligned under the field, the action inline. |
| M7 | P1 | A failed, empty or offline search hides the person's own models; offline is exactly when they need them. | Local matches stay in every outcome; the network's answer is one line above them. |
| M8 | P1 | At 2x the library row cuts "Gemma 3 4B" to "Ge…" while "Used today" and the Default chip keep full width; "2.5 GB" wraps. | The name wins; trailing parts move to a second line at large scales. |
| M9 | P2 | Offline lists suggestions that don't match the query, under two stacked muted headers saying the same thing. | Filter by the query; one label. |
| M10 | P2 | Three left edges: storage text and the offline line at 16 dp, section labels and row text at 32 dp, the search icon at ~28 dp. | One inset for everything inside the gutter. |
| M11 | P2 | The search bar touches the first result card and reads as that row's progress. | The bar belongs to the field (its bottom edge). |
| M12 | P2 | Accent means five things; the heaviest marks on screen are the Default pill and the download bar, not names. | Accent only for an active download and the primary action. |
| M13 | P2 | Download row: the bordered Pause pill outweighs the name; Pause (text pill) and ✕ (bare icon) are two grammars. | One quiet icon pair; the name is the row's one primary. |
| M14 | P2 | Rows with no maker or capabilities strand "↓ 412k" alone on a line; card heights go ragged. | Fixed lines; the line is never empty (size fills it). |
| M15 | P2 | Download detail clips at 2x with no ellipsis ("310 MB of 740 MB" loses the speed). | Stack at large scales; drop the speed first; ellipsis. |
| M16 | P2 | "SUGGESTED FOR THIS PHO…" at 2x. | Shorter label, or two lines. |
| M17 | P2 | Starting a download inserts a "Downloading" section at the top; the row teleports and everything drops ~70 dp. | The row stays where it was, with its progress; items animate. |
| M18 | P2 | Clearing the query snaps back to the groups and re-adds storage in one frame. | Crossfade. |
| M19 | P2 | "No models match. Try a shorter name." is wrong advice for a real name ("gemma3"). | "Nothing on Hugging Face matches …", with local matches below. |
| M20 | P2 | The problem banner (a cancel that failed) looks like a model card, says "Try again" with no action, and isn't on the row it concerns. | Say it on the affected row, with its action. |
| M21 | P2 | The remove dialog: "Remove" is a filled primary pill, "Keep" plain text; the destructive choice looks like the safe default. | Remove in danger colour; the two in one button family. |
| M22 | P2 | Capability glyphs are 16 dp at ~60% ink; the embeddings glyph is faint and unlearnable. | 3:1 contrast; a clearer glyph. |
| M23 | P3 | The can't-chat row mutes everything, so the reason isn't said. | Name in ink; the no-entry symbol carries it. |
| M24 | P3 | "Not used yet" takes ~90 dp of every library row and truncates the name. | Recency on the secondary line, or only in the card. |
| M25 | P3 | Display names keep upload tokens ("it qat q4 0 unquantized", "FP8"); makers' casing differs ("Google" vs "google"). | Strip quant and format tokens; one maker map. |
| M26 | P3 | An empty library still shows "0 B used" and an empty bar on the screen a newcomer sees first. | No storage with no models. |
| M27 | P1 | Search relevance: "abliterated" lists Qwen3.8 27B first: grouped originals outrank the uploads that matched. | Rank by the matching uploads, not the group's total. |

## Rows

| # | Sev | Finding | Fix |
|---|-----|---------|-----|
| R1 | P1 | Card height changes with every download state (270 / 312 / 270 / 347 px) in one frame; pausing removes the bar and the progress just seen. | The footer line is reserved in every download state: queued an empty track, paused a frozen bar, failed the bar plus the reason; heights animate. |
| R2 | P1 | One channel per kind of information is broken: "42%" badge and the bar say the same; paused/queued say it in the badge, failed only in the footer. | Badge = state word only; footer = quantity. |
| R3 | P1 | The badge reads as part of the name ("Qwen3.5 0.8B On phone"): same colour as ink, near the title's weight; at 2x it takes the name's width. | A distinct form (tonal pill or muted label) on the second line; the name never truncates first. |
| R4 | P1 | Failure text in sand, the "slow" colour; the code's own token note says sand is a fit, not a warning. | Failures use `danger`; remove `onErrorContainer = slowInk`. |
| R5 | P1 | The download row calls a failure "The download paused. It'll pick up where it left off." with "Resume", resets the bar to 0, while the model row says "The connection dropped. Try again." | One failure sentence everywhere, in the failure colour; the bar keeps the bytes done; "Try again". |
| R6 | P1 | "Waiting to start" with a Pause button in the download row, "Queued" in the model row. | One word ("Waiting") and only Cancel until bytes flow. |
| R7 | P1 | Fit symbol (20 dp) and capability icons (16 dp) don't scale with the font: at 2x the most important signal is a speck. | Size from sp, capped. |
| R8 | P2 | "Fit not known yet" is just a missing symbol; it pops in later and shifts the line. | Reserve the symbol box. |
| R9 | P2 | Too-big ink is the dimmest mark on the card (below muted in both themes); a results page of 27B models is a wall of grey kneeling figures. | At least muted contrast; results put what runs here first. |
| R10 | P2 | Can't-chat and too-big share one grey; the embeddings icon repeats the no-entry's meaning. | Drop the embeddings icon on can't-chat rows. |
| R11 | P2 | The download row's button touches the card's top at 2x; the bar sits under it with no gap; the title truncates first. | Padding; actions on their own line at large scales. |
| R12 | P3 | Card edges nearly invisible in both themes (#FFF on #FAFAFA, #141414 on #0A0A0A); the search field disappears in light. | A hairline or one step more contrast. |
| R13 | P3 | Capability icons sit optically indented; the bulb reads as "tip" more than "thinks". | Optical alignment; reconsider the glyph. |
| R14 | P3 | A failed row says "Try again" with no control on the row. | A retry action on the footer line. |
| R15 | P3 | The progress bar is the heaviest stroke on the card in light. | Calmer fill. |

## First run and its pick sheet

| # | Sev | Finding | Fix |
|---|-----|---------|-----|
| F1 | P0 | "Picked for your phone" can list a model that is too big for the phone (the three picks are fixed; on a small phone the 4B kneels). | Picks are only models that run great or well; fewer when fewer fit; one plain line when none does. |
| F2 | P0 | The most prominent control is a search field that reacts to nothing while typing and then leaves for another screen with another layout; "Hugging Face" means nothing to a newcomer. | One Models page; first run is its empty state, search in place. |
| F3 | P1 | A pick card puts the size where a result row puts the download count, in the same style. | One row (see Rows). |
| F4 | P1 | "PICKED F…" at 2x: the help link wins over the heading. | The heading wins; the link wraps or moves. |
| F5 | P1 | The pick sheet's fit line is a grey box like the four fact cards but unlabeled, and truncates at 2x. | One card for every model (the model sheet). |
| F6 | P2 | Orb, greeting and a three-line paragraph take ~45% of the screen; at 2x the third pick is below the fold; nonlinear scaling flattens the greeting into the body. | One short intro line; content above the middle. |
| F7 | P2 | Nothing says which to start with: three cards of equal weight. | The best fit first and marked, or a short purpose per pick. |
| F8 | P2 | Before the phone's facts are read the section is empty under its heading. | Placeholder rows at final size. |
| F9 | P2 | Pick sheet: maker and about merge (same colour and size, one block gap); the name is serif here and sans on cards; at 2x "From Hugging Face" touches the nav bar. | Gone with the pick sheet; the model sheet's header rules apply. |
| F10 | P2 | Fact cards repeat ("Memory 0.7 GB", "Download 0.7 GB"), "Thinking: Can think first, or not" is jargon, values are the smallest text. | Gone with the pick sheet. |
| F11 | P2 | "What's a model?" doesn't explain the fit symbols, the newcomer's real question on that screen. | It teaches the five symbols. |

## Model sheet

| # | Sev | Finding | Fix |
|---|-----|---------|-----|
| S1 | P0 | "Downloading Q4_K_M · 0.7 GB" becomes "Downloading Recommended · 0.7 GB" when the listing lands; 0.7 GB is the file in flight (the image reader), not the 2.7 GB the tier shows elsewhere; the ETA covers one file. | The tier and the total are recorded when Get is pressed; heading and progress cover the whole download from the first frame. |
| S2 | P0 | Advanced: "Recommended" and "On this phone" chips are clipped and run into the size column. | The chip leaves the name column. |
| S3 | P1 | The sheet re-expands up to five times while opening (repository, maker, listing, page, download landing); ~0.9 s shows only the header and a loading bar. | One coherent first frame from cached data; fixed-size state slot; the page arrives below without re-expanding. |
| S4 | P1 | Two loaders: the page's indeterminate bar sits under the download bar, identical; between "Best quality" and Get it looks like a download starting. | No anonymous bar for the page: reserved text lines or nothing. |
| S5 | P1 | The page's H1 equals or outweighs the sheet title (22 sp, three lines on the phone) and often repeats the name. | Page headings stepped down two levels; a leading H1 that repeats the name dropped. |
| S6 | P1 | No boundary between the app's facts and the author's page; "decensored", "Click here" read as the app's own words. | An overline ("From the model's page on Hugging Face") and space above it. |
| S7 | P1 | Paused, queued and failed are all headed "Downloading …"; the real state is a 12 sp word on the right; the failure copy says it will resume by itself above "Try again". | The heading is the state: "Paused · Recommended · 0.3 of 3.4 GB"; the bar stays, frozen, in every state. |
| S8 | P1 | "On this phone" is both the Advanced fit column ("how it runs here") and the stored state; the header truncates to "ON THIS". | The fit column is "Runs here". |
| S9 | P1 | The sheet vanishes instantly when an action closes it (no slide down). | Hide, then dismiss. |
| S10 | P1 | Get names a time in Basic and a size in Advanced; with Smaller selected it still reads "Get Qwen3.5 4B · about 8 min" (Recommended's), and on a model already here it reads like a re-download. | One formula naming the choice: "Get Smaller · 2.6 GB · about 6 min". |
| S11 | P1 | A model that can't chat still offers a live, selectable size choice; the reason is last, muted. | No size choice; the reason leads, as the state line. |
| S12 | P1 | The byline wraps inside the repo path and joins two clauses with "·" ("…heretic-G / GUF · by xtools-at"). | Maker on one line, the repo on its own line, never broken mid-path. |
| S13 | P1 | The maker differs by entry point ("by Alibaba Qwen" from a suggestion, "by Qwen" from a download) and arrives ~1.7 s late. | One maker source, known on the first frame. |
| S14 | P1 | Too much at one weight: the state line, tier titles, the switch labels, page H3s and body all 16 sp; solid accent chips are the loudest marks. | A three-step scale for the sheet; tonal chips. |
| S15 | P1 | Advanced's list is cut mid-row inside the compact sheet and the image reader is hidden. | Quants in the sheet's one scroll. |
| S16 | P2 | At 2x the tier title truncates for the chip ("Recomm…"). | The chip moves under the subtitle. |
| S17 | P2 | Buttons: bordered Cancel 40 dp beside a taller primary; secondary actions are underlined text like the page's hyperlinks; a long "Chat with …" wraps to two lines while quiet buttons clip silently. | One button height; secondary as tonal/bordered; "Start a chat". |
| S18 | P2 | When a download lands, Cancel/Pause become a full-width "Chat with" at another height under the thumb. | The action row keeps its position and height; crossfade in place. |
| S19 | P2 | Cancel weighs the same as Pause and discards the progress without asking. | Pause leads; Cancel quiet in danger ink, confirmed past a few hundred MB. |
| S20 | P2 | Failure states repeat centred "Try again" buttons (sizes and page); "Looking at the sizes…" is centred while all else is left. | One left-aligned notice in the state slot; one retry. |
| S21 | P2 | The selection is only an outline; with Recommended on the phone, Smaller is selected unasked and nothing says so. | A radio mark; one line saying why. |
| S22 | P2 | Basic's fit symbols are small and low contrast in dark for slow and too big. | Contrast to 3:1; size from sp. |
| S23 | P2 | The segmented switch's selected segment is hard to see (white on near-white, #2A on #10). | A stronger selected fill. |
| S24 | P2 | In dark the sheet's edge barely separates from the scrim. | One step lighter surface or a hairline. |
| S25 | P2 | No separation between the scrolling page and the pinned action; a table row is cut right above the button. | A hairline or fade when content runs under it. |
| S26 | P2 | Markdown tables stop at ~85% width with a ragged edge; at 2x the third column is a sliver. | Full width, or horizontal scroll with an edge fade. |
| S27 | P2 | A "Drag handle" tooltip and a grey 48 dp box show on the handle when the sheet opens without touch (keyboard, D-pad, Chromebook, the capture). | No tooltip; the handle focusable only by keyboard; the system's focus ring. |
| S28 | P3 | Units mix ("310 MB of 740 MB · 9.8 MB/s" under a heading in GB); at 2x the line ends on "·". | One unit; parts that drop whole. |
| S29 | P3 | Queued shows a full-width Cancel where other download states show two half-width buttons. | The same two slots. |
| S30 | P3 | The open animation rises and grows at once. | Height settled before the slide. |

## Where each finding stands

Checked 2026-11-21 against the branch's code and the acceptance rounds (F177). Commits are on the models-page
branch.

### Models screen

| # | Status | How |
|---|---|---|
| M1 | Fixed | Storage is the On this phone section's note; the one loading line is the field's own edge (D117) |
| M2 | Fixed | Focus enters search at once; local rows filter as typed, the Hub's add below in one list (D119) |
| M3 | Fixed | Storage is in the browse state; the page paints from the library and the queue (D119) |
| M4 | Fixed | One `ModelRow` for every model, two lines in the subtle design (D123) |
| M5 | Fixed | Every row has its size; only a Hub row says its downloads, in words, quietest (D123) |
| M6 | Fixed | `Notice`: one muted line at the inner inset, its action inline (D117) |
| M7 | Fixed | Local matches stay over every Hub outcome (models-page §2) |
| M8 | Fixed | The name wraps and never yields (D117); a row's marks are on line 2 (D123) |
| M9 | Fixed | Search's local groups take the query; one label each |
| M10 | Fixed | One inner inset for every line inside the gutter (D117) |
| M11 | Fixed | The loading line is the field's bottom edge (D117) |
| M12 | Fixed | The accent is for the primary action and a download's ring (D117), now one monochrome tone (D125) |
| M13 | Fixed | The ring panel: the control inside the progress ring, a quiet ✕ |
| M14 | Fixed | Line 2 is never empty: "maker · size", else the repo's owner (acceptance P2-2) |
| M15 | Fixed | The footer's lines keep two lines each and grow at large font (models-page §3) |
| M16 | Fixed | Sentence-case labels that wrap, the action under them at large font (D117, D123) |
| M17 | Fixed differently | The row moves to Downloading as one animated item (models-page §7, D120), not in place |
| M18 | Fixed | Mode changes fade through and lists move exit, place, enter (D119, D120) |
| M19 | Fixed in part | The line sits under "From Hugging Face" with local matches above, and a phone too small for any model says so (D128); the words "Try a shorter name." stay |
| M20 | Fixed differently | A failed remove or cancel is one plain line under the field, closed by its own OK (acceptance P2-5): the row it concerned may be gone |
| M21 | Fixed | Remove and Cancel download in danger, bordered; Keep a bordered quiet button |
| M22 | Fixed | Capability icons in the soft ink, 4.5:1 (D123); none on a can't-chat row, whose symbol says it |
| M23 | Fixed | The name is in the ink on every row |
| M24 | Fixed | No row says when a model was last used (D123) |
| M25 | Fixed | Names cleaned when read; one maker map (D120) |
| M26 | Fixed | No storage with no models |
| M27 | Ruled out | The Hub's order stands: a page arriving would reshuffle a ranked list; "Fits this phone" by default keeps 27B uploads out instead (D127, F176) |

### Rows

| # | Status | How |
|---|---|---|
| R1 | Fixed | The footer is reserved in every download state; a footer that comes or goes folds with the row (D120) |
| R2 | Fixed | One signal per state: no mark while the footer says it (acceptance A6, D123) |
| R3 | Fixed | The state is a soft mark on line 2, never a pill (D123) |
| R4 | Fixed | Failures in `danger`; `onErrorContainer` is the danger ink (D117) |
| R5 | Fixed | One failure sentence, "Stopped" with the bytes kept and Try again (D118, D120) |
| R6 | Fixed | "Waiting": an empty ring around an hourglass, only ✕ |
| R7 | Fixed | The fit symbol and icons grow with the font, capped at 32 and 24 dp (D117) |
| R8 | Fixed | The fit symbol's box is reserved |
| R9 | Fixed | Too-big ink no dimmer than muted (D117); search fits the phone by default (D127, F176) |
| R10 | Fixed | No capability icons on a can't-chat row |
| R11 | Fixed | The ring panel replaced the button and bar; trailing parts move under the name at large font (D117) |
| R12 | Ruled out | Edges were added (D117), then the maintainer reverted them: cards are their fill alone (D122) |
| R13 | Fixed in part | The icons are light outline glyphs on one line (D123); the bulb stays, as it is the message bar's Think (D50) |
| R14 | Fixed | Try again is the ring's control |
| R15 | Fixed | The ring replaced the bar; its ink is the monochrome accent (D125) |

### First run and its pick sheet

| # | Status | How |
|---|---|---|
| F1 | Fixed | Suggested lists only models that run great or well, with one line when none does |
| F2 | Fixed | First run is the Models page's empty state, search in place (D130) |
| F3 | Fixed | One row |
| F4 | Fixed | Section labels wrap and put an action under them at large font (D117) |
| F5 | Fixed | The pick sheet is gone; every model opens the one card |
| F6 | Fixed | One intro line over the field; no orb or greeting |
| F7 | Fixed | Suggestions are listed best fit first (models-page §2) |
| F8 | Fixed differently | The page paints what it knows; the suggestions join under it when the phone's facts land (D119) |
| F9 | Fixed | Gone with the pick sheet |
| F10 | Fixed | Gone with the pick sheet |
| F11 | Fixed | "What's a model?" teaches the five symbols |

### Model sheet

| # | Status | How |
|---|---|---|
| S1 | Fixed | Get records the name, maker and total; labels name the quant (D118, D130) |
| S2 | Fixed | "Recommended" and "On phone" are words under the quant |
| S3 | Fixed | One view from local data (D118); the compact height from the first frame (D119); no drop on the phone (F177) |
| S4 | Fixed | No bar for the page |
| S5 | Fixed | The page's headings are soft labels; a leading heading that repeats the name is dropped (D118, D123) |
| S6 | Fixed differently | A quiet "About" over the page and its source at its end (D123), not an overline |
| S7 | Fixed | The ring panel's stage word heads every state; Stopped keeps its bytes (D118, D120) |
| S8 | Fixed | The fit column is "Runs here" |
| S9 | Fixed | Every closing action slides the sheet down first |
| S10 | Fixed | Get names the file in Basic and Advanced alike: "Get Q4_K_M · 3.4 GB · about 8 min" (D130) |
| S11 | Fixed | No choice; the reason is the state line, its one statement since acceptance round 5 |
| S12 | Fixed differently | The repo leaves the header for the page's source line (D123) |
| S13 | Fixed | One maker map (D120), read before the sheet shows (D118); a never-seen repo's maker can still come with the network, its line held |
| S14 | Fixed | The card's hierarchy in the subtle design (D123) |
| S15 | Fixed | Advanced's quants in the sheet's one scroll |
| S16 | Fixed | No chip in a tier's title |
| S17 | Fixed | One button height (D117); "Start a chat"; "Another size" bordered (D123) |
| S18 | Fixed | The foot is one height in every state, the kinds fading through (D118, D119) |
| S19 | Fixed | The ring's control leads; Cancel asks past 300 MB in danger; the ✕ stays quiet (the maintainer's call, models-page §4) |
| S20 | Fixed | One notice with one Try again in the choice's place (D118) |
| S21 | Fixed | A radio mark; a size on the phone says "On phone" under its quant and can't be picked, so another chosen says why |
| S22 | Fixed | Fit symbols sized from sp (D117) |
| S23 | Fixed | The chosen segment has the one selection outline (D117, D125) |
| S24 | Ruled out | The lighter dark surface was reverted by the maintainer (D122); the scrim follows the sheet's position (D119) |
| S25 | Fixed | A hairline over the foot while content runs under it (D124) |
| S26 | Fixed | Tables span the width and scroll sideways with a fading end |
| S27 | Fixed | The handle has no tooltip and takes focus only from a keyboard |
| S28 | Fixed | One progress string in the total's unit (D120) |
| S29 | Fixed | One ring panel for every stage |
| S30 | Fixed | The height is settled before the slide (D119); a ~1 dp spring overshoot, Material's, is parked |

## What the capture got wrong

- The pick sheets were all given one fit ("Runs well") by the capture, so the pick card and pick sheet disagree only in
  the pictures (finding F5's wording mismatch is not the app's).
- The not-here sheet transition reused a 4B listing and page under the 0.8B name; the advanced Get label stayed in its
  Basic form because the capture fed a fixed string.
- Dialogs were captured mid-animation, and one remove dialog leaked into the next state's picture.
- Light-theme status bar icons are white on white because the capture draws without the app shell.
