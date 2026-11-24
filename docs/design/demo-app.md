# The demo app: design reference

This is the consolidated design of the demo chat app in [`demo/`](../../demo/README.md): who it is for, what it does, how it looks and the rules that keep it consistent. Decisions are cited as D<n> ([decisions](../decisions.md)) and findings as F<n> ([findings](../research/findings.md)). How the app is verified is in [../testing.md](../testing.md).

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

- First run as the Models page's empty state: suggested models that run well here, a plain "What's a model?", and a chat that is usable before the download ends (D130).
- Models: one screen for what is downloading, what is on the phone and what to get: search over Hugging Face, a fixed list of suggestions, and a model sheet that follows the model's state, with plain sizes (Basic) or every quantisation (Advanced), each shown with how it runs on this phone before anything is downloaded.
- Downloads that continue in the background, survive process death and resume where they stopped, verified by hash (D40, D47).
- Chat with streaming replies, markdown and code blocks, a folded thought ("Thought for 8 s"), Think levels, image input for models that can see, voice messages for models that hear (D76), stop, edit, regenerate, copy, share, and a divider when the model changes mid-chat.
- Custom instructions applied to every chat, a default model, an optional Hugging Face token for gated models, and storage management.
- A choice of chip in Settings › Memory (Automatic, NPU, GPU or CPU), with where the model actually runs and why when that isn't the chip chosen (D72).
- Two layouts, Sidebar and Chat list, and light, dark and system themes, both themes first-class.
- Voice mode for a model that talks (D95, D100): a spoken conversation, hold to talk, with captions, saved as an ordinary chat (section 4.8).
- Plain limit lines with one action each: not enough memory, context full, missing model, a failed or gated download.

**Not in the app:** dictation (speech turned into text) and text to speech outside voice mode (D37, D76, D100), web search, tools or agents, side-by-side model comparison, accounts, cloud sync, exporting chats, and maintained lists such as "new this week" or "most loved".

**Failures regardless of function:** a design language that feels lifeless and corporate, cheesy, juvenile, or like a meditation app (each was tried and rejected, section 12), and exposed machinery that a newcomer must understand before chatting.

## 3. Constraints and architecture

- The app uses only the SDK's public API (`LlamaBro`, `Conversation`, `llamabro-hub`). A need the SDK lacks is first a finding and then an SDK change, never a workaround: the two that arose were starting a `Conversation` from stored messages (F96) and reading the memory budget for fit labels (F97).
- Search is the SDK's (D68, replacing D42's rule): `hub.search` finds GGUF models, grouping a model's quantized uploads
  under its original, and `hub.files` lists a repository's quantisations with their sizes. The app names the rows
  (section 5), merges results that share a name into one row, and makes its own fit verdict from the files' sizes and
  the phone's total RAM (section 7.1, D101); the SDK makes none.
- A model loads by its name, `llama.loadModel(hub.model(key))`, whose installed copy loads with no network (D64). The
  app never starts a download the person didn't ask for: it loads only what the library lists, and a load of a model
  deleted meanwhile fails rather than fetching it.
- Loading takes seconds and cannot be made faster (D30, F58); the interface masks it (section 4.1). Memory is tight on real phones: a load can be refused with `InsufficientMemory`, and the app explains that in plain words and offers what to do, never crashing (F90).
- Layers: UI, view models exposing `StateFlow`s derived with `combine` and `stateIn`, repositories, the SDK. Only genuine input is a mutable flow. Manual wiring through an application container, no dependency injection framework; the container also builds the screens' view models and implements `ui.Screens`, the small interface screens take instead of the container (D144). Room holds chats and messages (text, image references, thinking text, the model used, times and timings), DataStore holds settings, images are copied into app-private storage, and the Hugging Face token lives under a Keystore key and is sent only to `huggingface.co`.
- Chats are stored by the app and sent to the model through the SDK's `Conversation`; editing, regenerating, switching models and reopening a chat rebuild the `Conversation` from the stored messages (F96).
- Speculative decoding stays at the SDK default, which is off on every chip (F67, F68): the app downloads no draft models and offers no switch.
- The model loads with the chip choice's `DevicePolicy` (D72) and the context length's `ContextSize` (`Auto`, or `UpTo(n)`, D78); the host keys a load on both (`LoadConfig`), so a model loaded under another config is not reused.
- Background downloads are the SDK's worker (`llamabro-hub-work`, D64, D75): `DemoApp` registers `LlamaBroWorkerFactory`
  over the container's hub, so a download uses the app's token and its progress is the hub's own, and the notifications
  speak the app's words through the SDK's override (`ModelNotifications`). The app must hold its notification permission
  for a force-stopped download to resume as a foreground service (F92).
- Package `com.suhel.llamabro.demo`, API 29 and up, arm64-v8a only. No network except Hugging Face; no analytics.

### 3.1 Packages and the layering rule

The code is in packages, bottom to top; a package imports only those below it, and `ui` and the root import any
(D144). A `LayeringTest` reads every main source file's package and import lines (and fully qualified names) and fails on an
edge this list forbids, on any domain package that names `ui` or the design system, and on a package this table lacks.

| Package | What it holds | May import |
|---|---|---|
| `data.settings` | The plain types the settings persist: `Knobs`, `CardSampling`, `LoadConfig`, `SearchFilters` and its parts, `Theme` | none |
| `data` (with `data.db`) | Room, the settings, token, image and audio stores, `ChatRepository` | `data.settings` |
| `models` | The catalog, the hub and its cache, downloads, the library, fit; `ModelInUse`, which the chat's model host implements | `data`, `data.settings` |
| `audio` | The microphone and its capture, `VoiceRecorder`, `VoicePlayer`, `SpeechOutput`, WAV, pause detection | `data`, `data.settings` |
| `chat` | `ChatEngine`, `ModelHost`, history, limits, sampling, `Think`, the reply draft, `BackgroundRelease` | `data`, `models`, `audio` |
| `voice` | Voice mode (`VoiceConversation`), `Dictation`, `ReadAloud`, captions, transcripts, `Steps` | `chat`, `audio`, `models`, `data` |
| `ui` | Screens and view models by feature (`ui.chat`, `ui.models`, `ui.settings`, `ui.shell`), `Screens`, the Markdown parser; domain enums (`Theme`, `Think`, `VoiceStage`) are mapped to the design system's at this edge | all, and `designsystem` |
| root | `DemoApp`, `MainActivity`, `ReplyService`, `AppContainer`, `ContainerFactory`: the Android entry points and the composition root | all |

The design system (`:demo:designsystem`) knows no domain type, and no domain package knows it.

## 4. The experience

First run is no longer a screen of its own: it is the Models page's empty state, in §4.3 (D130). §4.1 is left
unused so that the other sections keep their numbers.

### 4.2 Two layouts

Chosen in Settings, Appearance, each shown as a small preview. Sidebar is the default. Switching applies at once with a short crossfade, keeps the open chat, and leaves chats, models and settings the same. The bubbles belong to Chat list and the open page to Sidebar; there is no separate message-style switch. Search covers chat titles and message text. There are no unread counts, ticks or presence indicators, because a local model is always there.

**Sidebar.**

- Home is a new chat. The drawer (D126, "Quiet list") reads top to bottom:
  - **Head**: the app's name, "Llama Bro", in the serif (`sidebarTitle`, 22 sp Regular) on a 24 dp inset, with New chat (Material's `add_comment`, in the ink) as a 48 dp icon at its end.
  - **Search**: "Search chats", 16 dp from the sides and 12 dp under the name. It filters the history by title and message text and says "No chats match." when nothing does.
  - **Chats grouped by date** (Today, This week, Earlier). A group's label is quiet (13 sp, soft, sentence case), 24 dp under the group above (8 dp under the search for the first) and close on its rows.
  - **A chat** is one line, at least 48 dp tall: the title (15 sp Regular, `rowLine`, ellipsized) and the chat's time at the end (12 sp, soft: the time today, the weekday this week, else the date). The row is 12 dp inside and 12 dp from the drawer's edges, so the labels and the titles start on one 24 dp line. The open chat lies on the soft accent fill with 12 dp corners, no outline and no tick. Long press opens Rename and Delete.
  - **No chats at all**: the head, the search and the foot stay, and "No chats yet. Start one." stands where the list would be.
  - **Foot**, under a hairline: two labelled 48 dp rows, Models (its icon, "Models", and "N on this phone" soft at the end) and Settings.
  - **Screen reader**: each place is one button, "Models, 2 on this phone" (or "Models" before the library is read) and "Settings". The drawer is one traversal group with the head and the foot first, so they are reached before the chats.
- A new chat opens with the default model: the last model used, unless one was pinned with "Use for new chats". An empty chat shows the small orb, "What's on your mind?" and starter chips.
- Tapping the model's name in the top bar opens the model switcher: the models on the phone as the Models page's rows
  (§4.3), "Current" on the chat's model and "Default" on the one for new chats as line 2's small soft mark, the fit as
  its symbol, an info action beside the symbol that opens the model's card, and **Get more models** at the end. A tap
  switches the chat's model; a long press sets "Use for new chats". A new chat with no model chosen is titled "Choose a
  model". Switching mid-chat keeps the conversation and adds a quiet "Switched to <Name>" divider.
- Sent messages are in a soft neutral bubble on the right; the model writes on the page, full width, with its small actions under the reply.

**Chat list.**

- Home is the list of chats, newest first, under a sans "Chats" title and a search box. A row shows an avatar with the model's initial, the title, the time or day, and the last message prefixed by its sender.
- New chat is a floating button. Bottom tabs hold Chats, Models and Settings.
- A conversation screen has a back arrow, the avatar, the title and the model's name under it (tap to switch). Both sides are in bubbles, the model's on a raised surface; the time sits in each bubble's corner, with the speed on the model's, and quiet day labels separate days. Back from a chat reached after a layout switch goes to the list.

Thinking folds, code, images, edit, regenerate, share, copy and the model divider work the same in both layouts.

### 4.3 Finding models and the library

One **Models** page (D116, D130) is the only place for models. It opens from the sidebar's Models row (D126), the
chat list's Models tab and the switcher's "Get more models", and a fresh install starts on it. Design:
[models-page.md](models-page.md) and, for search's paging and filters, [models-search.md](models-search.md); the
departures made while building are D117-D129. It shows what the phone already knows from its first frame (Room), and
a fresh answer from Hugging Face changes words in place, never the layout (F177).

- **A fresh install** (first run). While first run hasn't been seen and nothing is on the phone, the app starts on
  Models, in either layout. One muted line stands over the field, "Pick a model to talk to. It runs on this phone,
  private and offline.", with **What's a model?**: two plain lines and the five fit symbols with their words. The first
  Get then starts the download and opens a new chat on that model at once, and first run is seen; searching doesn't
  mark it seen, and every later Get stays on the page. In that chat the download is a ring with a percentage beside the
  model's name in the top bar (D50); the first message can be typed now and is held until the model is ready ("<Name>
  will answer the moment it's here"); leaving the app keeps the download going, with a notification. While the model
  loads, a fine accent line under the top bar fills with the SDK's load progress (a sweep before the first report, no
  spinner and no number, still with animations off); then the held message is answered.
- **Browse**, top to bottom: the title "Models" (light, with Back when something is under it), the intro on a fresh
  install, the search field, then **Downloading**, **On this phone** (its note is the storage, "6.1 GB · 120 GB free";
  none with no models) and **Suggested for this phone**: the catalog's models (Qwen3.5 0.8B, Gemma 3 4B, Qwen3.5 4B,
  Llama 3.2 3B, Qwen3.5 9B, LFM3 Omni 2B) that run great or well here and aren't taken, best first; when none does, one
  line says the phone is short on memory and names the smallest. Labels are quiet and in sentence case. An empty group
  is hidden. A failed remove or cancel is one plain line under the field, closed by its own OK.
- **Search** starts the moment the field is focused, before any typing, in place on the same list: the intro folds
  away, recent searches show as chips while the field is empty, and browse's own groups stay under the same labels,
  narrowed to the query as it is typed (every catalog model whose name matches, whatever its fit). After two characters
  and 350 ms, Hugging Face is asked; its rows come under **From Hugging Face**, a page at a time in the Hub's order,
  hiding gated repos, sized to fit this phone by default (F176), and the next page loads near the end (D127). The
  field's own bottom edge is the one loading line. A failure, offline or no match is one plain line under that label
  with its action inline, the local matches staying above it; a next page that fails says "Couldn't load more." with
  Try again. The field's filter action opens a plain sheet of sort, size and "Sees images", applied at once; a dot on
  it, and one line under the label naming the changed filters with Reset, say when they aren't the defaults (D128).
  Back, or clearing and leaving the field, returns to browse at the list's top. Results are models, not
  repositories: uploads of one model collapse into one row, and a Hub row for a model listed above is left out.
- **One row** (`ModelRow`, D123) for every model: the name (16 sp Regular, in the ink, never the first thing cut) over
  one muted line, "maker · size" and the capability icons (light outline, soft, no words: Thinks, Talks, Sees images,
  Writes code), then, quietest, a Hub row's downloads ("· 1.2M downloads") and a state's small soft mark ("· Default",
  "· On phone"); the fit symbol is centred on the right. The size is what the download takes, the model file and its
  image reader, one size everywhere; a Hub row's is "about 3.4 GB", guessed from its parameter count (F175), until a
  card has read its files. A download adds a footer, the ring panel at row size (below). A model on the phone swipes to
  remove, after asking. A row moves with its state as one animated item (Suggested to Downloading to On this phone).
  At font scale 1.3 and up the fit symbol moves under the name and the icons and marks take their own line.
- **The fit symbol** (D116, D101): `sprint` runs great, `directions_run` runs well, `elderly` is slow here, a kneeling
  figure is too big, `block` can't chat. A 20 dp box, reserved, growing with the font to 32 dp; the words are its
  content description and long-press tooltip, and no Models row shows them as text. One function gives every fit
  (`Fits.quick`: the whole size for memory, the model file's active share for speed), so a model's row, switcher row
  and card agree, one fit per file (D121).
- **Can't chat**: a model with no chat template, or an embedding or reranking model, shows the no-entry symbol, no
  capability icons and its name in the ink. Its card says why in its state line and offers no Get.
- **The model card** opens from any row, the same sheet for every model, drawn once every local read has answered so
  its first frame is final (D118). It opens at its compact height (560 dp, or the screen's) whatever its content, and
  grows to the status bar's edge only when scrolled or pulled up, or when "Another size" needs the room (D119, D121).
  Top to bottom, each part in the same place in every state:
  - the name in the serif (24 sp Regular), and one quiet line: the maker, the capability icons and the fit symbol;
  - the state line, muted: "Not on this phone", "On this phone · Q4_K_M · 3.4 GB" or "Not for chat: this is an
    embedding model"; none while a download runs, as the foot says it;
  - the size choice, Basic or Advanced, when getting is possible. **Basic**: **Smaller**, **Recommended** (preselected)
    and **Best quality**, each with its quant and size under the title ("Q4_K_M · 3.4 GB"), its fit symbol and a radio
    mark; Recommended is Q4_K_M where it exists, else UD-Q4_K_XL, else the largest Q4; Smaller is the largest file under
    75% of Recommended; Best quality is Q8_0, else Q6_K, else the largest; a tier the upload lacks stays, disabled, "Not
    in this upload". **Advanced**: every quant in the sheet's one scroll, its fit column titled "Runs here",
    "Recommended" and "On phone" as words under the quant, and the image reader as one flat row, "Included". A size on
    the phone shows its own size and can't be picked. Offline, a catalog model's own file is its Recommended tier and
    the others say "Needs the network", with one notice and Try again;
  - after a larger gap, the model's page: a quiet "About", then the original model's Hugging Face page (the base
    model's `README.md`, else the repository's own) without its front matter, muted and small, its headings soft, its
    note boxes, badge rows and link-only lines left out, and its source at the end ("Source: owner/repo"). Images are
    dropped; tables span the width and scroll sideways. Nothing shows while it is unknown;
  - the pinned foot, one button tall in every state, the page scrolling under it: **Get** naming the file ("Get Q4_K_M
    · 3.4 GB · about 8 min"); during a download, the **ring panel**: the progress as a ring around Pause (Resume when
    paused, an hourglass while waiting, Try again when stopped), two lines ("Downloading · Q4_K_M" over "0.9 of 3.4 GB
    · 4 min left"; "Stopped" in the danger ink over the reason and the bytes kept) and a quiet ✕ that asks first past
    300 MB; on the phone, **Start a chat** and the bordered **Another size**, which unfolds the choice with one line
    ("Q4_K_M is already on this phone.") and Done. A foot that changes kind fades through. At large font the foot grows
    rather than cut.
  - Labels name the quant, never a tier: Smaller, Recommended and Best quality are only Basic's titles. Every
    progress figure is one string in the total's unit ("0.9 of 3.4 GB", "35 of 740 MB").
- **Motion** (D119, D120): words that change in place fade through (out, then in), never crossfade; lists move exit,
  then place, then enter; a part that comes or goes folds with its height; screens fade through; every closing action
  slides the sheet down first. All instant with animations off.

### 4.4 Chat

- Messages with markdown, parsed as CommonMark with the GitHub tables and strikethrough (D74): paragraphs, headings of every level, bold, italic and strikethrough with `*` or `_`, inline code, fenced and indented code with a copy button, nested ordered and bulleted lists, nested quotes, tables, horizontal rules, links and bare URLs, and backslash escapes. An image shows its alt text, HTML shows as typed, and maths is not rendered (it shows as typed). Only http, https and mailto links open; any other target shows as plain text. Copy, and stop. A stopped reply is kept and marked "Stopped". A reply that reaches its length limit is kept too: as the chat's last reply it shows "Stopped at the length limit." with **Continue**, which goes on writing the same reply; once the chat has moved on it is only marked (section 4.7).
- **Images:** the **+** button attaches a photo. The button stays visible for a model that cannot see images; using it shows the plain limit line that says so. Tapping a photo in a sent message opens it full-screen, where it zooms and pans, swipes to the message's other photos, and closes with a swipe down, the ✕ or back (section 8.10).
- **Voice messages** (D76), for a model that hears: with the text field empty, the mic in Send's slot records; the recording is sent as audio, so the model hears the words and the tone, with nothing transcribed. Photos already in the bar go with it. In the chat it is a sent-side bubble with play and pause, a waveform and its length, and no Edit. A recording stops at 2 minutes (about 1,500 tokens, F152) and, when the screen is left or a call comes, waits in the bar for ✕ or ↑. A chat that moves to a model that can't hear sends a placeholder for each voice message, with a limit line offering a model that hears. Recordings are app-private and deleted with their chat.
- **Dictation** (D140): a tap on the mic dictates, a hold records a voice message (a long-press buzz; both named for
  TalkBack); a muted line by the mic, "Hold for a voice message", shows until either is first used. While dictating
  the text area shows the words as they come over a strip of levels and the time, with ✕ in the + slot and ✓ in
  Send's; ✓ writes the words after what was typed. The flow is upstream's web client's (live text, a piece at each
  pause, or pieces over `transcribe`, then one pass over the whole recording). Settings › Voice sets its language.
- **Read aloud** (D141): on a model that speaks, a typed reply's speaker action reads it in the chosen voice; a second
  tap stops it.
- **Edit and regenerate:** edit a sent message to cut the chat back to it and send again, or ask for a new answer. Copy, share and regenerate show under the latest reply only.
- **Share a reply** as a tidy image card with the reply, the model's name and its speed.
- **Speed and the rest of what was measured:** behind the info icon among a reply's actions, in a "Reply details" sheet: the model, the chip it ran on, the speed ("38 words/s · 14.2 tokens/s", words being tokens times 0.75), the reply's and the prompt's tokens (with what came from the cache), how fast the prompt was read, how long it thought, and how many MTP drafts were kept. Each line shows only when it's known. The actions themselves (copy, share, regenerate, details) are 18 dp glyphs in 48 dp targets.
- **Scrolling:** the list keeps a streaming reply's newest text in view while the reader is at its end. Scrolling up, even within a reply taller than the screen, stops that and shows a **back to the latest message** button; the button, scrolling back to the very end, or sending a message follows the reply again.
- **Custom instructions** (Settings) become the system prompt of every chat; with none, no system prompt is sent.

### 4.5 Settings

Appearance (Layout with previews; Theme: System, Light, Dark), Custom instructions, Default model, a Hugging Face token (optional, for gated models; a masked field that is never suggested or corrected), Sampling (section 4.7), Voice (section 4.8), Memory (section 4.6), Storage (the library) and About (versions and licences). Settings, About and Storage are grouped cards (section 8).

### 4.6 The model's memory

One model is in memory at a time, owned by the app, not by a chat: the first message loads it, a switch replaces it, and a chat that is left keeps it for the next (D56, D59). A model is never closed while a reply streams from it: each turn holds the model until it ends, so a message sent in another chat on another model waits behind the wake line until that reply ends, then its model replaces the one in memory and answers; nothing has to be sent again. Stop ends the wait; the model still changes once that reply ends. Until then Settings › Memory and the model sheet still show the model in memory. The person sees it and decides:

- **In memory now.** Settings › Memory, and the chat's model sheet under "In memory", show the loaded model ("Qwen3.5 4B", "2.8 GB · NPU · 16k context": the weights, the chip, and the context the SDK picked, in whole k rounded down; the context's memory comes on top of the size) with **Unload**. In Settings one line under it says, for both choices below: "Changing the chip or the context length reloads the model." While a reply is being written the row says so instead: a reply is never cut off. Nothing loaded shows no row in the sheet, and "Nothing loaded" in Settings.
- **Runs on.** Automatic (the default: "The fastest chip that can run the model", the SDK's `Auto`), NPU, GPU or CPU, each `Prefer(kind)`, so a chip that can't load the model falls back instead of failing (D72). A kind this phone doesn't have stays listed as "Not on this phone, so the fastest chip it has runs the model". Under the choice: "Running on the CPU: the GPU couldn't load it." when the model was loaded for this choice and isn't on that chip; "Moves to the CPU after this reply." (or "… with the next message.") while the model in memory was loaded for an earlier choice (no line when it already runs on the chip chosen). A change frees a model loaded for another choice once no reply holds it, and the next message loads it on the new chip; a message sent while another reply is still being written shares the model in use. Fit and speed estimates use the chosen chip when the phone has it.
- **Context length.** How much of the chat the model sees at once (D78).
  - **Choices**: Automatic (the default: "The most that fits in memory, up to 16k", the SDK's `Auto`), 4k, 8k, 16k or 32k, each `UpTo(n)`. `UpTo(n)` is a ceiling the SDK still fits to free memory (and to the model's training context), never below 4k, so a choice never fails a load.
  - **Subtitles** give each in words ("About 3,000 words; the least memory" … "About 24,000 words, for long documents; the most memory").
  - **Under the choice**, while the model in memory was loaded at another length: "Reloads at 8k after this reply." (or "… with the next message."; for Automatic "Reloads at the most that fits …"). Otherwise: "How much of the chat the model sees at once. Each size is the most it gets: with less memory free, it gets less, down to 4k."
  - **A change reloads exactly as a chip change does** (D72): it frees a model loaded under another config once no reply holds it, a message sent beside another reply shares the model in use, and a chip or context change replaces the other's pending wait (the choice itself is always saved).
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
- **The length limit.** Every reply has one unless the person removes it, its thinking and answer together. Settings › Sampling › **Reply length** sets it for every model and chat, with no per-chat override (D78).

  | Choice | Limit |
  |---|---|
  | **Automatic** (the default) | About 2,000 tokens for the answer plus the Think level's budget (table in section 7.2) |
  | **Short** | 1,024 tokens |
  | **Long** | 8,192 tokens |
  | **No limit** | "Until the model stops or the context is full": no `max_tokens` is sent |

  - Short and Long replace the level's total and leave its thinking budget as it is, so a Short reply at Balanced or Deep can stop while it still thinks.
  - Note under the group: "For every model and chat. A reply that reaches its length stops, and Continue goes on, unless the chat has filled the context."
  - A small model that loops ends at the limit instead of running until Stop (F113); No limit gives that guard up on purpose.
  - **At the limit** the reply is kept with "Stopped at the length limit." and **Continue** sends the chat with that reply last, so llama-server goes on writing it, from inside its thinking if it was cut there (F137). It can be cut and continued again.
  - **When the chat filled the context** (prompt and reply reach the loaded context) there is no room to continue: the reply ends failed with "This chat is longer than {model} can hold." and **Start a new chat**, keeping what it wrote.

### 4.8 Voice mode

A spoken conversation with a model that talks (D95, D100). The previous context of the chat is not given to the spoken conversation: upstream's realtime conversation takes the person's text but not the model's earlier replies, so each time voice mode opens (and after leaving the screen), the model starts the conversation afresh, while the chat keeps every turn. A chat that already has messages says so quietly above the words: "Voice starts fresh: it doesn't see the messages above." The typed chat afterwards sees every voice turn, since it is rebuilt from the stored messages. The voice mode holds the model as a reply does, so it is never freed under a conversation; leaving the screen lets it go.

- **Getting there.** For a model that speaks, an empty message bar puts the voice button (Material's `graphic_eq`) in Send's slot, filled with the accent (D125), and the voice-message mic (D76) sits just before it. Typing turns the slot back into Send. The app learns that a model speaks from `ModelInfo.voices` when it loads and remembers that for its next load, as it does for hearing.
- **The voice view.** Full screen over the chat. A top bar with a close at the start and the model's name and voice centred (`titleSmall` over `bodySmall`). The orb at the voice view's size in the upper middle, with one muted `label` line under it: "Tap or hold to talk", "Listening", "Thinking" or "Speaking". Under that, the person's last words in muted `bodyMedium`, then the reply's caption in `voiceCaption`, its older lines muted and fading at the top. At the bottom, the talk button (an 80 dp tonal circle with the mic glyph), centred, and while the model speaks a 48 dp tonal Stop at its start. A hint under the controls says how to talk now: "Tap to start, or hold while you talk", "Tap to send", "Release to send" or "Talk to interrupt".
- **Talking.** Tap or hold: a tap starts a turn that the next tap sends ("Tap to send"), and a press held past 400 ms (Material's long-press timeout) sends on release ("Release to send"). The button fills with the accent while it listens. Pressing it while the model speaks stops the reply and listens ("Talk to interrupt"). There is no hands-free mode: upstream's realtime conversation has no turn detection at b12479 (`turn_detection` is always null), and the maintainer chose not to add one by a patch or in the app (2026-11-19). Stop ends the reply. Close ends voice mode and returns to the chat.
- **The orb in each state.** Ready: still. Listening: it swells with the voice's level and a hairline ring spreads from it. Thinking (about 0.3 s, F170): a soft highlight turns inside it. Speaking: it moves with the reply's speech. With animations off it holds still in every state, and the state line carries the meaning.
- **What is saved.** Each turn is an ordinary message: the person's as a voice bubble (D76) with its caption under it in muted `bodyMedium` when captions are on, the reply as its text with a speaker action among its actions that replays its speech. The chat goes on by typing.
- **Settings.** Settings › Voice: the voice, from the model's list (the first by default), and captions of the person's words (on by default; they load the model with two slots, D99). The spoken language follows the phone's when the model has it, else English. The built-in answering instruction comes first, the custom instructions after it (F166).
- **Leaving.** Home, a call or the lock stops recording and playback as Stop does and closes the session; the view is ready to talk again on return. Playback goes to the speaker or the connected headset; the mic uses the voice-communication source, with the phone's echo cancellation.

## 5. Model naming

A model has one name, its own, on every surface that names it: Models, the model card, the switcher, Default model, the chat's top bar, the dividers and the notifications ("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B"). Another file of the same repository in another size keeps the name; its size says which. The library lists one entry per downloaded file. Decided (2026-11-12, D50).

The suggested models (the catalog also pins file sizes and a maker; the plain line each once carried is gone, since D116 shows the model's own page instead):

| Model | Thinks | Sees images | Talks |
| --- | --- | --- | --- |
| Qwen3.5 0.8B | yes | yes | no |
| Gemma 3 4B | no | yes | no |
| Qwen3.5 4B | yes | yes | no |
| Llama 3.2 3B | no | no | no |
| Qwen3.5 9B | yes | yes | no |
| LFM3 Omni 2B | no | no | yes |

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
- Progress shows on the model's row in Models wherever the download was started (a thin bar and the percentage, or Queued, or Paused), under Downloading (megabytes done of total, speed, time left, Pause and cancel), in the chat's top bar beside the model's name, and as a notification outside the app. Cancel deletes the partial; Pause keeps it.
- The estimated time on a button is the size divided by 8 MB/s, rounded up to minutes ("about 3 min", "under a minute"); the real download measured about 10 MB/s on Wi-Fi (F93).
- A failed download becomes a plain line with an action (section 9); saving a token restarts the downloads refused for want of one, also after the app was restarted (the refusal is remembered).

## 7. Fit labels and Think levels

### 7.1 Fit

Every model, size and file is shown with one fit word, coloured, never a card tint.

- **Memory** is a formula, the same everywhere: lists, the model card for every size and quant, the switcher (D101). A model may use half the phone's total RAM, read once from the SDK's CPU device (`devices()`, its `memoryTotal`); against that budget the app sets the model file plus its encoder (mmproj), what is downloaded and later held in memory. Nothing reads free memory and nothing reads GGUF headers over the network, so a model's label doesn't move between two looks. The encoder's size comes from the catalog for a model it knows and otherwise from the Hub's listing (`Quantization.encoderBytes`, the encoder the Hub fetches with that quant). The catalog's suggestions ship with their sizes, so the first screen needs no network.
- **Context room** is the headroom left in the budget after the model and its encoder: at least 1.5 GiB is large, at least 0.5 GiB normal, anything above zero small, none when they do not fit.
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

Calm, classy, a little alive. Depth and restraint over softness or loudness. A cool grey near-white in light mode and a near-black in dark mode, with no hue (D69, D125). Colour is kept for meaning and for one living moment, the orb. A classic serif, Source Serif 4, for hero moments, a model card's name and the sidebar's title; Geist, a clean sans, everywhere else (D125). Soft shadows and generous but not floaty spacing. Motion only where it earns its place, quiet and quick (§8.11): sheets, the download ring, the wake-up hairline, the thinking fold, and in the chat what arrives, leaves or changes in place.

### 8.2 Type

Material 3 roles, a screen naming a role and never a size. Each role carries size, line height, weight, tracking and face; every line height is a multiple of 4, but `replyBody`'s, chosen by eye (D125). The sans is Geist (SIL OFL, bundled as a variable font, `res/font/geist.ttf`, at weights 300, 400 and 500), the serif Source Serif 4, and code the system monospace (D125).

| Role | Size / line | Weight | Face | Use |
|---|---|---|---|---|
| `displayHero` | 28 / 36 | Medium | Serif | the empty-chat line |
| `sheetTitle` | 22 / 28 | Medium | Serif | a sheet's title |
| `cardTitle` | 24 / 32 | Regular | Serif | a model card's name (D123) |
| `sidebarTitle` | 22 / 28 | Regular | Serif | "Llama Bro" at the head of the sidebar (D126) |
| `screenTitle` | 22 / 28 | Medium | Geist | a top bar's title, "Chats" |
| `screenTitleLight` | 24 / 32 | Light | Geist | the Models page's title (D123) |
| `title` | 16 / 24 | Medium | Geist | a row's or card's title |
| `titleRegular` | 16 / 24 | Regular | Geist | a model row's name, a quiet button's label (D123) |
| `titleSmall` | 14 / 20 | Medium | Geist | the model's name under a top bar, a group heading inside a card |
| `body` | 16 / 24 | Regular | Geist | the user's messages, descriptions, field text |
| `replyBody` | 16 / 26 | Regular | Geist | a reply's running text (D125) |
| `rowLine` | 15 / 20 | Regular | Geist | a one-line row of a long list, the sidebar's chats and places (D126) |
| `bodyMedium` | 14 / 20 | Regular | Geist | a row's subtitle, a card's description, a state line |
| `quietLabel` | 13 / 20 | Regular | Geist | a quiet sentence-case section label, a model page's headings and "About" (D123) |
| `pageBody` | 13 / 24 | Regular | Geist | a model's page under its card (D123) |
| `bodySmall` | 12 / 16 | Regular | Geist | a reply's meta line, a row's second line, captions |
| `label` | 14 / 20 | Medium | Geist | a button's or chip's text |
| `labelSmall` | 12 / 16 | Medium | Geist | an upper-case section label, a status pill |
| `voiceCaption` | 22 / 28 | Regular | Geist | the reply's caption in voice mode, read at arm's length (D100) |
| `code` | 14 / 20 | Regular | Monospace | code blocks and inline code |

The serif is for the hero roles only (the empty-chat line and a sheet's title), a model card's name (D123) and the sidebar's title (D126); every screen title, row and label is Geist. There is no role below 12 sp. Body text is 16 sp.

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

Layout rules for gutters, gaps and section labels:

- **Gutter**: 16 dp everywhere, measured from the edge of a content column that is at most 640 dp wide and centred (Material's cap for a sheet), so a tablet does not stretch lines. Content with no left-aligned text (a segmented switch) keeps the gutter.
- **Proximity**: related things are closer than unrelated ones, and the gap around a group exceeds the gap inside it.
- **Section label**:
  - It belongs to what follows it: 4 dp above its first row on a screen (8 dp on a sheet), with the previous section's last row 24 dp above the label.
  - It is inset by the 16 dp padding of the cards and rows under it, on every screen and every sheet, so it starts where their text starts, not on the gutter where their surface starts (D77). Where rows reach the edge themselves, as the drawer's chats do, that inset alone puts it on their text.
  - A label with an action is the action's 48 dp tall, the label centred on the button's line, like Material's list subheader.
  - A muted note under a section's cards (where the model runs, what a change reloads) is a `SectionNote`, inset the same way.
- **Touch targets** are a real 48 dp of layout, never an overlay that reaches past its row, since a parent's bounds cut such an overlay and accessibility services see only what's left (F130). The thinking fold is a 48 dp row for the same reason, and its height above and below the line is the gap to the reply.
- **A reply's footer**: the meta line sits 4 dp under the reply, and the action row (48 dp tall, a touch target) 8 dp under that.

### 8.4 Layout primitives

Screens and sheets are assembled from primitives that own the gutter and the gaps and that take content, never padding: `Screen`, `Sheet`, `Section`, `Stack` and `Inline` (gaps from the named steps only), with `BottomBar`, `MessageColumn`, `Centered`, `ScrollStack` and `LazyStack` for specific jobs. A sheet is the 48 dp handle area, then content on the gutter, ending 16 dp above the bottom inset with no dead space; its primary action is a 48 dp accent button (D117, D125). A plain sheet whose content grows after it opens re-expands so that its content stays on screen (F118); an expandable sheet, a model's card, is its compact height (560 dp, or the screen's) from its first frame and grows to the status bar's edge only when scrolled or pulled up, or when a part the person opens needs the room (D119, D121).

### 8.5 Controls and shapes

Every size a component uses comes from `DemoSize` (heights, widths, icon sizes, strokes, elevations), every gap from `DemoSpacing` and every corner from `DemoShapes`; outside `theme/` a component has no dp literal, and `demo/scripts/check.sh` (`ds`) fails the build on one. Icons come in two sizes: 24 dp for actions and leading icons, 18 dp for a mark inside text or on a picture (a chevron, a check, a remove cross); the message bar's buttons are the one exception, a 20 dp glyph in a 36 dp circle inside a 48 dp target (D73).

| Part | Size |
|---|---|
| List row, one / two / three lines | 56 / 72 / 88 dp; a dense one-line row (the drawer's chats) 48 dp (D77) |
| Row's leading icon / avatar | 24 / 40 dp |
| Top app bar | 64 dp |
| Navigation bar | 80 dp with labels; the selected tab's indicator is a 64 × 32 dp pill |
| Button, primary or quiet, bordered or not | 48 dp, one height (D117) |
| Search bar and text field | 56 dp |
| Chip | 32 dp |
| Status pill (a download's state outside the Models page; tonal, D117) | 20 dp pill, 8 dp each side |
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

The palette is a cool grey light theme and a dimmed dark one (the values in the table, from `Tokens.kt`). One primary, monochrome (D69, D125): the **accent**, a quiet grey a step off the ink. Primary buttons (Get, Start a chat, Save, New chat), the floating button, the message bar's send, stop and Think on, the mic and voice buttons, every selected outline, progress and the selected navigation item are the accent, and what sits on them is the on-accent colour (near-white in light, near-black in dark). With no hue to tell states apart, a second signal does it: links and unbordered text actions ("Unload", "Reset to the model's defaults") are underlined. D73's indigo send accent is gone (D125). The fit words and Remove keep their colours and are the only hues that carry meaning, besides the orb. Contrast is 4.5:1 for every text pair and 3:1 for large text (18 sp, or 14 sp bold) and icons, in both themes, checked by a unit test.

A scrim (behind the drawer, behind a sheet) is the theme's darkest token: the canvas at 50% in dark, the ink at 30% in light. Never the ink in dark, which would lay a light veil over a dark page (F131).

New chat is a speech bubble with a plus (Material's `add_comment`), the sign messaging apps use for a new conversation; the pencil means edit (a message, a chat's name) and nothing else.

| Token | Light | Dark |
|---|---|---|
| Canvas | `#F2F2F5` | `#0B0B0C` |
| Surface (cards and fields are their fill alone, D122) | `#FBFBFC` | `#161618` |
| Ink | `#1D1D1F` | `#E3E3E6` |
| Reply (a reply's running text; its headings and bold are the ink) | `#2C2C2E` | `#CFCFD4` |
| Muted text | `#65656A` | `#98989D` |
| Soft text (the least important text, capability icons, D123) | `#6E6E73` | `#808085` |
| Hairline (dividers; cards and fields have no edge, D122) | `#E3E3E8` | `#242426` |
| User bubble | `#E6E6EB` | `#222224` |
| Primary (accent; every strong action and selected outline, D125) | `#3A3A3D` | `#C8C8CD` |
| On accent | `#FBFBFC` | `#161618` |
| Accent soft | `#E8E8ED` | `#28282B` |
| Fit: runs great (text) | `#3E5641` | `#A9C3AA` |
| Fit: slow here (text) | `#73572F` | `#D3B588` |
| Fit: too big (text; no dimmer than muted, D117) | `#5A5A5F` | `#AAAAB0` |
| Danger ("Remove") | `#A23B2F` | `#E59A90` |

The orb is the app's one spark of colour: a radial gradient from amber `#FDB969` at its lit point (34% across, 28% down) to rose `#C35F5A` at the edge, the same in both themes, with a soft rose-tinted shadow. It is about 56 dp on the first screen and 40 dp in an empty chat. It takes the warm end of the icon; the icon's violet stays in the icon. Everything else is neutral so that the orb and the fit words are the only colour on a screen (D69, D125).

The launcher icon is adaptive: the llama on a flat night background `#02011E`, scaled into the 66 dp safe zone, with a one-colour themed layer traced from the llama's outline. The splash shows the icon on the canvas, so no other colour flashes before the first screen.

### 8.7 States

Every state is one signal:

- **Selected** is the one selection outline (D125), 2 dp in the accent on the chosen thing's own shape: a card that is one choice among cards (a size or quant, a layout) and a segmented switch's chosen segment, which is also raised. A row that is one choice inside a grouped list (the Think levels, the default model) has an ink check at its end; the open chat in the drawer lies on the soft accent fill (D126); the switcher's current model is marked "Current", not outlined. Nothing else means selected; an "included" file is not outlined. A selected row exposes the `selected` semantic.
- **Fit** is a coloured word, never a card tint; on the Models page, its card and the model switcher it is a symbol in its colour (D116). **Recommended** is a label inside the card. **Disabled** is ink at 38% on a surface at 12%, still readable as a label.
- Every string a person reads starts with a capital ("Used today", "Paused"); a count or time that begins with a figure is left as it is.

### 8.8 The message bar

The text over a row of buttons, in one outlined surface (1 dp hairline, 28 dp corner). The text (`body`) starts 16 dp from the edge and 16 dp below the top, and grows to six lines; it capitalises the start of each sentence. The buttons' circles sit 14 dp under it (8 dp of padding and the 6 dp each circle leaves in its target). The row holds three 36 dp circles with 20 dp glyphs (D73) in 48 dp targets, 10 dp from the bar's bottom and sides so that they are concentric with the corner (18 + 10 = 28). The targets stay where the 40 dp circles had them, 4 dp from the bar's edge, so each circle's centre stays 28 dp in and the bar's height is unchanged; the circles' gaps grow by the 4 dp they lost (12 dp apart, 14 dp under the text, where 40 dp circles were 8 and 12):

- **+** at the start, tonal (the hairline's fill, an ink glyph).
- At the end, the **Think** lightbulb (only for a model that can think; tonal when off, filled with the accent when on) and **send**, 12 dp apart.
- **Send** is a tonal circle with a muted glyph that does nothing while there is no text and no photo, filled with the accent (§8.6, D125) when it can act, and a stop button on the same fill while a reply is coming (D49). The on-accent glyph is 11:1 on it in both themes. For a model that hears, an empty text field shows the **mic** in its place instead (D76); with photos attached and no text, the mic sits just before Send, 12 dp apart like Think, so photos alone can still be sent. The mic appears once the model has said it hears on a load, and the app remembers that for its next load.

**Recording** (D76). A tap on the mic turns the text area into a live waveform of the input level with a timer; **✕** takes the + slot and discards, **↑** takes Send's slot and sends. The timer turns ink in the last 10 s before the 2-minute stop. Starting and stopping are announced, and with animations off the waveform holds still.

Attached pictures sit inside the bar, above the text, never above the bar where they would read as already sent: 64 dp thumbnails with a 12 dp corner, 16 dp from the bar's top and sides (concentric with its 28 dp corner) and 8 dp apart, scrolling sideways when many; the text then starts 8 dp under them. A thumbnail is a soft fill while its picture decodes. Its ✕ is an 18 dp glyph on a 24 dp dark scrim circle (white glyph, the same in both themes, so it reads on any photo), 4 dp in from the top end; its 48 dp target reaches past the picture's corner. The bar grows and shrinks with the standard motion (250 ms).

A one-line bar is about 91 dp tall. Decided (2026-11-12, D50): Think is a lightbulb icon inside the bar, not a chip outside it. The download's ring and percentage sit beside the model's name in the top bar, not by the new-chat button; the time left is in the ring's description and the notification.

### 8.9 Rows, groups and settings

A model is one `ModelRow` everywhere it is listed (the Models page, the model switcher; D123, §4.3): the name in `titleRegular` over one muted `bodySmall` line, "maker · size" with the soft capability icons and marks, and the fit symbol centred on the right. Any other row's title is `title`, whether or not it has a subtitle (`bodyMedium` in muted ink). Trailing text sits on the padding edge, never mid-row; a row that opens a page ends in a 24 dp chevron. Settings, About and Storage are grouped cards: a 12 dp-cornered card per group, a 1 dp divider inset 16 dp between its rows, a chevron on rows that open a page, and a section label above each group, on the text of the rows under it (section 8.3, D77). Buttons are pills, all 48 dp tall (D117); a bordered one is outlined by the hairline; a destructive one is drawn in the danger ink.

### 8.10 Chat details

Spacing in chat uses the `DemoSpacing` tokens (section 8.3), so `DemoSpacing.kt` stays the one source of the values:

- **Bubble and reply**:
  - A sent message's bubble has `item` padding sideways and `compact` vertically, with ink text; a model reply in the open style has none.
  - Inside a reply, blocks are `block` apart, list items `inline`, and a heading has `item` above it.
- **Lists**:
  - An item's text hangs on a 32 dp indent: the marker is right-aligned in a 24 dp column, 8 dp before the text, so "1." and "2." start their text at one edge.
  - A line under an item that starts no other block belongs to the item, as in CommonMark.
  - A nested list hangs one indent (32 dp) deeper than its parent item, and its items are `inline` apart like any others; a list right after another one (a change of marker) keeps the `block` gap.
  - Bullets are drawn rather than typed, 6 dp in the muted colour: a disc, then a 1 dp ring, then a square by depth, centred where the body font's "•" sits.
- **Headings**: 1 and 2 take the `screenTitle` role and 3 to 6 the `title` role, with `item` above each, and are announced as headings.
- **Quote and rule**: a quote is a 4 dp hairline-coloured bar with its blocks 12 dp (`compact`) after it, in the muted colour; a nested quote draws its own bar inside. A horizontal rule is a 1 dp hairline across the column.
- **Table**:
  - A 1 dp hairline frame with 12 dp corners; the header row in the `title` role on the code fill, the body in `body`, a hairline rule under every row but the last.
  - Cells are 12 dp by 8 dp inside, each column as wide as its widest cell and aligned as the delimiter row says.
  - Cells don't wrap; a table wider than the column scrolls sideways, like code.
- **Code block**: a 12 dp-cornered card with `item` padding inside.
- **Empty chat**: the chips are `label` text in 32 dp chips `block` apart, centred under the hero.
- **Banners** sit on the gutter, `block` above the message bar.

A photo in a sent message opens full-screen in the photo viewer, over the whole window, edge to edge.

| Aspect | Behaviour |
|---|---|
| Backdrop | Black in both themes, opaque at rest so the chat doesn't show through; the photo is fitted to the screen |
| Close | A ✕ at the top end (a 24 dp white glyph on a 40 dp dark scrim circle in a 48 dp target), or back |
| Zoom | Pinch up to 4x about the fingers; a double tap zooms to 2.5x at the tap and the next one back to 1x |
| Pan | Only as far as the photo's own edges, and at 1x not at all |
| Several photos | A sideways swipe moves between them, opening at the one tapped; a photo swiped past returns to 1x |
| Pull to close | At 1x a swipe down (or up) moves the photo with the finger and fades the backdrop; let go past a fifth of the screen's height, or flicked, it closes, otherwise it springs back with the standard motion |
| Accessibility | Each photo is announced as "Photo 2 of 3" |
| Window | No dim of its own; light system bar icons in both themes |
| Loading | Full pictures, bounded by the screen's long side, decoded off the main thread, the tapped one first, each shown as it lands; until then a page is the bare backdrop |
| Undecodable picture | "This photo can't be shown."; it does not zoom, so swipes and the pull to close still work |
| Rotation | Reopens the photo in view, at 1x |

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
| Send and stop | the icons cross-fade (200 ms); the fill (tonal to the accent) and glyph colours ease when there is something to send (250 ms) |

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

`Markdown.parse` was measured on the phone with the hand-written parser. Its replacement by commonmark-java (D74) was measured on the host only (`MarkdownStreamingTest`, four characters per append), and the phone is not re-measured:

- A median of about 0.01 ms per append for a mixed reply, against the old parser's 0.02 ms.
- A list or table being written is parsed again whole on each append, about 0.3 ms for a 10 KB table (D108).

The method, the budgets' exact definitions and what they do not cover are in [../testing.md](../testing.md#performance-budgets-perf). The design assumes these rules:

- Stable or immutable parameters; lambdas instead of passed state where a value changes often.
- Lazy list items with stable keys and content types.
- No parsing, formatting or decoding in composition.
- The streaming reply as its own flow, separate from the stored list; deltas coalesced to at most one update per frame; the database written at most every 250 ms during a stream.
- Markdown re-parsing only the top-level block the line being written can still join, with earlier blocks kept as they were.
- A recomposition test per screen: while one reply streams only its row recomposes, and typing does not recompose the list.

The real-model run on the phone met the chat benchmarks.

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
