# The demo app: design reference

This is the consolidated design of the demo chat app in [`demo/`](../../demo/README.md): who it is for, what it does, how it looks and the rules that keep it consistent. Decisions are cited as D<n> ([decisions](../decisions.md)) and findings as F<n> ([findings](../research/findings.md)). The static mockups in [mockups/](mockups/README.md) predate the final design system; where they differ from this page, this page is the reference. How the app is verified is in [../testing.md](../testing.md).

## 1. Purpose and users

The app is an everyday on-device chat app that people reach for without thinking, the way they reach for a hosted assistant, and that never gets in the way. It is also the SDK's largest consumer, so its tests double as integration tests of the public API, but it is built as a product and not as a showcase: nothing in it exists to prove a point about the SDK.

The intended feel is calm, powerful, smooth, refreshing and trustworthy, with the aesthetic care of a finished system app. The tool never stands between a person and what they came to do.

It serves four moments:

1. **A new model came out.** Try it quickly, hold a conversation, see its speed, share it.
2. **Private work.** A document, a CV, some code, a real discussion, where running on the phone is the point.
3. **The settled favourite.** A model a person keeps returning to and enjoys every time.
4. **The judicious smaller model.** Knowingly using a lighter model than a hosted assistant for everyday tasks, without the experience feeling like a downgrade: honest about limits, the same quality of app.

It has to work for two kinds of person: someone who has never heard of a model or Hugging Face, and an expert. A newcomer must never feel lost and never be under-informed; an expert must find every option one level deeper.

## 2. Scope

**Features.**

- First run with three suggested models, plain-language descriptions and a chat that is usable before the download ends.
- Get models: search over Hugging Face, one fixed list of suggestions, and a model sheet with plain sizes (Basic) or every quantisation (Advanced), each shown with how it runs on this phone before anything is downloaded.
- Downloads that continue in the background, survive process death and resume where they stopped, verified by hash (D40, D47).
- Chat with streaming replies, markdown and code blocks, a folded thought ("Thought for 8 s"), Think levels, image input for models that can see, stop, edit, regenerate, copy, share, and a divider when the model changes mid-chat.
- Custom instructions applied to every chat, a default model, an optional Hugging Face token for gated models, and storage management.
- Two layouts, Sidebar and Chat list, and light, dark and system themes, both themes first-class.
- Plain limit lines with one action each: not enough memory, context full, missing model, a failed or gated download.

**Not in the app:** voice dictation and text to speech (D37), web search, tools or agents, side-by-side model comparison, accounts, cloud sync, exporting chats, a choice of chip (the SDK's `DevicePolicy.Auto` decides and the app only reports it), and maintained lists such as "new this week" or "most loved".

**Failures regardless of function:** a design language that feels lifeless and corporate, cheesy, juvenile, or like a meditation app (each was tried and rejected, section 12), and exposed machinery that a newcomer must understand before chatting.

## 3. Constraints and architecture

- The app uses only the SDK's public API (`LlamaBro`, `Conversation`, `estimateFit`, `llamabro-hub`). A need the SDK lacks is first a finding and then an SDK change, never a workaround: the two that arose were starting a `Conversation` from stored messages (F96) and reading the memory budget for fit labels (F97).
- Search is the app's job (D42): `GET /api/models?search=…&filter=gguf` on Hugging Face, then the Hub resolves the chosen repository.
- Loading takes seconds and cannot be made faster (D30, F58); the interface masks it (section 4.1). Memory is tight on real phones: a load can be refused with `InsufficientMemory`, and the app explains that in plain words and offers what to do, never crashing (F90).
- Layers: UI, view models exposing `StateFlow`s derived with `combine` and `stateIn`, repositories, the SDK. Only genuine input is a mutable flow. Manual wiring through an application container, no dependency injection framework. Room holds chats and messages (text, image references, thinking text, the model used, times and timings), DataStore holds settings, images are copied into app-private storage, and the Hugging Face token lives under a Keystore key and is sent only to `huggingface.co`.
- Chats are stored by the app and sent to the model through the SDK's `Conversation`; editing, regenerating, switching models and reopening a chat rebuild the `Conversation` from the stored messages (F96).
- Speculative decoding stays at the SDK default, which is off on every chip (F67, F68): the app downloads no draft models and offers no switch.
- Background downloads run as WorkManager jobs with a foreground notification. The app must hold its notification permission for a force-stopped download to resume as a foreground service (F92).
- Package `com.suhel.llamabro.demo`, API 29 and up, arm64-v8a only. No network except Hugging Face; no analytics.

## 4. The experience

### 4.1 First run

1. **Arrive.** A subtle pearl-coloured orb, a greeting by time of day, one line: "Pick a mind to talk to. It lives on this phone: private, and it works offline.", a search bar, and **Picked for your phone** with a **What's a model?** link that opens a two-line plain answer. Three suggestions (Qwen3.5 0.8B, Gemma 3 4B, Qwen3.5 4B), each with its plain line, its size in GB and a fit word. No icons.
2. **A closer look (a sheet).** The model's name and maker, what it is good at and its honest limits, a fit line, four facts (thinking, images, memory, download size and time), **Get <name>**, and "From Hugging Face".
3. **Talk right away.** Tapping Get opens the chat at once. The download shows as a ring with a percentage beside the model's name in the top bar (D50); the person can type the first message now and it is held until the model is ready ("<Name> will answer the moment it's here"). Leaving the app keeps the download going, with a notification.
4. **It wakes, then answers.** While the model loads, a fine hairline sweeps across the top of the reply area; there is no spinner and no percentage. Then the held message is answered. With animations turned off the hairline is static.

### 4.2 Two layouts

Chosen in Settings, Appearance, each shown as a small preview. Sidebar is the default. Switching applies at once with a short crossfade, keeps the open chat, and leaves chats, models and settings the same. The bubbles belong to Chat list and the open page to Sidebar; there is no separate message-style switch. Search covers chat titles and message text. There are no unread counts, ticks or presence indicators, because a local model is always there.

**Sidebar.**

- Home is a new chat. The drawer holds New chat, Models (the library, with a count), chats grouped by date, and Settings at the bottom; the open chat is marked.
- A new chat opens with the default model: the last model used, unless one was pinned with "Use for new chats". An empty chat shows the small orb, "What's on your mind?" and starter chips.
- Tapping the model's name in the top bar opens a sheet listing the models on the phone, the default outlined with a **Default** chip, and **Get more models**. Switching mid-chat keeps the conversation and adds a quiet "Switched to <Name>" divider.
- Sent messages are in a soft neutral bubble on the right; the model writes on the page, full width, with the speed line under the reply.

**Chat list.**

- Home is the list of chats, newest first, under a sans "Chats" title and a search box. A row shows an avatar with the model's initial, the title, the time or day, and the last message prefixed by its sender.
- New chat is a floating button. Bottom tabs hold Chats, Models and Settings.
- A conversation screen has a back arrow, the avatar, the title and the model's name under it (tap to switch). Both sides are in bubbles, the model's on a raised surface; the time sits in each bubble's corner, with the speed on the model's, and quiet day labels separate days. Back from a chat reached after a layout switch goes to the list.

Thinking folds, code, images, edit, regenerate, share, copy and the model divider work the same in both layouts.

### 4.3 Finding models and the library

- **Get models** is a search bar over Hugging Face and one fixed list, **Suggested for this phone**, hardcoded: Qwen3.5 0.8B, Gemma 3 4B, Qwen3.5 4B, Llama 3.2 3B and Qwen3.5 9B, in that order, each with a fit word and a plain line. Queries under two characters show the suggestions instead; a longer query is debounced by 350 ms.
- **Results** are models, not repositories: uploads of the same model collapse into one row, represented by the repository with the most downloads. A row shows the fit word, a plain line, "Thinks" (known only for the suggested models) and "Sees images" (from the pipeline tag), and the download count.
- **The model sheet** shows the name, then repository and maker, a short description, and a Basic and Advanced switch:
  - **Basic:** three sizes in plain words with no quantisation names: **Smaller** ("a little less sharp"), **Recommended** (preselected, "the best balance") and **Best quality** ("near-original, slower"), each with size and fit. Recommended is Q4_K_M where it exists, else UD-Q4_K_XL, else the largest Q4; Smaller is the largest file under 75% of Recommended; Best quality is Q8_0, else Q6_K, else the largest. A tier with no matching file is hidden.
  - **Advanced:** every quantisation in equal-height rows with aligned columns (quantisation, size, how it runs here), the selected row outlined with a Recommended label inside it, and the image reader as a matching row marked "Included".
  - The button names the model (Basic) or the quantisation (Advanced) and the time or total size.
- **Library (Models):** storage used and free, each model with its last use, the default outlined with a Default chip, swipe to remove (after asking), a Downloading section, and Get more models.

### 4.4 Chat

- Messages with markdown (paragraphs, headings, bold, italic, inline code, fenced code with a copy button, lists, quotes, links), copy, and stop. A stopped reply is kept and marked "Stopped".
- **Images:** the **+** button attaches a photo. The button stays visible for a model that cannot see images; using it shows the plain limit line that says so.
- **Edit and regenerate:** edit a sent message to cut the chat back to it and send again, or ask for a new answer. Copy, share and regenerate show under the latest reply only.
- **Share a reply** as a tidy image card with the reply, the model's name and its speed.
- **Speed:** one quiet line under a reply, "38 words/s" (tokens times 0.75).
- **Scrolling:** the list follows a streaming reply only while at its end; scrolled away, a **back to the latest message** button appears.
- **Custom instructions** (Settings) become the system prompt of every chat; with none, no system prompt is sent.

### 4.5 Settings

Appearance (Layout with previews; Theme: System, Light, Dark), Custom instructions, Default model, a Hugging Face token (optional, for gated models; a masked field that is never suggested or corrected), Storage (the library) and About (versions and licences). Settings, About and Storage are grouped cards (section 8).

## 5. Model naming

A model has one name, its own, on every surface that names it: first run, Get models, the model sheet, the switcher, Default model, the library, the chat's top bar, the dividers and the notifications ("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B"). The plain line under a name says what it is for ("Tiny and quick, for everyday questions"). Another file of the same repository in another size keeps the name; its size says which. The library lists one entry per downloaded file. Decided (2026-11-12, D50).

The suggested models, with the plain line shown under each (the catalog also pins file sizes and a maker):

| Model | Plain line | Thinks | Sees images |
|---|---|---|---|
| Qwen3.5 0.8B | Tiny and quick, for everyday questions | yes | yes |
| Gemma 3 4B | A careful writer, good with tone | no | yes |
| Qwen3.5 4B | Thinks things through before answering | yes | yes |
| Llama 3.2 3B | Meta's all-rounder, friendly and fast | no | no |
| Qwen3.5 9B | Smarter, but heavy for this phone | yes | yes |

A catalog entry is keyed by repository and quantisation, but its name applies to every upload of that repository, so a size the picker recommends instead of the catalog's own file is not listed under a second name (F112). Search rows that are not in the catalog take their name from the repository and a plain line from the parameter count in the name: under 1.5B "Tiny and quick, for everyday questions", under 5B "Balanced, a good all-rounder", under 10B "Smarter, but heavy for a phone", otherwise "Needs a computer, not a phone", and "A model from <author>" when the size is unknown.

## 6. Downloads

- Each download is a WorkManager job with a foreground notification showing progress; one file at a time by default (D44). The Hub checks every file's SHA-256 before it enters the cache and re-hashes a resumed partial (D40, D47).
- Progress shows on the row it was started from (a thin bar, percentage and time left), in the library under Downloading (megabytes done of total, speed, time left, Pause and cancel), in the chat's top bar beside the model's name, and as a notification outside the app. Cancel deletes the partial; Pause keeps it.
- The estimated time on a button is the size divided by 8 MB/s, rounded up to minutes ("about 3 min", "under a minute"); the real download measured about 10 MB/s on Wi-Fi (F93).
- A failed download becomes a plain line with an action (section 9); saving a token restarts the downloads refused for want of one.

## 7. Fit labels and Think levels

### 7.1 Fit

Every model, size and file is shown with one fit word, coloured, never a card tint.

- **Memory** comes from the SDK. The model sheet asks the Hub's `estimateFit` for each size, which reads only the GGUF headers (F88). A list cannot afford that per row, so lists use a quick estimate from the file's size (model plus image reader) against the SDK's memory budget, and the sheet replaces it with the real one. The suggestions and first-run picks ship with their sizes, so the first screen needs no network.
- **Context room:** large at 16,384 tokens or more, normal at 4,096 or more, small below that, none when it does not fit. From the quick estimate: headroom of at least 1.5 GiB is large, at least 0.5 GiB normal, anything above zero small.
- **Speed** cannot be measured before a download. Decoding is memory-bound, so tokens per second is about a constant per chip divided by the model's size in bytes, with constants taken from measurements on the test phone: 42e9 for the NPU, 26e9 for the GPU, 30e9 for the CPU (F67, F78, F27).
- **Words:** **Runs great** (large room and at least 20 tokens a second), **Runs well**, **Slow here** (small room or under 8 tokens a second), **Too big** (does not fit).

### 7.2 Think levels

The **Think** lightbulb appears in the message bar only for a model that can think: a suggested model says so, else the chat template reports reasoning effort, else it mentions `enable_thinking` or `<think>` (F97). It opens a menu of four levels:

| Level | What the request sends |
|---|---|
| Off | thinking disabled |
| Quick | thinking enabled with a budget of 512 tokens |
| Balanced | thinking enabled with a budget of 2,048 tokens |
| Deep | thinking enabled, no budget |

The budget covers the reasoning only. A reply shows "Thought for 4 s" folded, and while thinking, the live thoughts can be opened. The bulb's description says "Think" or "Think · Quick".

A small model can loop in its answer, and nothing in the app ends it: Stop is the only exit (F113). A cap on the reply length per level, Qwen's recommended presence penalty, and a Settings page for the sampling values are open (section 12).

## 8. Design system

The system lives in `demo/designsystem`. Its values are Material 3's wherever Material gives one, read from the Compose Material 3 1.4.0 token sources; where Material is silent (a composer, a bubble, section gaps) a value is marked chosen and follows what the leading chat apps do (D49, F116). Nothing in a screen may carry a number of its own; the build enforces it ([the design-system gates](../testing.md#the-design-system-gates-ds)). The design system was introduced because an audit found uneven gutters, gaps, type roles and states across screens (F109); this section is the result (D48).

### 8.1 Character

Calm, classy, a little alive. Depth and restraint over softness or loudness. Warm-neutral paper in light mode and graphite in dark mode, never pure white or black. A classic serif only for hero moments; a clean sans everywhere else. Soft shadows and generous but not floaty spacing. Motion only where it earns its place: sheets, the download ring, the wake-up hairline, the thinking fold.

### 8.2 Type

Material 3 roles, a screen naming a role and never a size. Each role carries size, line height, weight, tracking and face; every line height is a multiple of 4.

| Role | Size / line | Weight | Face | Use |
|---|---|---|---|---|
| `displayHero` | 28 / 36 | Medium | Serif | first-run greeting, the empty-chat line |
| `sheetTitle` | 22 / 28 | Medium | Serif | a model sheet's title |
| `screenTitle` | 22 / 28 | Medium | Sans | a top bar's title, "Chats" |
| `title` | 16 / 24 | Medium | Sans | a row's or card's title |
| `titleSmall` | 14 / 20 | Medium | Sans | the model's name under a top bar, a group heading inside a card |
| `body` | 16 / 24 | Regular | Sans | message text, descriptions, field text |
| `bodyMedium` | 14 / 20 | Regular | Sans | a row's subtitle, a card's description; code in monospace |
| `bodySmall` | 12 / 16 | Regular | Sans | a reply's meta line, a card's facts, captions |
| `label` | 14 / 20 | Medium | Sans | a button's or chip's text |
| `labelSmall` | 12 / 16 | Medium | Sans | an upper-case section label, a badge |

The serif is for the two hero roles only (the first-run greeting and the empty-chat line, and a model sheet's title); every screen title, row and label is sans. There is no role below 12 sp. Body text is 16 sp.

### 8.3 Spacing and the gutter

The grid is 4 dp for small things and 8 dp for layout. The scale is 4, 8, 12, 16, 24, 32 and 48, with a name per use so that no screen picks a number:

| Name | dp | Meaning |
|---|---|---|
| `inline` | 4 | inside one unit: a title to its subtitle, a message to its meta line, a label to its field |
| `block` | 8 | between blocks of one unit: paragraph, list or code block of one reply; the cards of one list |
| `item` | 16 | between items of different units: two replies of one speaker, a card's inner padding |
| `section` | 24 | between sections, and between turns (user to model) |
| `hero` | 32, 48 | around a hero or an empty state |
| `gutter` | 16 | the left and right edge of every screen, sheet, composer and banner |

The gutter is 16 dp everywhere, measured from the edge of a content column that is at most 640 dp wide and centred (Material's cap for a sheet), so a tablet does not stretch lines. Gaps follow proximity: related things are closer than unrelated ones, and the gap around a group exceeds the gap inside it. A section label belongs to what follows it: 4 dp above its first row, with the previous section's last row 24 dp above the label. A reply's meta line sits 4 dp under it, and the action row (48 dp tall, a touch target) sits 8 dp under that.

### 8.4 Layout primitives

Screens and sheets are assembled from primitives that own the gutter and the gaps and that take content, never padding: `Screen`, `ListScreen`, `Sheet`, `Section`, `Stack` and `Inline` (gaps from the named steps only), with `BottomBar`, `MessageColumn`, `Centered`, `ScrollStack` and `LazyStack` for specific jobs. A sheet is the 48 dp handle area, then content on the gutter, ending 16 dp above the bottom inset with no dead space; its primary action is a 56 dp navy button. A sheet whose content grows after it opens re-expands so that its content stays on screen (F118).

### 8.5 Controls and shapes

| Part | Size |
|---|---|
| List row, one / two / three lines | 56 / 72 / 88 dp |
| Row's leading icon / avatar | 24 / 40 dp |
| Top app bar | 64 dp |
| Navigation bar | 64 dp (80 dp with labels) |
| Button, medium / small | 56 / 40 dp |
| Search bar and text field | 56 dp |
| Chip | 32 dp |
| Sheet's drag handle | 32 × 4 dp in a 48 dp area |
| Divider | 1 dp |
| Touch target, minimum | 48 dp |

| Part | Corner |
|---|---|
| Bottom sheet (top corners) | 28 dp |
| Card (grouped list, model card, selectable card) | 12 dp |
| Chip | 8 dp |
| Button, search bar, FAB | full |
| A sent message's bubble | 20 dp (chosen) |
| Message bar | 28 dp (chosen) |
| Extended FAB | 16 dp |

### 8.6 Colour

One primary: **navy**. Primary buttons (Get, Save, New chat), the send button, the floating button, the selected border, links, progress and the selected navigation item are navy. Near-black is ink, used for text only. Contrast is 4.5:1 for every text pair and 3:1 for large text (18 sp, or 14 sp bold) and icons, in both themes, checked by a unit test.

| Token | Light | Dark |
|---|---|---|
| Canvas | `#F7F6F3` | `#121316` |
| Surface | `#FFFFFF` | `#1B1C20` |
| Ink | `#17181C` | `#ECEBE8` |
| Muted text | `#55565C` | `#A5A6AC` |
| Hairline | `#E6E4DF` | `#26272C` |
| User bubble | `#E9E7E1` | `#2A2C31` |
| Navy primary (accent) | `#25304D` | `#B9C4DE` |
| Accent soft | `#E4E7EF` | `#252A38` |
| Fit: runs great (text) | `#3E5641` | `#A9C3AA` |
| Fit: slow here (text) | `#73572F` | `#D3B588` |
| Fit: too big (text) | `#6E6E73` | `#8C8C92` |
| Danger ("Remove") | `#A23B2F` | `#E59A90` |

The orb is a pearl, mostly white with a faint cool and warm sheen (darker blue-grey in dark), about 56 dp on the first screen and 40 dp in an empty chat. Colour was chosen because brightness and saturation affect feeling more than hue: a deep, low-saturation accent reads as composed and trustworthy.

### 8.7 States

Every state is one signal:

- **Selected** is a 2 dp navy border for a card that is one choice among cards (a size, a file, a model in the switcher, a layout), and a navy check at the end of a row that is one choice inside a grouped list (the Think levels, the default model, the open chat in the drawer). A segmented switch raises its chosen segment. Nothing else means selected; an "included" file is not outlined. A selected row exposes the `selected` semantic.
- **Fit** is a coloured word, never a card tint. **Recommended** is a label inside the card. **Disabled** is ink at 38% on a surface at 12%, still readable as a label.
- Every string a person reads starts with a capital ("Used today", "Paused"); a count or time that begins with a figure is left as it is.

### 8.8 The message bar

The text over a row of buttons, in one outlined surface (1 dp hairline, 28 dp corner). The text (`body`) starts 16 dp from the edge and 16 dp below the top, 4 dp above the row, and grows to six lines. The row holds three 40 dp circles in 48 dp targets, 8 dp from the bar's bottom and sides so that they are concentric with the corner (20 + 8 = 28):

- **+** at the start, tonal.
- At the end, the **Think** lightbulb (only for a model that can think; tonal when off, the soft accent with the accent ink when on) and **send**, 8 dp apart.
- **Send** is a tonal circle with muted ink that does nothing while there is no text and no photo, navy when it can act, and a stop button while a reply is coming (D49).

A one-line bar is about 91 dp tall. Decided (2026-11-12, D50): Think is a lightbulb icon inside the bar, not a chip outside it. The download's ring and percentage sit beside the model's name in the top bar, not by the new-chat button; the time left is in the ring's description and the notification.

### 8.9 Rows, groups and settings

A row's title is `title` on every row and card, whether or not it has a subtitle (`bodyMedium` in muted ink). Trailing text sits on the padding edge, never mid-row; a row that opens a page ends in a 24 dp chevron. Settings, About and Storage are grouped cards: a 12 dp-cornered card per group, a 1 dp divider inset 16 dp between its rows, a chevron on rows that open a page, and a section label above each group on the gutter. Buttons are pills; a bordered one is outlined and 40 dp tall; a destructive one is drawn in the danger ink.

### 8.10 Chat details

A sent message's bubble has 16 dp horizontal and 12 dp vertical padding and ink text; a model reply in the open style has none. The empty chat's chips are `label` text in 32 dp chips 8 dp apart, centred under the hero. Banners sit on the gutter 8 dp above the message bar.

## 9. Limits and error lines

A limit is one plain line at the moment it matters, with the fix as a button; there is no nagging.

| Situation | Line | Action |
|---|---|---|
| Not enough memory to load | "<Name> needs more memory than this phone has free right now." | **Try again**, then **Smaller size**, which opens this model's own sheet on the Smaller size; Get then starts that file and moves the chat to it with the question held (D49, F107) |
| Context full | "This chat is longer than <Name> can hold." | Start a new chat |
| Image sent to a model that cannot see | "<Name> can't see images. <Other model> can." | Switch to the other model, or Get one that can |
| Download paused or failed, retryable | "The download paused. It'll pick up where it left off." | Retry |
| Download refused for a missing token | "This model needs a Hugging Face token." | Add token |
| Download refused, not retryable | "Hugging Face wouldn't give us this file." | Retry |
| Not enough storage | "There isn't enough space on this phone for this model." | Retry |
| Model removed while a chat uses it | "<Name> isn't on this phone any more." | Switch model |

A chat whose model is gone stays readable. A message typed while a model downloads is held and answered in order, once. Choosing a model in an empty chat does not add a divider, and editing a message removes a divider that the edit cuts away (F115). A phone with no system photo picker says "This phone has no photo picker." instead of failing (F107). The reason a load failed comes from the SDK's `ModelLoadFailed.reason` (F51).

## 10. Performance

Budgets are for the test phone with the release build and the Baseline Profile and are checked by the optional benchmark step. All were met (F108):

| Journey | Budget | Measured |
|---|---|---|
| Cold start, first frame (median) | 450 ms | 116 ms |
| Cold start, fully drawn (median) | 700 ms | 198 ms (255 ms after the design system rework) |
| Fling the Chat list of 300 chats, frame overrun P90 | 0 ms | -9.0 ms |
| Fling a chat of 300 messages, frame overrun P90 | 0 ms | -7.3 ms |
| A reply streaming at 60 tokens a second for 20 s, frame overrun P90 | 0 ms | -1.4 ms |
| `ChatEngine.onDelta` and `Markdown.parse`, per call (median) | 0.5 ms each | 5 microseconds and 0.18 ms |
| Model sheet Basic and Advanced, layout switch, frame overrun P90 | 0 ms | -6.0 ms and -4.5 ms |
| Java heap held while streaming in a 300-message chat | 64 MB | 21.2 MB |

The method, the budgets' exact definitions and what they do not cover are in [../testing.md](../testing.md#performance-budgets-perf). The design assumes these rules: stable or immutable parameters; lambdas instead of passed state where a value changes often; lazy list items with stable keys and content types; no parsing, formatting or decoding in composition; the streaming reply as its own flow, separate from the stored list; deltas coalesced to at most one update per frame; the database written at most every 250 ms during a stream; markdown re-parsing only the last block; and a recomposition test per screen (while one reply streams only its row recomposes, and typing does not recompose the list). The real-model run on the phone met the chat benchmarks.

## 11. Accessibility

- **Touch targets** are at least 48 dp. The long-press area of a bubble or reply and a sheet's drag handle were found too small and now carry a 48 dp area, and the handle a label (F101).
- **Contrast** is 4.5:1 for text and 3:1 for large text and icons in both themes, asserted by a unit test; the status bar's icons follow the app's own theme choice rather than the phone's (F102), and the window background follows it too, so no strip shows behind the status bar.
- **Labels and semantics.** Every icon button has a description; the Think bulb's description names the level; a selected row exposes the `selected` semantic; a progress ring's description includes the time left; the Compose accessibility checks run on every screen test and, once a surface has settled, on every screen and sheet of the walk.
- **Text scale.** The screens were walked at font scales 1.3 and 2.0: text wraps, buttons wrap to two lines and nothing clips; a "Recommended" label stacks past 1.3.
- **Motion.** The wake hairline is static when animations are off.
- **Layout direction.** Right-to-left was found by screenshot tests: bubbles are placed with start and end rather than fixed edges, back and chevron icons mirror, and code blocks stay left-to-right.
- **Large screens.** Content is capped at 640 dp and centred, with the gutter measured from the column's edge. Landscape and a tablet were walked: the screens hold, the header takes a third of the height in landscape and the lists scroll.
- **A secret field** is masked, never suggested or corrected, and ends with Done.

## 12. Open items and rejected directions

**Open.**

- The models do not appear to see an attached picture on the phone (F119). The app passes the right pixels and the SDK passes the same image case against upstream on the host and on the NPU; upstream on the host reads the picture correctly, so the fault is on the phone or in the app's use of it. No test asserted image understanding before.
- A small model can loop without end in its answer and the app has no guard (F113).
- Sampling values have no Settings page, with a "reset to the model's default".
- The reasoning budget's forced end is untested on a small model.
- Voice dictation is the first feature planned after the app.
- Not audited: a physical fold or tablet, and right-to-left on a device (the emulator ignored the system setting; it is covered by screenshot tests).

**Rejected directions**, so they do not return: a pastel orb with a serif greeting alone (reads as a meditation app); a dark "instrument" look, an editorial "paper" look and a chat-first minimal screen (soulless); a talking onboarding with a house metaphor (too much text); vivid album-art images per model (cheesy); a professional monogram list (lifeless); a violet accent (generic AI); a mid teal (juvenile); nicknames for models (D50).
