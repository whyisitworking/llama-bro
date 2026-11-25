package com.suhel.llamabro.demo.server

import com.suhel.llamabro.server.ServerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * One request in server mode's log: [time] is when it arrived (epoch millis); a request turned away before it reached
 * the model has no [method], [millis] or token counts.
 */
data class LogLine(
    val time: Long,
    val client: String,
    val method: String?,
    val path: String,
    val status: Int,
    val millis: Long?,
    val promptTokens: Int?,
    val predictedTokens: Int?,
)

/**
 * The server's events as log [lines], newest first, at most [MAX_LINES]: a request is one line once it ends (its start
 * and end joined by id), a refusal one line at once. An end whose start the server's events dropped is no line.
 */
class ServerLog(private val clock: () -> Long) {
    private val started = ConcurrentHashMap<Long, Pair<Long, ServerEvent.RequestStarted>>()
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    fun record(event: ServerEvent) {
        when (event) {
            is ServerEvent.RequestStarted -> {
                started[event.id] = clock() to event
            }

            is ServerEvent.RequestEnded -> {
                val (time, start) = started.remove(event.id) ?: return
                add(
                    LogLine(
                        time,
                        start.client,
                        start.method,
                        start.path,
                        event.status,
                        event.millis,
                        event.promptTokens,
                        event.predictedTokens,
                    ),
                )
            }

            is ServerEvent.Rejected -> {
                add(LogLine(clock(), event.client, null, event.path, event.status, null, null, null))
            }
        }
    }

    /** Forgets the requests still open: a stopped server ends none of them in its events. */
    fun forgetOpen() = started.clear()

    private fun add(line: LogLine) = _lines.update { (listOf(line) + it).take(MAX_LINES) }

    private companion object {
        const val MAX_LINES = 200
    }
}
