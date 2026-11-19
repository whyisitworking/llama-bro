# Experiments from the headline-features phase

Each folder holds the scripts, patches and notes of one experiment, run on the OnePlus 13 (and the Mac's CPU for the
byte-for-byte checks) at llama.cpp b12352, with b12479 where a note says so. Raw output (logs, audio) is not kept.

| Folder | Question | Findings and decisions |
|---|---|---|
| [delta-net](delta-net/) | Can the NPU run a hybrid model's prefill with MTP fast, and with what patch? (`speed-ab.sh`, `replay-ab.sh`; the head/tail split it replaced and the one-line gate kept as patch 0001; `upstream-notes.md` for the upstream proposal) | F8, F149, D80 |
| [load-cancel](load-cancel/) | Can a cancelled load stop reading weights? (`upstream-pr.md`: the problem, patch 0003 and its evidence, for an upstream proposal) | F145, F148, D67 |
| [cache-stop](cache-stop/) | Why does the prompt cache never reuse a hybrid-SWA model's state, and what fixes it? (`repro.sh`, `fix-master.patch`; patch 0004; `upstream-issue.md`, `upstream-pr.md`) | F153, D91 |
| [media-path](media-path/) | Is a chat's media faster as a `file://` link than as base64? (`media-ab.sh`, `results-2026-11-17.md`) | F157, D87 |
| [speech-output](speech-output/) | Can LFM3-Omni speak on the NPU, and how fast? (`tts-*.sh`, `stages-research.patch`; patch 0005; the dropped keep-flat and text-head patches, kept here; `upstream-issue-hexagon.md`) | F154, F155, F156, F165, D86, D94, D111 |
| [upstream](upstream/) | What changed upstream since the b12352 pin, and is it worth the upgrade? (`review-2026-11-17.md`) | D90, D93, F163 |

The upstream notes are facts, numbers and citations for whoever writes an issue or a pull request upstream; llama.cpp
asks for those to be written by a person, so none of them is meant to be pasted (see each note's header). Nothing here
has been posted.
