# Voice-mode experiments

Scripts for LFM3-Omni-2B's realtime conversation (`/v1/realtime`), run against upstream `llama-server` on the Mac's CPU
and through the device tools on the OnePlus 13's NPU. Recordings and transcripts are not kept.

| File | What it does | Findings and decisions |
|---|---|---|
| `realtime-turn.py` | One turn over a WebSocket: session setup, a spoken question in 100 ms chunks, commit, the reply's text and speech | F166, F168 |
| `realtime-sweep.sh` | One turn per seed across many seeds, for a voice, language, model and thread count | F167, F173 |
| `tail-guard-sim.py` | Replays patch 0008's guard over recorded turns: which runaways it stops, which clean replies it cuts | F167, F173, D96 |
| `tail-guard-check.py` | Transcribes what a stop removed, to tell an early cut from a caught failure | F173 |
| `tail-guard-sweep.md` | The guard's results on another voice, Q8_0 and three more languages | F173 |
| `phone-step4.sh`, `phone-turns.py` | The same turns on the phone's NPU: latency, speed, cancels, the second slot's cost | F170, F171, D99 |
