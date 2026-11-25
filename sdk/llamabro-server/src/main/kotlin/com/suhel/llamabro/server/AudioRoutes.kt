package com.suhel.llamabro.server

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.audio.SpeechAudio
import com.suhel.llamabro.speak
import com.suhel.llamabro.transcribe
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.TimeSource

private const val HEX = 16

/**
 * `POST /v1/audio/transcriptions` and `POST /v1/audio/speech`
 * ([design](https://github.com/whyisitworking/llama-bro/blob/main/docs/design/server.md)), answered from the SDK's
 * `Model.transcribe` and `Model.speak` rather than forwarded: llama-server's own routes want a multipart body and
 * WAV, and the SDK already reads both. They take the key, the body limit and the request log like the other model
 * routes, and hold a lease that is released exactly once on every path.
 */
internal class AudioRoutes(
    private val models: ServedModels,
    private val emit: (ServerEvent) -> Unit,
    private val nextId: () -> Long,
) {
    /** Registers both routes for [config]. */
    fun Route.audio(config: ServerConfig) {
        post("/v1/audio/transcriptions") { transcriptions(call, config) }
        post("/v1/audio/speech") { speech(call, config) }
    }

    private suspend fun transcriptions(call: ApplicationCall, config: ServerConfig) {
        // one file per request, named here so the finally can delete it whatever stopped the upload (cancel included)
        val upload = Path(config.workDir, "upload-${Random.nextLong().toULong().toString(HEX)}")
        try {
            val request =
                try {
                    TranscribeRequest.read(call, upload, config.maxBodyBytes)
                } catch (refusal: Refusal) {
                    reject(call, refusal)
                    return
                }
            tracked(call) { outcome ->
                withLease(call, request.model, outcome, { it.info.supportsAudio }, "hear") { model ->
                    val transcript =
                        model.transcribe(MediaSource.file(upload.toString())) {
                            language = request.language
                            prompt = request.prompt
                            temperature = request.temperature
                        }
                    outcome.status = HttpStatusCode.OK.value
                    if (request.text) {
                        call.respondText(transcript.text, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
                    } else {
                        val json = buildJsonObject { put("text", transcript.text) }
                        call.respondText(json.toString(), ContentType.Application.Json)
                    }
                }
            }
        } finally {
            SystemFileSystem.delete(upload, mustExist = false)
        }
    }

    private suspend fun speech(call: ApplicationCall, config: ServerConfig) {
        val body =
            when (val guarded = call.guardedBody(true, config.maxBodyBytes)) {
                is GuardedBody.Passed -> {
                    guarded
                }

                is GuardedBody.Refused -> {
                    reject(call, Refusal(guarded.status, guarded.message, guarded.reason))
                    return
                }
            }
        val request =
            try {
                SpeechRequest.read(body.json as? JsonObject)
            } catch (refusal: Refusal) {
                reject(call, refusal)
                return
            }
        tracked(call) { outcome ->
            withLease(call, request.model, outcome, { it.info.supportsSpeech }, "speak") { model ->
                // OpenAI clients always send a voice ("alloy"); one the model doesn't have is left out, not refused
                val voice = request.voice?.takeIf { it in model.info.voices }
                val audio =
                    model.speak(request.input) {
                        this.voice = voice
                        speed = request.speed
                    }
                coroutineScope { call.respondAudio(audio.produceIn(this), request.wav, outcome) }
            }
        }
    }

    // the first piece decides the status: an error before any audio is still a clean error response
    private suspend fun ApplicationCall.respondAudio(
        audio: ReceiveChannel<SpeechAudio>,
        wav: Boolean,
        outcome: Outcome,
    ) {
        try {
            val first = audio.receiveCatching()
            val failure = first.exceptionOrNull()
            when {
                failure != null -> respondFailure(failure, outcome)
                first.isClosed -> respondBytes(ByteArray(0), audioType(wav))
                else -> stream(first.getOrThrow(), audio, wav, outcome)
            }
        } finally {
            // a client that hung up, or a write that failed: the speech stops being made
            audio.cancel()
        }
    }

    private suspend fun ApplicationCall.stream(
        first: SpeechAudio,
        rest: ReceiveChannel<SpeechAudio>,
        wav: Boolean,
        outcome: Outcome,
    ) {
        outcome.status = HttpStatusCode.OK.value
        response.header(SAMPLE_RATE_HEADER, first.sampleRate.toString())
        respondBytesWriter(audioType(wav), HttpStatusCode.OK) {
            try {
                if (wav) writeFully(wavHeader(first.sampleRate, first.channels))
                writeFully(first.littleEndian())
                flush()
                for (piece in rest) {
                    writeFully(piece.littleEndian())
                    flush()
                }
            } catch (_: LlamaBroException) {
                // the model closed or failed after the status went out: it can't change now, the stream ends cleanly
            }
        }
    }

    /** Logs the start and the end of the request around [block], whose [Outcome] says what was answered. */
    private suspend fun tracked(call: ApplicationCall, block: suspend (Outcome) -> Unit) {
        val request = call.request
        val id = nextId()
        emit(
            ServerEvent.RequestStarted(
                id,
                request.local.remoteAddress,
                request.httpMethod.value,
                request.path(),
                request.userAgent(),
            ),
        )
        val began = TimeSource.Monotonic.markNow()
        val outcome = Outcome()
        try {
            block(outcome)
        } finally {
            emit(ServerEvent.RequestEnded(id, outcome.status, began.elapsedNow().inWholeMilliseconds, null, null))
        }
    }

    /** Runs [block] on the model for [id] if [capable] says it can; else 404, as a route this model doesn't serve. */
    private suspend fun withLease(
        call: ApplicationCall,
        id: String?,
        outcome: Outcome,
        capable: (Model) -> Boolean,
        verb: String,
        block: suspend (Model) -> Unit,
    ) {
        val lease = call.acquireLease(models, id) { outcome.status = it } ?: return
        try {
            if (!capable(lease.model)) {
                outcome.status = HttpStatusCode.NotFound.value
                call.respondError(HttpStatusCode.NotFound, "this model can't $verb", ErrorType.NOT_FOUND)
                return
            }
            try {
                block(lease.model)
            } catch (e: LlamaBroException) {
                call.respondFailure(e, outcome)
            } catch (e: IllegalArgumentException) {
                call.respondFailure(e, outcome)
            } catch (e: IllegalStateException) {
                call.respondFailure(e, outcome)
            }
        } finally {
            lease.release()
        }
    }

    private suspend fun reject(call: ApplicationCall, refusal: Refusal) {
        val request = call.request
        emit(ServerEvent.Rejected(request.local.remoteAddress, request.path(), refusal.status.value, refusal.reason))
        call.respondError(refusal.status, refusal.message, ErrorType.INVALID_REQUEST)
    }
}

/** What the request ended with, for [ServerEvent.RequestEnded]: a 500 until something answers. */
private class Outcome {
    var status: Int = HttpStatusCode.InternalServerError.value
}

private fun audioType(wav: Boolean) = ContentType.parse(if (wav) "audio/wav" else "audio/pcm")

/**
 * What the SDK raised, as the client sees it: upstream's own status for a [LlamaBroException.ServerError], 400 for a
 * request the model refused or a setting it can't take, 503 for a closed model, 500 for the rest.
 */
private suspend fun ApplicationCall.respondFailure(failure: Throwable, outcome: Outcome) {
    val (status, type) =
        when (failure) {
            is LlamaBroException.ServerError -> {
                serverFailure(failure.httpStatus)
            }

            is LlamaBroException.ModelClosed, is LlamaBroException.Unavailable -> {
                HttpStatusCode.ServiceUnavailable to ErrorType.UNAVAILABLE
            }

            is LlamaBroException.InvalidRequest,
            is LlamaBroException.ContextExceeded,
            is LlamaBroException.InputTooLong,
            is LlamaBroException.NotSupported,
            is IllegalArgumentException,
            is IllegalStateException,
            -> {
                HttpStatusCode.BadRequest to ErrorType.INVALID_REQUEST
            }

            is LlamaBroException -> {
                HttpStatusCode.InternalServerError to ErrorType.SERVER
            }

            else -> {
                throw failure
            }
        }
    outcome.status = status.value
    respondError(status, failure.message ?: "the request failed", type)
}

// a ServerError with a 2xx status is a reply the SDK couldn't read: the server's fault
private fun serverFailure(httpStatus: Int): Pair<HttpStatusCode, String> =
    when {
        httpStatus < MIN_ERROR_STATUS -> HttpStatusCode.InternalServerError to ErrorType.SERVER
        httpStatus < MIN_SERVER_STATUS -> HttpStatusCode.fromValue(httpStatus) to ErrorType.INVALID_REQUEST
        else -> HttpStatusCode.fromValue(httpStatus) to ErrorType.SERVER
    }

private const val MIN_ERROR_STATUS = 400
private const val MIN_SERVER_STATUS = 500
