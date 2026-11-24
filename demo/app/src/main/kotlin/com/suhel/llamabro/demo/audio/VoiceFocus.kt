package com.suhel.llamabro.demo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
     * microphone: asked for while talking interrupts a reply, and held only when the call's voice reaches the
     * loudspeaker or a headset.
     */
    val communication: Boolean

    /** Takes the focus, for replies played as a call's voice when [communication]; taking it again does nothing. */
    fun take(communication: Boolean)

    /**
     * For a new microphone: the focus, asked for again when it was lost since it was taken (another app took it for
     * good, or for a while that is over); false when it can't be had now.
     */
    fun reclaim(): Boolean

    /** Gives it back; giving it back again, or before it was taken, does nothing. */
    fun release()

    /** No focus at all: the host tests, and the phone tests' scripted audio. */
    object None : SessionFocus {
        override val lost: Boolean = false
        override val communication: Boolean = false

        override fun take(communication: Boolean) = Unit

        override fun reclaim(): Boolean = true

        override fun release() = Unit
    }
}

/**
 * [SessionFocus] on the phone's [AudioManager]: transient exclusive focus, as the microphone held it for a turn. Asked
 * for a call's voice on Android 12 and later, the phone is put in communication mode (so its echo cancellation takes
 * the reply as its reference) and the call's voice goes to a headset when one is in, else to the loudspeaker, again as
 * headsets come and go; the mode before is restored on release. When that routing can't be made, or before Android
 * 12, replies play as the assistant's, as before, and the mode is left alone.
 */
class VoiceFocus(context: Context) : SessionFocus {
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile override var lost: Boolean = false
        private set

    @Volatile override var communication: Boolean = false
        private set

    private var request: AudioFocusRequest? = null
    private var asked = false
    private var modeBefore: Int? = null
    private var routed = false
    private var devices: AudioDeviceCallback? = null

    @Synchronized
    override fun take(communication: Boolean) {
        if (request != null && !lost) return
        // lost for good, the old request is spent: ask again
        request?.let { audio.abandonAudioFocusRequest(it) }
        asked = communication
        val focus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes(communication))
                .setOnAudioFocusChangeListener { change -> lost = change < 0 }
                .build()
        request = focus
        lost = audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!lost && communication && modeBefore == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startCalls()
        }
    }

    @Synchronized
    override fun reclaim(): Boolean {
        if (request == null) return false
        if (lost) take(asked)
        return !lost
    }

    @Synchronized
    override fun release() {
        val focus = request ?: return
        request = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) endCalls()
        audio.abandonAudioFocusRequest(focus)
        lost = false
    }

    /** Communication mode and the call's voice routed; undone at once when the routing can't be made. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startCalls() {
        modeBefore = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
        if (!communication) {
            endCalls()
            return
        }
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = reroute()

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = reroute()
            }
        devices = callback
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Synchronized
    private fun reroute() {
        if (modeBefore != null) route()
    }

    /** The call's voice to a headset when one is in, else to the loudspeaker; [communication] says whether it went. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun route() {
        val headsets =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )
        val available = audio.availableCommunicationDevices
        val device =
            available.firstOrNull { it.type in headsets }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        routed = device != null && audio.setCommunicationDevice(device)
        val current = audio.communicationDevice
        communication = routed || (current != null && current.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun endCalls() {
        devices?.let { audio.unregisterAudioDeviceCallback(it) }
        devices = null
        if (routed) audio.clearCommunicationDevice()
        routed = false
        modeBefore?.let { audio.mode = it }
        modeBefore = null
        communication = false
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
