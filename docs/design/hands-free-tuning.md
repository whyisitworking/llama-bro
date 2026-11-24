# Hands-free voice mode: tuning on the phone

Status: hand-over note, 2026-11-24, for the owner. The decision is D147 in [../decisions.md](../decisions.md#d147); the
voice view is [demo-app.md](demo-app.md) §4.8. Nothing here has run on a phone yet: the numbers are first guesses taken
from upstream's dictation detectors (D140), and the host tests only check that the rules hold, not that they feel right.

## What to set up

- A debug build of the demo on the phone, LFM3-Omni-2B loaded (the model that talks), captions on as usual.
- Settings › Voice › Talking: **Hands-free**. Leave **Interrupt by talking** off for the first round.
- About ten minutes, attended; no model inference beyond a handful of short turns (D100's "short and attended").

## Round 1: hands-free turns (interrupt off)

Try each, with the phone on the table at arm's length, then held close:

1. A short answer ("Yes.", "Hello.") in a quiet room. It should be sent about a second after you stop.
2. Two sentences with a natural breath between them. The breath must not send the first sentence on its own.
3. A sentence with a thinking pause in it ("I'd like a story about... a lighthouse."). Note how long a pause you can make
   before it sends: about 0.9 s is the design.
4. Silence for half a minute with a fan, a TV at low volume or street noise. Nothing should be sent, and the orb's
   level may move but the voice view should stay on "Listening".
5. The same noise, then talk over it. The turn should start and end as in a quiet room.
6. Tap the talk button in the middle of a sentence: it should send at once. Tap while nothing has been said: it should
   stop listening ("Tap or hold to talk"), and the next tap listens again.
7. Answer the instant the reply ends ("Yes." right on its last word). The turn should start and keep the "Y": the new
   listener starts from the room's floor the last one heard, so its first block of speech counts.
8. From "Tap or hold to talk", hold the button and say a quiet "yes", wait two seconds, release. It should be sent:
   while the button is held a pause doesn't end the turn.
9. Cough, knock or clap a few times with nothing said: no turn is sent. After three of those in a row, or two minutes
   of nothing, it should go back to "Tap or hold to talk" with the microphone off (the status bar's mic dot goes).
10. Press Home and come back: hands-free listens again by itself. (Tap to talk waits for a press, as before.)

Listen for (in the saved voice bubbles, which replay what was sent):

- The first syllable cut off: the start is late or too little is kept before it.
- A turn that ends inside a sentence: the end pause is too short, or the room is so loud that quiet never comes.
- A turn that waits too long after you stop: the end pause is too long.
- Turns sent on noise alone, or the orb jumping on a door, a cough or a keyboard: the start is too easy.
- Quiet speakers or a far phone never starting a turn: the start is too hard.

## Round 2: interrupt by talking

Switch **Interrupt by talking** on, close and reopen voice mode (settings apply when it opens), ask for something long
("Tell me a story."), and:

0. Where the reply plays. With the switch on, replies play as a call's voice, so the phone's echo canceller has them
   as its reference; on Android 12 and later voice mode moves the call's voice to the loudspeaker when no headset is in.
   Check that the reply comes from the loudspeaker (not the earpiece) at a normal volume, that the volume keys change
   it, and that with headphones it plays in them. On Android 10 and 11 replies play as before (the assistant's), and
   only the app's own echo guard keeps the reply from stopping itself.
1. On the loudspeaker, say nothing while it speaks. The reply must play to its end, loud passages included. If it
   stops itself, the echo is getting through: raise the margin or the warm-up below. Turning the volume up in the
   middle of a reply is the known weak spot (the guard learnt the echo at the old volume).
2. On the loudspeaker, talk over it at a normal voice. It should stop within about half a second and take your turn.
3. The same with wired or Bluetooth headphones: it should never stop itself, and should stop at once for you.
4. In Tap to talk with interrupt on: talking over a reply stops it, and that turn ends at a pause on its own.

5. While a reply plays with interrupt on, a phone call or another app taking the audio should end the listening, as
   before; the reply starting must not (the microphone and the speaker share one focus now, for the whole session).

Listen for a reply that stops on its first words (the warm-up is too short), one you have to shout over (the margin is
too high), and a turn that starts with the reply's own last words in it (the echo got into the kept audio: only the
interrupting speech and 0.1 s before it are kept).

## What to change

In `demo/app/src/main/kotlin/com/suhel/llamabro/demo/audio/Pauses.kt` (`TurnDetector`, `EchoGuard`) and
`demo/app/src/main/kotlin/com/suhel/llamabro/demo/voice/HandsFreeEar.kt` (`HandsFreeEar`), in their companions:

| Constant | Now | Raise it when | Lower it when |
|---|---|---|---|
| `TurnDetector.START_SPEECH_MS` | 250 ms | noise bursts start turns | short words ("Yes.") are missed |
| `TurnDetector.END_PAUSE_MS` | 900 ms | turns end inside sentences | it waits too long after you stop |
| `TurnDetector.MAX_TURN_MS` | 60 s (90 s hard cut) | long monologues are cut | (rarely) |
| `TurnDetector.INTERRUPT_SPEECH_MS` | 400 ms | a word of echo interrupts | interrupting feels slow |
| `EchoGuard.ECHO_MARGIN_DB` | 10 dB over the echo | the reply stops itself on the speaker | you must shout to interrupt |
| `EchoGuard.ECHO_WARM_UP_MS` | 600 ms of audible playback | the reply stops on its first words | early interrupts are missed |
| `EchoGuard.ECHO_TAIL_MS` | 300 ms | the reply stops itself just after a loud word | interrupting in its pauses is missed |
| `EchoGuard.MAX_COUPLING_DB` | 0 dB | (only with no echo cancellation at all) | talking during the warm-up blocks interrupts |
| `HandsFreeEar.MIN_SPEECH_MS` | 300 ms | coughs and knocks are sent as turns | short answers are dropped as noise |
| `HandsFreeEar.MAX_DISCARDS` | 3 in a row | it gives up in a room that is only briefly noisy | it keeps listening to noise too long |
| `HandsFreeEar.IDLE_MS` | 2 min | it stops listening while you think | the microphone stays on too long unused |
| `HandsFreeEar.BEFORE_MILLIS` | 700 ms | first syllables are cut | the kept audio carries noise |
| `HandsFreeEar.ONSET_BLOCKS` | 2 blocks (0.1 s) | an interruption's first syllable is cut | it carries the reply's last word |
| `SpeechDetector.ABOVE_FLOOR_DB` | 9 dB | noise starts turns (this also moves dictation's pieces) | quiet speakers are missed |

`BEFORE_MILLIS` also bounds what an interruption keeps: it is cut to the interrupting speech plus `ONSET_BLOCKS`, but
never more than `BEFORE_MILLIS`, so raising `INTERRUPT_SPEECH_MS` past it cuts the interruption's first words.

Change one at a time, by a step of about a third (for example 900 to 1,200 ms), and keep the host tests passing
(`PausesTest`, `VoiceConversationTest`); a measured change is worth a finding (`F<n>`) cited next to the constant.
`SpeechDetector.ABOVE_FLOOR_DB` is upstream's number and dictation's too, so prefer the turn constants first.
