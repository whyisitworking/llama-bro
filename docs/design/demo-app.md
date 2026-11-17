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
- Chat with streaming replies, markdown and code blocks, a folded thought ("Thought for 8 s"), Think levels, image input for models that can see, voice messages for models that hear (D76), stop, edit, regenerate, copy, share, and a divider when the model changes mid-chat.
- Custom instructions applied to every chat, a default model, an optional Hugging Face token for gated models, and storage management.
- A choice of chip in Settings › Memory (Automatic, NPU, GPU or CPU), with where the model actually runs and why when that isn't the chip chosen (D72).
- Two layouts, Sidebar and Chat list, and light, dark and system themes, both themes first-class.
- Plain limit lines with one action each: not enough memory, context full, missing model, a failed or gated download.

**Not in the app:** dictation (speech turned into text) and text to speech (D37, D76), web search, tools or agents, side-by-side model comparison, accounts, cloud sync, exporting chats, and maintained lists such as "new this week" or "most loved".

**Failures regardless of function:** a design language that feels lifeless and corporate, cheesy, juvenile, or like a meditation app (each was tried and rejected, section 12), and exposed machinery that a newcomer must understand before chatting.

## 3. Constraints and architecture

- The app uses only the SDK's public API (`LlamaBro`, `Conversation`, `estimateFit`, `llamabro-hub`). A need the SDK lacks is first a finding and then an SDK change, never a workaround: the two that arose were starting a `Conversation` from stored messages (F96) and reading the memory budget for fit labels (F97).
- Search is the SDK's (D68, replacing D42's rule): `hub.search` finds GGUF models, grouping a model's quantized uploads
  under its original, and `hub.files` lists a repository's quantisations with their sizes. The app names the rows
  (section 5), merges results that share a name into one row, and makes its own fit verdict from
  `llama.estimateFit(hub.model(…))` (section 7.1); the SDK makes none.
- A model loads by its name, `llama.loadModel(hub.model(key))`, whose installed copy loads with no network (D64). The
  app never starts a download the person didn't ask for: it loads only what the library lists, and a load of a model
  deleted meanwhile fails rather than fetching it.
- Loading takes seconds and cannot be made faster (D30, F58); the interface masks it (section 4.1). Memory is tight on real phones: a load can be refused with `InsufficientMemory`, and the app explains that in plain words and offers what to do, never crashing (F90).
- Layers: UI, view models exposing `StateFlow`s derived with `combine` and `stateIn`, repositories, the SDK. Only genuine input is a mutable flow. Manual wiring through an application container, no dependency injection framework. Room holds chats and messages (text, image references, thinking text, the model used, times and timings), DataStore holds settings, images are copied into app-private storage, and the Hugging Face token lives under a Keystore key and is sent only to `huggingface.co`.
- Chats are stored by the app and sent to the model through the SDK's `Conversation`; editing, regenerating, switching models and reopening a chat rebuild the `Conversation` from the stored messages (F96).
- Speculative decoding stays at the SDK default, which is off on every chip (F67, F68): the app downloads no draft models and offers no switch.
- The model loads with the chip choice's `DevicePolicy` (D72) and the context length's `ContextSize` (`Auto`, or `UpTo(n)`, D78); the host keys a load on both (`LoadConfig`), so a model loaded under another config is not reused.
- Background downloads are the SDK's worker (`llamabro-hub-work`, D64, D75): `DemoApp` registers `LlamaBroWorkerFactory`
  over the container's hub, so a download uses the app's token and its progress is the hub's own, and the notifications
  speak the app's words through the SDK's override (`ModelNotifications`). The app must hold its notification permission
  for a force-stopped download to resume as a foreground service (F92).
- Package `com.suhel.llamabro.demo`, API 29 and up, arm64-v8a only. No network except Hugging Face; no analytics.

## 4. The experience

### 4.1 First run

1. **Arrive.** A small warm orb (amber to rose), a greeting by time of day, one line: "Pick a mind to talk to. It lives on this phone: private, and it works offline.", a search bar, and **Picked for your phone** with a **What's a model?** link that opens a two-line plain answer. Three suggestions (Qwen3.5 0.8B, Gemma 3 4B, Qwen3.5 4B), each with its plain line, its size in GB and a fit word. No icons.
2. **A closer look (a sheet).** The model's name and maker, what it is good at and its honest limits, a fit line, four facts (thinking, images, memory, download size and time), **Get <name>**, and "From Hugging Face".
3. **Talk right away.** Tapping Get opens the chat at once. The download shows as a ring with a percentage beside the model's name in the top bar (D50); the person can type the first message now and it is held until the model is ready ("<Name> will answer the moment it's here"). Leaving the app keeps the download going, with a notification.
4. **It wakes, then answers.** While the model loads, a fine accent line under the top bar fills as the load goes on: the accent over a soft accent track, like the download ring, from the SDK's load progress. Before the first report it sweeps; there is no spinner and no number. Then the held message is answered. With animations turned off the line jumps to each value, and the sweep is static.

### 4.2 Two layouts

Chosen in Settings, Appearance, each shown as a small preview. Sidebar is the default. Switching applies at once with a short crossfade, keeps the open chat, and leaves chats, models and settings the same. The bubbles belong to Chat list and the open page to Sidebar; there is no separate message-style switch. Search covers chat titles and message text. There are no unread counts, ticks or presence indicators, because a local model is always there.

**Sidebar.**

- Home is a new chat. The drawer holds the chats grouped by date (Today, This week, Earlier), each a dense one-line row 48 dp tall with the open chat ticked and a long title ellipsized; under them, above a hairline and the navigation bar, one row of actions: Settings and Models as icons at the start, and New chat (Material's `add_comment`) at the end in a tonal circle, the drawer's one strong action, under the thumb (D77). Each is a 48 dp target; a screen reader says "Settings", "Models, 2 on this phone" (the count is spoken, not shown) and "New chat", and reaches them before the chats: the drawer is one traversal group with its actions first. The first icon starts on the rows' 16 dp text line and the circle ends on their tick line.
- A new chat opens with the default model: the last model used, unless one was pinned with "Use for new chats". An empty chat shows the small orb, "What's on your mind?" and starter chips.
- Tapping the model's name in the top bar opens a sheet listing the models on the phone, the default outlined with a **Default** chip, and **Get more models**. Switching mid-chat keeps the conversation and adds a quiet "Switched to <Name>" divider.
- Sent messages are in a soft neutral bubble on the right; the model writes on the page, full width, with its small actions under the reply.

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

- Messages with markdown, parsed as CommonMark with the GitHub tables and strikethrough (D74): paragraphs, headings of every level, bold, italic and strikethrough with `*` or `_`, inline code, fenced and indented code with a copy button, nested ordered and bulleted lists, nested quotes, tables, horizontal rules, links and bare URLs, and backslash escapes. An image shows its alt text, HTML shows as typed, and maths is not rendered (it shows as typed). Only http, https and mailto links open; any other target shows as plain text. Copy, and stop. A stopped reply is kept and marked "Stopped". A reply that reaches its length limit is kept too: as the chat's last reply it shows "Stopped at the length limit." with **Continue**, which goes on writing the same reply; once the chat has moved on it is only marked (section 4.7).
- **Images:** the **+** button attaches a photo. The button stays visible for a model that cannot see images; using it shows the plain limit line that says so. Tapping a photo in a sent message opens it full-screen, where it zooms and pans, swipes to the message's other photos, and closes with a swipe down, the ✕ or back (section 8.10).
- **Voice messages** (D76), for a model that hears: with the text field empty, the mic in Send's slot records; the recording is sent as audio, so the model hears the words and the tone, with nothing transcribed. Photos already in the bar go with it. In the chat it is a sent-side bubble with play and pause, a waveform and its length, and no Edit. A recording stops at 2 minutes (about 1,500 tokens, F152) and, when the screen is left or a call comes, waits in the bar for ✕ or ↑. A chat that moves to a model that can't hear sends a placeholder for each voice message, with a limit line offering a model that hears. Recordings are app-private and deleted with their chat.
- **Edit and regenerate:** edit a sent message to cut the chat back to it and send again, or ask for a new answer. Copy, share and regenerate show under the latest reply only.
- **Share a reply** as a tidy image card with the reply, the model's name and its speed.
- **Speed and the rest of what was measured:** behind the info icon among a reply's actions, in a "Reply details" sheet: the model, the chip it ran on, the speed ("38 words/s · 14.2 tokens/s", words being tokens times 0.75), the reply's and the prompt's tokens (with what came from the cache), how fast the prompt was read, how long it thought, and how many MTP drafts were kept. Each line shows only when it's known. The actions themselves (copy, share, regenerate, details) are 18 dp glyphs in 48 dp targets.
- **Scrolling:** the list keeps a streaming reply's newest text in view while the reader is at its end. Scrolling up, even within a reply taller than the screen, stops that and shows a **back to the latest message** button; the button, scrolling back to the very end, or sending a message follows the reply again.
- **Custom instructions** (Settings) become the system prompt of every chat; with none, no system prompt is sent.

### 4.5 Settings

Appearance (Layout with previews; Theme: System, Light, Dark), Custom instructions, Default model, a Hugging Face token (optional, for gated models; a masked field that is never suggested or corrected), Sampling (section 4.7), Memory (section 4.6), Storage (the library) and About (versions and licences). Settings, About and Storage are grouped cards (section 8).

### 4.6 The model's memory

One model is in memory at a time, owned by the app, not by a chat: the first message loads it, a switch replaces it, and a chat that is left keeps it for the next (D56, D59). A model is never closed while a reply streams from it: each turn holds the model until it ends, so a message sent in another chat on another model waits behind the wake line until that reply ends, then its model replaces the one in memory and answers; nothing has to be sent again. Stop ends the wait; the model still changes once that reply ends. Until then Settings › Memory and the model sheet still show the model in memory. The person sees it and decides:

- **In memory now.** Settings › Memory, and the chat's model sheet under "In memory", show the loaded model ("Qwen3.5 4B", "2.8 GB · NPU · 16k context": the weights, the chip, and the context the SDK picked, in whole k rounded down; the context's memory comes on top of the size) with **Unload**. In Settings one line under it says, for both choices below: "Changing the chip or the context length reloads the model." While a reply is being written the row says so instead: a reply is never cut off. Nothing loaded shows no row in the sheet, and "Nothing loaded" in Settings.
- **Runs on.** Automatic (the default: "The fastest chip that can run the model", the SDK's `Auto`), NPU, GPU or CPU, each `Prefer(kind)`, so a chip that can't load the model falls back instead of failing (D72). A kind this phone doesn't have stays listed as "Not on this phone, so the fastest chip it has runs the model". Under the choice: "Running on the CPU: the GPU couldn't load it." when the model was loaded for this choice and isn't on that chip; "Moves to the CPU after this reply." (or "… with the next message.") while the model in memory was loaded for an earlier choice (no line when it already runs on the chip chosen). A change frees a model loaded for another choice once no reply holds it, and the next message loads it on the new chip; a message sent while another reply is still being written shares the model in use. Fit and speed estimates use the chosen chip when the phone has it.
- **Context length.** How much of the chat the model sees at once (D78). Automatic (the default: "The most that fits in memory, up to 16k", the SDK's `Auto`), 4k, 8k, 16k or 32k, each `UpTo(n)`: a ceiling the SDK still fits to free memory (and to the model's training context), never below 4k, so a choice never fails a load. Subtitles give it in words ("About 3,000 words; the least memory" … "About 24,000 words, for long documents; the most memory"). Under the choice: "Reloads at 8k after this reply." (or "… with the next message."; for Automatic "Reloads at the most that fits …") while the model in memory was loaded at another length, then "How much of the chat the model sees at once. Each size is the most it gets: with less memory free, it gets less, down to 4k." A change reloads exactly as a chip change does (D72): it frees a model loaded under another config once no reply holds it, a message sent beside another reply shares the model in use, and a chip or context change replaces the other's pending wait (the choice itself is always saved).
- **After you leave the app.** Free it after a minute (the default: a quick look at another app keeps it ready), after 5 minutes, or keep it until Android needs the memory. Under every choice Android's own signal frees it at once (`onTrimMemory` at `TRIM_MEMORY_BACKGROUND`, which comes when the app is pushed towards the cached list, not on every Home: F134).
- **A reply still being written** when the app leaves the screen goes on under a short foreground service: a "Writing a reply…" notification with Stop, gone when the reply ends. Android allows it a few minutes; when that runs out the reply stops as Stop would stop it, keeping what arrived. There is no notification for an idle model: a service that keeps gigabytes away from the low-memory killer would close other apps instead, and Android kills an idle background app regardless.

After the model is freed the next message loads it again behind the wake line, and the chat rebuilds its conversation from the stored messages (F133).

### 4.7 Sampling and the reply's length

How a model picks its words starts from what its makers recommend and can be changed for the model or for one chat (D60):

- **Values.** Temperature, Top P, Top K, Min P and Presence penalty, each a slider row with its value and where it comes from. Nothing else: the repeat penalty (Qwen says to leave it), DRY, XTC and presets are left out.
- **Layers.** A suggested model starts from its card's values, which the catalog carries, per mode where the card
  differs (Qwen3.5 recommends other values when it thinks). Any other model starts from the hub's recommended sampling
  (D79): the GGUF file's own values, which are what llama.cpp applies at load, else the base model's
  `generation_config.json`; one set for both modes, shown as the model's default. A chat starts the lookup once its
  model is on the phone, not before: for a model not on the phone it would read the file's header over the network
  (D64). It runs in the background and never holds a turn: until it answers nothing is sent and the file's own values
  apply (F17). One that fails, or an answer given offline (the file's values alone, the base model not reachable), is
  asked again after five minutes; an answer given online is kept. Settings
  › Sampling lists the models on the phone ("Model defaults", "2 values changed"); a model's page shows "Model default",
  "Changed" or "llama.cpp default" under each value, a Thinking / Not thinking switch when its card has two sets, and
  **Reset to the model's defaults** once something changed. A change applies in both modes. In a chat, the model sheet's
  **This chat › Sampling** opens the same sliders at the chat's Think level, marked "Changed for this chat", "The
  model's setting", "Model default" or "llama.cpp default", with **Use the model's values**. The chat's value wins over
  the model's, the model's over the card's. A value nobody set is not sent, so a model file's own recommendation still
  applies. A chat with no message yet has no row: its values would have nothing to belong to.
- **The length limit.** Every reply has one unless the person removes it, its thinking and answer together. Settings › Sampling › **Reply length** sets it for every model and chat, with no per-chat override (D78): **Automatic** (the default: about 2,000 tokens for the answer plus the Think level's budget, table in section 7.2), **Short** (1,024 tokens) or **Long** (8,192), which replace the level's total and leave its thinking budget as it is (so a Short reply at Balanced or Deep can stop while it still thinks), or **No limit** ("Until the model stops or the context is full": no `max_tokens` is sent). Note under the group: "For every model and chat. A reply that reaches its length stops, and Continue goes on, unless the chat has filled the context." A small model that loops ends at the limit instead of running until Stop (F113); No limit gives that guard up on purpose. The reply is kept, "Stopped at the length limit.", and **Continue** sends the chat with that reply last, so llama-server goes on writing it, from inside its thinking if it was cut there (F137). It can be cut and continued again. A reply that ends because the chat filled the context (prompt and reply reach the loaded context) has no room to continue: it ends failed with "This chat is longer than {model} can hold." and **Start a new chat**, keeping what it wrote.

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

- Each download is the SDK's WorkManager job (`llamabro-hub-work`) with a foreground notification showing progress,
  "Downloading Qwen3.5 0.8B" over the size, time left and Cancel, and "Downloaded" or "Download failed" at its end. One
  download runs at a time, one file at a time within it (D44, F89): a second Get waits in the app's queue and starts
  when the work of the one before ends, whether it completed, was paused or cancelled (in the app or from the
  notification), or failed for good; a try the worker retries keeps its place. One stopped from the notification or
  given up by the worker reads Paused, and only Resume or Retry starts it again. The queue is rebuilt at start from the
  pending downloads and WorkManager's work, so it carries on after a restart; a download the person paused stays paused,
  and one paused before it started reads Paused with Resume. The Hub checks every file's SHA-256 before it enters the
  cache and re-hashes a resumed partial (D40, D47). The library lists a model once every one of its files is on the
  phone (`hub.source` reads it from disk); a model whose image reader is still arriving stays under Downloading.
- Progress shows on the row it was started from (a thin bar, percentage and time left), in the library under Downloading (megabytes done of total, speed, time left, Pause and cancel), in the chat's top bar beside the model's name, and as a notification outside the app. Cancel deletes the partial; Pause keeps it.
- The estimated time on a button is the size divided by 8 MB/s, rounded up to minutes ("about 3 min", "under a minute"); the real download measured about 10 MB/s on Wi-Fi (F93).
- A failed download becomes a plain line with an action (section 9); saving a token restarts the downloads refused for want of one, also after the app was restarted (the refusal is remembered).

## 7. Fit labels and Think levels

### 7.1 Fit

Every model, size and file is shown with one fit word, coloured, never a card tint.

- **Memory** comes from the SDK. The model sheet asks the Hub's `estimateFit` for each size, which reads only the GGUF headers (F88). A list cannot afford that per row, so lists use a quick estimate from the file's size (model plus image reader) against the SDK's memory budget, and the sheet replaces it with the real one. The suggestions and first-run picks ship with their sizes, so the first screen needs no network.
- **Context room:** large at 16,384 tokens or more, normal at 4,096 or more, small below that, none when it does not fit. From the quick estimate: headroom of at least 1.5 GiB is large, at least 0.5 GiB normal, anything above zero small.
- **Speed** cannot be measured before a download. Decoding is memory-bound, so tokens per second is about a constant per chip divided by the model's size in bytes, with constants taken from measurements on the test phone: 42e9 for the NPU, 26e9 for the GPU, 30e9 for the CPU (F67, F78, F27).
- **Words:** **Runs great** (large room and at least 20 tokens a second), **Runs well**, **Slow here** (small room or under 8 tokens a second), **Too big** (does not fit).

### 7.2 Think levels

The **Think** lightbulb appears in the message bar only for a model that can think: a suggested model says so, else the chat template reports reasoning effort, else it mentions `enable_thinking` or `<think>` (F97). It opens a menu of four levels:

| Level | What the request sends | The reply's length limit |
|---|---|---|
| Off | thinking disabled | 2,048 tokens |
| Quick | thinking enabled with a budget of 512 tokens | 2,560 |
| Balanced | thinking enabled with a budget of 2,048 tokens | 4,096 |
| Deep | thinking enabled, no budget | 8,192 |

The budget covers the reasoning only; the limit covers the whole reply (section 4.7). These limits are the Automatic reply length; Short, Long and No limit in Settings › Sampling replace the total and keep the budget (D78). A model that can't think has Off's limit. A reply shows "Thought for 4 s" folded, and while thinking, the live thoughts can be opened. The bulb's description says "Think" or "Think · Quick".

A small model can loop in its answer (F113). The model card's presence penalty makes it less likely, and the length limit ends it; Continue picks a reply up that was only long (D60).

## 8. Design system

The system lives in `demo/designsystem`. Its values are Material 3's wherever Material gives one, read from the Compose Material 3 1.4.0 token sources; where Material is silent (a composer, a bubble, section gaps) a value is marked chosen and follows what the leading chat apps do (D49, F116). Nothing in a screen may carry a number of its own; the build enforces it ([the design-system gates](../testing.md#the-design-system-gates-ds)). The design system was introduced because an audit found uneven gutters, gaps, type roles and states across screens (F109); this section is the result (D48).

### 8.1 Character

Calm, classy, a little alive. Depth and restraint over softness or loudness. A neutral near-white in light mode and an AMOLED near-black in dark mode, both untinted (D69). Colour is kept for meaning and for one living moment, the orb. A classic serif only for hero moments; a clean sans everywhere else. Soft shadows and generous but not floaty spacing. Motion only where it earns its place, quiet and quick (§8.11): sheets, the download ring, the wake-up hairline, the thinking fold, and in the chat what arrives, leaves or changes in place.

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
| `compact` | 12 | the vertical padding of a bubble, a list row and a dialog's button row, where 16 would look loose |
| `item` | 16 | between items of different units: two replies of one speaker, a card's inner padding |
| `section` | 24 | between sections, and between turns (user to model) |
| `hero` | 32, 48 | around a hero or an empty state |
| `gutter` | 16 | the left and right edge of every screen, sheet, composer and banner |

The gutter is 16 dp everywhere, measured from the edge of a content column that is at most 640 dp wide and centred (Material's cap for a sheet), so a tablet does not stretch lines. Gaps follow proximity: related things are closer than unrelated ones, and the gap around a group exceeds the gap inside it. A section label belongs to what follows it: 4 dp above its first row, with the previous section's last row 24 dp above the label. A section label with an action is the action's 48 dp tall, the label centred on the button's line, like Material's list subheader: every touch target is a real 48 dp of layout, never an overlay that reaches past its row, since a parent's bounds cut such an overlay and accessibility services see only what's left (F130). The thinking fold is a 48 dp row for the same reason, and its height above and below the line is the gap to the reply. A section label is inset by the 16 dp padding of the cards and rows under it, on every screen and every sheet, so it starts where their text starts, not on the gutter where their surface starts (D77); where rows reach the edge themselves, as the drawer's chats do, that inset alone puts it on their text. A muted note under a section's cards (where the model runs, what a change reloads) is a `SectionNote`, inset the same way. A label sits 4 dp above its first row on a screen and 8 dp on a sheet. Content that has no left-aligned text (a segmented switch) keeps the gutter. A reply's meta line sits 4 dp under it, and the action row (48 dp tall, a touch target) sits 8 dp under that.

### 8.4 Layout primitives

Screens and sheets are assembled from primitives that own the gutter and the gaps and that take content, never padding: `Screen`, `ListScreen`, `Sheet`, `Section`, `Stack` and `Inline` (gaps from the named steps only), with `BottomBar`, `MessageColumn`, `Centered`, `ScrollStack` and `LazyStack` for specific jobs. A sheet is the 48 dp handle area, then content on the gutter, ending 16 dp above the bottom inset with no dead space; its primary action is a 56 dp ink button. A sheet whose content grows after it opens re-expands so that its content stays on screen (F118).

### 8.5 Controls and shapes

Every size a component uses comes from `DemoSize` (heights, widths, icon sizes, strokes, elevations), every gap from `DemoSpacing` and every corner from `DemoShapes`; outside `theme/` a component has no dp literal, and `demo/scripts/check.sh` (`ds`) fails the build on one. Icons come in two sizes: 24 dp for actions and leading icons, 18 dp for a mark inside text or on a picture (a chevron, a check, a remove cross); the message bar's buttons are the one exception, a 20 dp glyph in a 36 dp circle inside a 48 dp target (D73).

| Part | Size |
|---|---|
| List row, one / two / three lines | 56 / 72 / 88 dp; a dense one-line row (the drawer's chats) 48 dp (D77) |
| Row's leading icon / avatar | 24 / 40 dp |
| Top app bar | 64 dp |
| Navigation bar | 80 dp with labels; the selected tab's indicator is a 64 × 32 dp pill |
| Button, medium / small | 56 / 40 dp |
| Search bar and text field | 56 dp |
| Chip | 32 dp |
| Tag (a quant name, "Recommended", "Default") | 20 dp pill, 8 dp each side |
| Segmented switch | 48 dp: 40 dp segments in a 4 dp inset, the track's corner the segment's plus the inset |
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

One primary: the **ink** (D69). Primary buttons (Get, Save, New chat), the floating button, the selected border, progress and the selected navigation item are ink, and what sits on them is the on-accent colour (white in light, near-black in dark). With no hue to tell states apart, a second signal does it: links and unbordered text actions ("Unload", "Reset to the model's defaults") are underlined. Two buttons are the exception (D73): the message bar's **send** (and stop, while a reply comes) and **Think on** are filled with the **send accent**, an indigo taken from the icon's night and violet mane, with the on-send-accent glyph (white in light, near-black in dark); an ink circle there read too heavy and too stark. Nothing else uses the indigo. The fit words and Remove keep their colours and are the only hues that carry meaning, besides the orb. Contrast is 4.5:1 for every text pair and 3:1 for large text (18 sp, or 14 sp bold) and icons, in both themes, checked by a unit test.

A scrim (behind the drawer, behind a sheet) is the theme's darkest token: the canvas at 50% in dark, the ink at 30% in light. Never the ink in dark, which would lay a light veil over a dark page (F131).

New chat is a speech bubble with a plus (Material's `add_comment`), the sign messaging apps use for a new conversation; the pencil means edit (a message, a chat's name) and nothing else.

| Token | Light | Dark |
|---|---|---|
| Canvas | `#FAFAFA` | `#0A0A0A` |
| Surface | `#FFFFFF` | `#141414` |
| Ink | `#0D0D0D` | `#ECECEC` |
| Muted text | `#5D5D5D` | `#A8A8A8` |
| Hairline | `#E5E5E5` | `#222222` |
| User bubble | `#EFEFEF` | `#1E1E1E` |
| Primary (accent, the ink) | `#0D0D0D` | `#ECECEC` |
| On accent | `#FFFFFF` | `#0A0A0A` |
| Accent soft | `#EBEBEB` | `#262626` |
| Send accent (send, stop, Think on; D73) | `#4F5BD5` | `#8E9BFF` |
| On send accent | `#FFFFFF` | `#0A0A0A` |
| Fit: runs great (text) | `#3E5641` | `#A9C3AA` |
| Fit: slow here (text) | `#73572F` | `#D3B588` |
| Fit: too big (text) | `#6E6E73` | `#8C8C92` |
| Danger ("Remove") | `#A23B2F` | `#E59A90` |

The orb is the app's one spark of colour: a radial gradient from amber `#FDB969` at its lit point (34% across, 28% down) to rose `#C35F5A` at the edge, the same in both themes, with a soft rose-tinted shadow. It is about 56 dp on the first screen and 40 dp in an empty chat. It takes the warm end of the icon; the icon's violet stays in the icon. Everything else is neutral so that the orb and the fit words are the only colour on a screen (D69), besides the indigo of send and Think on (D73), which marks the bar's two acting buttons and no state or meaning.

The launcher icon is adaptive: the llama on a flat night background `#02011E`, scaled into the 66 dp safe zone, with a one-colour themed layer traced from the llama's outline. The splash shows the icon on the canvas, so no other colour flashes before the first screen.

### 8.7 States

Every state is one signal:

- **Selected** is a 2 dp ink border for a card that is one choice among cards (a size, a file, a model in the switcher, a layout), and an ink check at the end of a row that is one choice inside a grouped list (the Think levels, the default model, the open chat in the drawer). A segmented switch raises its chosen segment. Nothing else means selected; an "included" file is not outlined. A selected row exposes the `selected` semantic.
- **Fit** is a coloured word, never a card tint. **Recommended** is a label inside the card. **Disabled** is ink at 38% on a surface at 12%, still readable as a label.
- Every string a person reads starts with a capital ("Used today", "Paused"); a count or time that begins with a figure is left as it is.

### 8.8 The message bar

The text over a row of buttons, in one outlined surface (1 dp hairline, 28 dp corner). The text (`body`) starts 16 dp from the edge and 16 dp below the top, and grows to six lines; it capitalises the start of each sentence. The buttons' circles sit 14 dp under it (8 dp of padding and the 6 dp each circle leaves in its target). The row holds three 36 dp circles with 20 dp glyphs (D73) in 48 dp targets, 10 dp from the bar's bottom and sides so that they are concentric with the corner (18 + 10 = 28). The targets stay where the 40 dp circles had them, 4 dp from the bar's edge, so each circle's centre stays 28 dp in and the bar's height is unchanged; the circles' gaps grow by the 4 dp they lost (12 dp apart, 14 dp under the text, where 40 dp circles were 8 and 12):

- **+** at the start, tonal (the hairline's fill, an ink glyph).
- At the end, the **Think** lightbulb (only for a model that can think; tonal when off, filled with the send accent when on) and **send**, 12 dp apart.
- **Send** is a tonal circle with a muted glyph that does nothing while there is no text and no photo, filled with the send accent (indigo, `#4F5BD5` / `#8E9BFF`, §8.6) when it can act, and a stop button on the same fill while a reply is coming (D49). The glyph on the indigo is 5.5:1 in light and 7.8:1 in dark. For a model that hears, an empty text field shows the **mic** in its place instead (D76); with photos attached and no text, the mic sits just before Send, 12 dp apart like Think, so photos alone can still be sent. The mic appears once the model has said it hears on a load, and the app remembers that for its next load.

**Recording** (D76). A tap on the mic turns the text area into a live waveform of the input level with a timer; **✕** takes the + slot and discards, **↑** takes Send's slot and sends. The timer turns ink in the last 10 s before the 2-minute stop. Starting and stopping are announced, and with animations off the waveform holds still.

Attached pictures sit inside the bar, above the text, never above the bar where they would read as already sent: 64 dp thumbnails with a 12 dp corner, 16 dp from the bar's top and sides (concentric with its 28 dp corner) and 8 dp apart, scrolling sideways when many; the text then starts 8 dp under them. A thumbnail is a soft fill while its picture decodes. Its ✕ is an 18 dp glyph on a 24 dp dark scrim circle (white glyph, the same in both themes, so it reads on any photo), 4 dp in from the top end; its 48 dp target reaches past the picture's corner. The bar grows and shrinks with the standard motion (250 ms).

A one-line bar is about 91 dp tall. Decided (2026-11-12, D50): Think is a lightbulb icon inside the bar, not a chip outside it. The download's ring and percentage sit beside the model's name in the top bar, not by the new-chat button; the time left is in the ring's description and the notification.

### 8.9 Rows, groups and settings

A model offered to get (first run, Get models) is one `ModelCard` everywhere: the name in `title` with how it runs here at the end of the same line (the fit word in its ink), the plain line under it in muted `bodyMedium`, and the facts in `bodySmall` (size, Thinks, Sees images, On this phone, downloads). A row's title is `title` on every row and card, whether or not it has a subtitle (`bodyMedium` in muted ink). Trailing text sits on the padding edge, never mid-row; a row that opens a page ends in a 24 dp chevron. Settings, About and Storage are grouped cards: a 12 dp-cornered card per group, a 1 dp divider inset 16 dp between its rows, a chevron on rows that open a page, and a section label above each group, on the text of the rows under it (section 8.3, D77). Buttons are pills; a bordered one is outlined and 40 dp tall; a destructive one is drawn in the danger ink.

### 8.10 Chat details

A sent message's bubble has 16 dp horizontal and 12 dp vertical padding and ink text; a model reply in the open style has none. Inside a reply, blocks are 8 dp apart, list items 4 dp, and a heading has 16 dp above it; a list item's text hangs on a 32 dp indent (the marker right-aligned in a 24 dp column, 8 dp before the text, so "1." and "2." start their text at one edge), and a line under an item that starts no other block belongs to the item, as in CommonMark. A nested list hangs one indent (32 dp) deeper than its parent item, and its items are 4 dp apart like any others, while a list right after another one (a change of marker) keeps the 8 dp block gap; bullets are drawn rather than typed, 6 dp in the muted colour, a disc, then a 1 dp ring, then a square by depth, centred where the body font's "•" sits. Headings 1 and 2 take the `screenTitle` role and 3 to 6 the `title` role, with 16 dp above each, and are announced as headings. A quote is a 4 dp hairline-coloured bar with its blocks 12 dp after it in the muted colour; a nested quote draws its own bar inside. A horizontal rule is a 1 dp hairline across the column. A table sits in a 1 dp hairline frame with 12 dp corners: the header row in the `title` role on the code fill, the body in `body`, a hairline rule under every row but the last, cells 12 dp by 8 dp inside, each column as wide as its widest cell and aligned as the delimiter row says; cells don't wrap, and a table wider than the column scrolls sideways, like code. A code block is a 12 dp-cornered card with 16 dp inside. The empty chat's chips are `label` text in 32 dp chips 8 dp apart, centred under the hero. Banners sit on the gutter 8 dp above the message bar.

A photo in a sent message opens full-screen in the photo viewer, over the whole window, edge to edge. Its backdrop is black in both themes, opaque at rest so the chat doesn't show through, and the photo is fitted to the screen. A ✕ at the top end, a 24 dp white glyph on a 40 dp dark scrim circle in a 48 dp target, closes it, and so does back. Pinching zooms up to 4x about the fingers; a double tap zooms to 2.5x at the tap and the next one back to 1x; a zoomed photo pans only as far as its own edges, and at 1x not at all. A message with several photos swipes sideways between them, opening at the one tapped; a photo swiped past returns to 1x. At 1x a swipe down (or up) moves the photo with the finger and fades the backdrop; let go past a fifth of the screen's height, or flicked, it closes, otherwise it springs back with the standard motion. Each photo is announced as "Photo 2 of 3". The viewer's window has no dim of its own and light system bar icons in both themes. It decodes the full pictures, bounded by the screen's long side, off the main thread, the tapped one first, showing each as it lands; until then a page is the bare backdrop, and a picture that can't be decoded says "This photo can't be shown." Neither zooms, so swipes and the pull to close still work on them. A rotation reopens the photo in view, at 1x.

### 8.11 Motion

Material 3's durations and easings (D58), in `DemoMotion`; a screen never names a duration, and `demo/scripts/check.sh` (`ds`) fails on an animation spec in `:app`. What arrives takes 300 ms on the emphasized decelerate curve (fast, then settling); what leaves takes 150 ms on the emphasized accelerate curve, since the eye has stopped following it; a change in place (a fold, the bar growing, a colour) takes 250 ms on the standard curve. Nothing moves further than it needs to, and nothing bounces.

| What | Motion |
|---|---|
| A message, a day label, the reply starting | fades in (300 ms); rows below a row that changes height glide to their place (250 ms). A finished reply takes the streaming row's place without fading again. The rows of a chat that opens are there at once, and the list fades in once (300 ms) |
| The reply before its first word | three 6 dp muted dots, one line tall, in a wave (1.2 s, each a fifth later): while the model wakes and reads the prompt. A thinking model shows its fold instead |
| "Thinking" | a soft band of ink sweeps across the muted word (1.4 s, the wake line's loop) until the thought ends |
| The thought's chevron | turns a quarter as the fold opens (250 ms) |
| A note, a problem, the editing line | unfolds from its top and fades in (300 ms); folds away and fades (150 ms), still showing its words |
| Back to the latest | grows from 80% and fades in (300 ms), like Material's floating button; leaves in 150 ms |
| Send and stop | the icons cross-fade (200 ms); the fill (tonal to indigo) and glyph colours ease when there is something to send (250 ms) |

Tokens are not animated one by one: a reply arrives at reading speed, and per-token fades would make it shimmer. With animations off (the system's animator duration scale at 0) every transition is instant and the loops (wake sweep, dots, shimmer) hold still.

## 9. Limits and error lines

A limit is one plain line at the moment it matters, with the fix as a button; there is no nagging.

| Situation | Line | Action |
|---|---|---|
| The memory check doubted the model | "<Name> is using more memory than was free. Android may close apps in the background." | A note, not a wall (F128, D57): the SDK loaded the model at the smallest context and the reply goes on; it has a small ✕ to close it, the next message clears it anyway, and it shows on every load of such a model (each load can be closed again). Only a note can be closed: a line the chat waits on keeps its way out. **Smaller size** opens this model's own sheet on the Smaller size; Get then starts that file and moves the chat to it (D49, F107) |
| The model runs on another chip than the one chosen | "<Name> is running on the CPU: the GPU couldn't load it." (or "this phone has no NPU", "the NPU isn't available here") | A note, like the memory note: a ✕ closes it, the next message clears it, and it shows on every load that falls back (D72). **Settings** opens Settings › Memory. Automatic never shows it. When the memory note also applies, the memory note wins the line |
| Not enough memory to load | "<Name> needs more memory than this phone has free right now." | The load failed all the same: **Smaller size**, as above, with the question held, or **Try again** |
| Context full | "This chat is longer than <Name> can hold." | Start a new chat |
| A reply fails | "That didn't work." under the reply | Try again (regenerates it) |
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

`Markdown.parse` was measured on the phone with the hand-written parser; its replacement by commonmark-java (D74) was measured on the host only (`MarkdownStreamingTest`, four characters per append): a median of about 0.01 ms per append for a mixed reply, a 10 KB single list and a 10 KB single table, against the old parser's 0.02 ms for the mixed reply. The phone is not re-measured. The method, the budgets' exact definitions and what they do not cover are in [../testing.md](../testing.md#performance-budgets-perf). The design assumes these rules: stable or immutable parameters; lambdas instead of passed state where a value changes often; lazy list items with stable keys and content types; no parsing, formatting or decoding in composition; the streaming reply as its own flow, separate from the stored list; deltas coalesced to at most one update per frame; the database written at most every 250 ms during a stream; markdown re-parsing only the block the line being written can still join (in a list its last item, in a table its last row), with earlier blocks kept as they were; and a recomposition test per screen (while one reply streams only its row recomposes, and typing does not recompose the list). The real-model run on the phone met the chat benchmarks.

## 11. Accessibility

- **Touch targets** are at least 48 dp. The long-press area of a bubble or reply and a sheet's drag handle were found too small and now carry a 48 dp area, and the handle a label (F101).
- **Contrast** is 4.5:1 for text and 3:1 for large text and icons in both themes, asserted by a unit test; the status bar's icons follow the app's own theme choice rather than the phone's (F102), and the window background follows it too, so no strip shows behind the status bar.
- **Labels and semantics.** Every icon button has a description; the Think bulb's description names the level; a selected row exposes the `selected` semantic; a progress ring's description includes the time left; the Compose accessibility checks run on every screen test and, once a surface has settled, on every screen and sheet of the walk.
- **Text scale.** The screens were walked at font scales 1.3 and 2.0: text wraps, buttons wrap to two lines and nothing clips; a "Recommended" label stacks past 1.3.
- **Motion.** With animations off every transition is instant, and the wake hairline, the typing dots and the thinking shimmer are still (§8.11).
- **Layout direction.** Right-to-left was found by screenshot tests: bubbles are placed with start and end rather than fixed edges, back and chevron icons mirror, and code blocks stay left-to-right.
- **Large screens.** Content is capped at 640 dp and centred, with the gutter measured from the column's edge. Landscape and a tablet were walked: the screens hold, the header takes a third of the height in landscape and the lists scroll.
- **A secret field** is masked, never suggested or corrected, and ends with Done.

## 12. Open items and rejected directions

**Open.**

- The models do not appear to see an attached picture on the phone (F119). The app passes the right pixels and the SDK passes the same image case against upstream on the host and on the NPU; upstream on the host reads the picture correctly, so the fault is on the phone or in the app's use of it. No test asserted image understanding before.
- A small model can loop without end in its answer and the app has no guard (F113). **Done (D60):** the length limit and Continue, section 4.7.
- Sampling values have no Settings page, with a "reset to the model's default". **Done (D60):** section 4.7.
- The reasoning budget's forced end is untested on a small model.
- Voice dictation is the first feature planned after the app. **Decided (D76):** voice messages for models that hear, not dictation (section 4.4); built after the SDK's `Content.Audio` (W7).
- Not audited: a physical fold or tablet, and right-to-left on a device (the emulator ignored the system setting; it is covered by screenshot tests).

**Rejected directions**, so they do not return: a pastel orb with a serif greeting alone (reads as a meditation app); a dark "instrument" look, an editorial "paper" look and a chat-first minimal screen (soulless); a talking onboarding with a house metaphor (too much text); vivid album-art images per model (cheesy); a professional monogram list (lifeless); a violet accent (generic AI); the warm-paper and navy palette of PD (replaced by the neutral one, D69); a mid teal (juvenile); nicknames for models (D50).
