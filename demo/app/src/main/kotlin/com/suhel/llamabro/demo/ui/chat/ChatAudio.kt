package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.demo.audio.SpeechOutput
import com.suhel.llamabro.demo.audio.VoicePlayer
import com.suhel.llamabro.demo.audio.VoiceRecorder
import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.voice.Dictation
import com.suhel.llamabro.demo.voice.VoiceConversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What a chat's audio features take from the app, in one place: a chat built without it (most tests) has no
 * microphone and no speaker, so no voice messages, voice mode, dictation or read-aloud. A member that is null leaves
 * only its own feature out.
 */
class ChatAudio(
    /** Records voice messages (D76). */
    val recorder: VoiceRecorder? = null,
    /** Plays the chat's voice messages (D76). */
    val player: VoicePlayer = VoicePlayer.None,
    /** Starts voice mode in a chat (D100). */
    val voiceModeStart: ((VoiceChat) -> VoiceConversation)? = null,
    /** Starts a dictation over a held model in a language (D140). */
    val dictationStart: ((Model, String?) -> Dictation)? = null,
    /** The language dictation listens for, as Settings › Voice says (null: the model tells). */
    val dictationLanguage: suspend () -> String? = { null },
    /** Whether the mic's one-time hint still shows, and its "seen" (D140). */
    val micHint: Flow<Boolean> = flowOf(false),
    val onMicHintSeen: suspend () -> Unit = {},
    /** Where read-aloud speech plays, at a rate (D141). */
    val speech: ((rate: Int) -> SpeechOutput)? = null,
    /** The voice chosen in Settings › Voice, for read-aloud. */
    val chosenVoice: suspend () -> String? = { null },
)
