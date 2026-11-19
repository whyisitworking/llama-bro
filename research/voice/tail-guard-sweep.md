# Patch 0008's speech tail guard beyond one voice, one language and one quantisation (F173)

2026-11-19. Question: patch 0008 (D96) ends LFM3-Omni speech that runs past its text, at 38 near-silent frames in a row
(about 3 s) or past 5 frames per text token plus 13 (F167). Its thresholds came from 600 turns of one English question,
female voice, Q4_K_M. Do they cut clean replies, or miss runaways, with another voice, other languages or Q8_0?

## Method

Mac CPU, upstream `llama-server` at b12479 built with upstream's CMake and none of our patches (the same build as F167),
`-c 2048 -np 1 -dev none --no-mmproj-offload`, one fresh server and one `/v1/realtime` turn per `--seed` (13-112) with
`realtime-sweep.sh`, five sweeps side by side at `RT_THREADS=2` (Japanese seeds 67-112 at 8; seeds 104 and 112 again with
`RT_TIMEOUT=400` under another job's load, both runaways). Each session sends the
model card's trained prompt and an answering instruction (F166), the instruction in the reply's language:

| Sweep | Model | Voice, language | Question (macOS `say`, 16 kHz via `afconvert`) |
|---|---|---|---|
| male-en | Q4_K_M | male, en | F167's lighthouse question (7.0 s) |
| q8-female-en | `ggml-org/LFM3-Omni-2B-GGUF:Q8_0` | female, en | the same |
| female-de | Q4_K_M | female, de | the question in German, voice Anna (7.8 s) |
| female-es | Q4_K_M | female, es | the question in Spanish, voice Mónica |
| female-ja | Q4_K_M | female, ja | the question in Japanese, voice Kyoko (8.2 s) |

Prompt: `Respond with interleaved text and audio.` followed by "You are a helpful voice assistant. Answer the user's
spoken question in your own words." (en), "Du bist ein hilfsbereiter Sprachassistent. Beantworte die gesprochene Frage
des Nutzers mit deinen eigenen Worten auf Deutsch." (de), "Eres un asistente de voz servicial. Responde a la pregunta
hablada del usuario con tus propias palabras, en español." (es), "あなたは親切な音声アシスタントです。ユーザーの音声による質問に、
自分の言葉で日本語で答えてください。" (ja). With the English instruction, German and Japanese read the question back.

Pilots (2 seeds each) chose the languages: Spanish answers with a story; German answers with one sentence ("Sure, I'll
tell you a short story...") and stops; French and Chinese read the question back; Japanese reads it back and runs away.
German and Spanish are the two languages; Japanese is added because its readback runs away often.

`tail-guard-sim.py` replays the guard over each recorded turn (one audio delta is one 80 ms frame, one text delta one
token: the model's tokenizer gives the same count or one or two fewer for every caption). `tail-guard-check.py` asks an
independent listener, whisper.cpp `ggml-small` (multilingual), what each stopped reply says whole and up to the stop,
and how many caption words the stop removes; `--all` screens every reply. A runaway is a reply over 80 s of audio, as in
F167; a stop below that is either a failure that ended by itself or a clean reply cut early, and the listener tells them
apart.

```sh
SRV=path/to/upstream/build/bin/llama-server
RT_THREADS=2 RT_VOICE=male RT_LANG=en research/voice/realtime-sweep.sh $SRV en.wav out/male-en 18911 13 112 "$INS_EN"
RT_MODEL=~/Downloads/llamabro-test/LFM3-Omni-2B-Q8_0.gguf RT_VOICE=female RT_LANG=en research/voice/realtime-sweep.sh ...
python3 research/voice/tail-guard-sim.py --stats out/*            # the table below
python3 research/voice/tail-guard-sim.py out/female-es            # each stop, where and which rule
python3 research/voice/tail-guard-check.py es:out/female-es       # what each stop removes
python3 research/voice/tail-guard-check.py --all en:out/male-en   # every reply's caption coverage
```

## Result

| Sweep | Replies | Runaways (stopped) | Other replies stopped | What those stops remove (listener) | Other replies: median (p10-p90) audio | Unstopped replies' closest approach: length / quiet |
|---|---|---|---|---|---|---|
| F167, en female Q4_K_M | 600 | 6 (6) | 4 | failures ending by themselves (F167) | 25-37 s | 0.91 / 37 frames |
| male-en | 100 | 0 | 0 | - | 24.3 s (21.0-27.5) | 0.78 / 29 |
| q8-female-en | 100 | 0 | 2 | seed 93 loops ("That night, that night, ...") and is stopped at 21.8 s of 41.0; seed 88 garbles its last sentence, the stop removes it (2.6 s) | 21.7 s (18.9-27.1) | 0.85 / 34 |
| female-de | 100 | 0 | 4 (quiet rule) | 0.5-7.2 s after 3 s of near-silence: in seeds 104 and 33 a trailing "bitte" the caption ends with, otherwise noise | 10.2 s (8.9-12.4) | 0.97 / 28 |
| female-es | 100 | 11 (11) | 9 | 7 remove at most one caption word (noise, or whisper's "Subtítulos por Amara.org" on silence); seed 94 two, in a garbled 45 s tail; seed 55 loops on a phrase, recovers and is stopped 2.2 s before its last 4 words | 20.2 s (14.5-33.4) | 0.98 / 25 |
| female-ja | 100 | 49 (49) | 49 (48 length, 1 quiet) | every reply reads back the misheard question (43 characters, 27 tokens), and the 8 the listener checked are unintelligible whole and before the stop | 22.1 s (13.2-38.5) | 0.78 / 9 (the 2 unstopped) |

- **English, both voices, both quantisations:** no clean reply is cut. Every unstopped reply says its caption (listener
  coverage 93-100%, at most 0.44 s of audio per word, speech to the end of the audio), so nothing is missed either. The
  male voice keeps the most headroom (78% of the length limit, 29 quiet frames). Q8_0 has no runaway in 100 and two
  replies that lose their place near the end, both stopped with at most their garbled last sentence.
- **Spanish runs away seven times as often** as English (11 of 100 against 1.6%), and the guard stops all 11, ten of
  them at 16-58 s of 87-148 s. Seed 14 is stopped late, at 115 s: its caption itself loops (181 words repeating "El
  farero la recupera...") so the length limit is far, and its speech goes to loud garble at about 40 s, so the quiet
  rule waits; a reply-length cap is what bounds such a case (D95). The 9 other stops land on speech that has already gone
  wrong; the most caption they remove is seed 55's last 4 words after a loop.
- **German** replies are one sentence (median 18 words, 10 s), never run away; the guard stops 4 on the quiet rule after 3 s
  of near-silence, and two of them (seeds 104, 33) then say a last "bitte" that the stop removes: one caption word. The speech is barely intelligible ("Sicker, dicker, diller, einer, kurser
  Geschickte..." for "Sicher, ich erzähle dir eine kurze Geschichte...").
- **Japanese** replies all read the question back, in speech the listener could not follow in any of the 8 it
  checked ("ドラマイン、アー、エイトゥ..."). 49 of 100 run away and
  are all stopped at 8.7-11.9 s; of the other 51, 49 are stopped (48 by the length rule at 11.9 s, one by the quiet rule)
  and two short ones (5.1 s, 9.3 s) end by themselves. 11.9 s is
  1.45 times the 8.2 s macOS takes to say the question, so a fluent reading would fit. There is no clean
  Japanese reply to protect in this mode.

Looser lengths only move the trade-off: at 6 frames per token, Spanish has 6 other stops instead of 9, but F167's
failures 41 and 200 are no longer stopped and 21 is stopped later. With no clean reply cut early, beyond German's last "bitte" after a 3 s pause, the thresholds stay.

Side result: in the captioned realtime mode, LFM3-Omni-2B (Q4_K_M) speaks English and Spanish intelligibly, and German,
French, Japanese and Chinese barely or not at all, whatever the guard does.

Raw replies, audio and logs are not kept.
