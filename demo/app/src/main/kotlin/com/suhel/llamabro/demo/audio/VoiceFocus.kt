package com.suhel.llamabro.demo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Voice mode's one claim on the phone's audio (D147), taken as its session opens and given back as it ends. The
 * reply's speaker and the microphone under it ask for none of their own, so neither takes focus from the other: the
 * microphone ends on a loss to something else (a call, another app recording), never on a reply starting.
 */
interface SessionFocus {
    /** The focus has gone to something else since it was taken; a microphone under it ends. */
    val lost: Boolean

    /**
     * Replies play as a call's voice, the playback the phone's echo cancellation takes back out of the voice-call
     * microphone: asked for while talking interrupts a reply.
     */
    val communication: Boolean

    /** Takes the focus, for replies played as a call's voice when [communication]; taking it again does nothing. */
    fun take(communication: Boolean)

    /** Gives it back; giving it back again, or before it was taken, does nothing. */
    fun release()

    /** No focus at all: the host tests, and the phone tests' scripted audio. */
    object None : SessionFocus {
        override val lost: Boolean = false
        override val communication: Boolean = false

        override fun take(communication: Boolean) = Unit

        override fun release() = Unit
    }
}

/**
 * [SessionFocus] on the phone's [AudioManager]: transient exclusive focus, as the microphone held it for a turn. Played
 * as a call's voice, a reply would go to the earpiece, so on Android 12 and later the call's device is set to the
 * loudspeaker while the focus is held, unless a headset is in; before 12 replies play as before, as the assistant's.
 */
class VoiceFocus(context: Context) : SessionFocus {
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile override var lost: Boolean = false
        private set

    @Volatile override var communication: Boolean = false
        private set

    private var request: AudioFocusRequest? = null
    private var routed = false

    override fun take(communication: Boolean) {
        if (request != null) return
        val asCall = communication && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val focus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes(asCall))
                .setOnAudioFocusChangeListener { change -> lost = change < 0 }
                .build()
        request = focus
        lost = audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        this.communication = asCall
        if (asCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) routed = loudspeakerForCalls()
    }

    override fun release() {
        val focus = request ?: return
        request = null
        if (routed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        routed = false
        communication = false
        audio.abandonAudioFocusRequest(focus)
        lost = false
    }

    /** The loudspeaker for the call's voice, when it would go to the earpiece; false when nothing was changed. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun loudspeakerForCalls(): Boolean {
        if (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return false
        val speaker =
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return false
        return audio.setCommunicationDevice(speaker)
    }

    companion object {
        /** A reply as the assistant's speech, or as a call's voice. */
        fun attributes(communication: Boolean): AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(
                    if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT,
                ).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
