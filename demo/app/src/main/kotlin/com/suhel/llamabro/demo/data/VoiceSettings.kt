package com.suhel.llamabro.demo.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * How a voice-mode turn ends (Settings › Voice, D147): [TapToTalk] when the talk button says so (a second tap, or the
 * release of a hold); [HandsFree] also on its own, at a pause after speech, and voice mode listens again after each
 * reply.
 */
enum class VoiceTurns { TapToTalk, HandsFree }

/** Settings › Voice as stored (D100, D140, D147), held in the app's settings as [AppSettings.voice]. */
data class VoicePrefs(
    /** Voice mode's voice: its name, or null for the model's first. */
    val name: String? = null,
    /** Voice mode captions what the person says (D99, D100); it then loads the model with two slots. */
    val captionYou: Boolean = true,
    /** How a voice-mode turn ends (D147). */
    val turns: VoiceTurns = VoiceTurns.TapToTalk,
    /** Talking over a spoken reply stops it and starts a turn (D147). */
    val interruptByTalking: Boolean = false,
    /** The language dictation listens for (D140), an ISO 639-1 code; null lets the model tell. */
    val dictationLanguage: String? = null,
    /** The person has dictated or recorded once, so the mic's "hold for a voice message" line is gone (D140). */
    val seenMicHint: Boolean = false,
)

/** Settings › Voice's choices, each a single edit of the app's settings [store] ([SettingsRepository.voice]). */
class VoiceSettings(private val store: DataStore<Preferences>) {
    suspend fun setVoice(voice: String?) {
        store.edit { if (voice == null) it.remove(VOICE) else it[VOICE] = voice }
    }

    suspend fun setCaptionYou(caption: Boolean) {
        store.edit { it[CAPTION_YOU] = caption }
    }

    suspend fun setVoiceTurns(turns: VoiceTurns) {
        store.edit { it[VOICE_TURNS] = turns.name }
    }

    suspend fun setInterruptByTalking(interrupt: Boolean) {
        store.edit { it[INTERRUPT_BY_TALKING] = interrupt }
    }

    suspend fun setDictationLanguage(language: String?) {
        store.edit { if (language == null) it.remove(DICTATION_LANGUAGE) else it[DICTATION_LANGUAGE] = language }
    }

    suspend fun setSeenMicHint() {
        store.edit { it[SEEN_MIC_HINT] = true }
    }

    internal companion object {
        private val VOICE = stringPreferencesKey("voice")
        private val CAPTION_YOU = booleanPreferencesKey("caption_you")
        private val DICTATION_LANGUAGE = stringPreferencesKey("dictation_language")
        private val VOICE_TURNS = stringPreferencesKey("voice_turns")
        private val INTERRUPT_BY_TALKING = booleanPreferencesKey("interrupt_by_talking")
        private val SEEN_MIC_HINT = booleanPreferencesKey("seen_mic_hint")

        /** Settings › Voice as [p] stores it. */
        fun prefsOf(p: Preferences): VoicePrefs =
            VoicePrefs(
                name = p[VOICE],
                captionYou = p[CAPTION_YOU] ?: true,
                turns = VoiceTurns.entries.firstOrNull { it.name == p[VOICE_TURNS] } ?: VoiceTurns.TapToTalk,
                interruptByTalking = p[INTERRUPT_BY_TALKING] ?: false,
                dictationLanguage = p[DICTATION_LANGUAGE],
                seenMicHint = p[SEEN_MIC_HINT] ?: false,
            )
    }
}
