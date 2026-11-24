package com.suhel.llamabro

import androidx.annotation.CheckResult
import com.suhel.llamabro.audio.SpeechAudio
import com.suhel.llamabro.audio.SpeechConfig
import com.suhel.llamabro.internal.ErrorMapping
import com.suhel.llamabro.internal.JsonMergePatch
import com.suhel.llamabro.internal.Media
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Speaks [text] ([D139](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d139)): upstream's
 * `POST /v1/audio/speech`, streamed, on a model that speaks (LFM3-Omni, Kokoro, Qwen3-TTS, …). The flow emits the
 * speech as it is made, as [SpeechAudio] pieces of 16-bit PCM; collecting it runs the request, and cancelling the
 * collector stops it. Nothing in [configure] is set for the app: an unset field keeps the model's default, and a
 * setting the model doesn't take is refused by upstream.
 *
 * @throws IllegalArgumentException at once if [SpeechConfig.Builder.voice] isn't one of [ModelInfo.voices], or both a
 *   voice and a speaker reference are set (upstream takes one).
 * @throws IllegalStateException at once if the model was loaded for another task ([ModelTask]).
 * @throws LlamaBroException.NotSupported from the flow if the model doesn't speak, as upstream reports it. There is no
 *   check up front: a model can speak with no predefined [ModelInfo.voices], from a speaker reference alone
 *   (`handle_tts_impl`).
 * @throws LlamaBroException.InvalidRequest from the flow if upstream refused the request, with its message (a setting
 *   the model doesn't take, a speaker reference it can't use), or the reference is missing or isn't WAV, MP3 or FLAC.
 * @throws LlamaBroException.ServerError from the flow if the speech that came back isn't 16-bit PCM WAV or holds no
 *   audio: the server's fault, not the caller's.
 */
@CheckResult
public fun Model.speak(text: String, configure: SpeechConfig.Builder.() -> Unit = {}): Flow<SpeechAudio> {
    val config = SpeechConfig(configure)
    requireTask<ModelTask.Generate>("speak", "ModelTask.Generate")
    requireVoice(config.voice)
    require(config.voice == null || config.speakerReference == null) {
        "a voice and a speaker reference can't be used together; set one"
    }
    val body = speechBody(text, config).toString()
    val attachments =
        listOfNotNull(config.speakerReference?.let { Media.attachment("speaker_ref", Media.CLIP_TYPE, it) })
    return flow {
        val wav = WavStream()
        requestBytes(Endpoint.Speech, body, attachments).collect { bytes ->
            wav.feed(bytes)?.let { emit(it) }
        }
        if (!wav.started) throw ErrorMapping.unreadable("speech", "it ended before any audio")
    }
}

/** The request upstream's speech route reads (`convert_speech_to_tts`): WAV streamed as it is made. */
private fun speechBody(text: String, config: SpeechConfig): JsonObject {
    val base =
        buildJsonObject {
            put("input", text)
            put("response_format", "wav")
            put("stream_format", "audio")
            config.voice?.let { put("voice", it) }
            config.speakerReferenceText?.let { put("speaker_ref_text", it) }
            config.language?.let { put("lang", it) }
            config.speed?.let { put("speed", it) }
            config.seed?.let { put("seed", it) }
            config.temperature?.let { put("temperature", it) }
            config.maxFrames?.let { put("max_tokens", it) }
        }
    return JsonMergePatch.applyExtra(base, config.extra, SpeechConfig.OWNED)
}

/**
 * A streamed WAV as upstream writes it (one header with unknown sizes, then 16-bit PCM in chunks of any length): the
 * header gives the rate and channels, and each later chunk becomes samples, an odd byte carried to the next.
 */
internal class WavStream {
    private var header = ByteArray(0)
    private var rate = 0
    private var channels = 0

    /** Whether the header was read and the samples began. */
    var started = false
        private set
    private var carry: Byte? = null

    /** The samples [bytes] completes, or null while the header is still arriving or nothing whole is left. */
    fun feed(bytes: ByteArray): SpeechAudio? {
        var data = bytes
        if (!started) {
            header += bytes
            val at = dataStart(header) ?: return null
            started = true
            data = header.copyOfRange(at, header.size)
            header = ByteArray(0)
        }
        val pending = carry
        val all = if (pending != null) byteArrayOf(pending) + data else data
        val whole = all.size / 2 * 2
        carry = if (whole < all.size) all[all.size - 1] else null
        if (whole == 0) return null
        val pcm =
            ShortArray(
                whole / 2,
            ) { i -> ((all[2 * i].toInt() and BYTE) or (all[2 * i + 1].toInt() shl BITS)).toShort() }
        return SpeechAudio(rate, channels, pcm)
    }

    /** Where the samples begin, once the `fmt ` and `data` chunk headers are in; reads the format on the way. */
    private fun dataStart(h: ByteArray): Int? {
        if (h.size < RIFF_HEADER) return null
        unreadable(h.ascii(0) == "RIFF" && h.ascii(WAVE_AT) == "WAVE") { "the speech isn't WAV" }
        var at = RIFF_HEADER
        while (at + CHUNK_HEADER <= h.size) {
            val id = h.ascii(at)
            val size = h.u32(at + 4)
            when (id) {
                "fmt " -> {
                    if (at + CHUNK_HEADER + FMT_BITS_AT + 2 > h.size) return null
                    channels = h.u16(at + CHUNK_HEADER + FMT_CHANNELS_AT)
                    rate = h.u32(at + CHUNK_HEADER + FMT_RATE_AT).toInt()
                    val bits = h.u16(at + CHUNK_HEADER + FMT_BITS_AT)
                    unreadable(bits == PCM_BITS) { "the speech is $bits-bit, not 16-bit PCM" }
                }

                "data" -> {
                    unreadable(rate > 0) { "the speech's WAV has no format before its data" }
                    return at + CHUNK_HEADER
                }
            }
            at += CHUNK_HEADER + size.toInt() + (size.toInt() and 1)
        }
        return null
    }

    /** Speech the server sent that can't be read is the server's fault, an SDK error, not the caller's misuse. */
    private inline fun unreadable(ok: Boolean, message: () -> String) {
        if (!ok) throw ErrorMapping.unreadable("speech", message())
    }

    private fun ByteArray.ascii(at: Int) = decodeToString(at, at + ID)

    private fun ByteArray.u16(at: Int) = (this[at].toInt() and BYTE) or ((this[at + 1].toInt() and BYTE) shl BITS)

    private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 2 * BITS)

    private companion object {
        const val BYTE = 0xFF
        const val BITS = 8
        const val ID = 4
        const val RIFF_HEADER = 12
        const val WAVE_AT = 8
        const val CHUNK_HEADER = 8
        const val FMT_CHANNELS_AT = 2
        const val FMT_RATE_AT = 4
        const val FMT_BITS_AT = 14
        const val PCM_BITS = 16
    }
}
