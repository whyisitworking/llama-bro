package com.suhel.llamabro.demo.server

import com.suhel.llamabro.server.Pairing
import com.suhel.llamabro.server.PairingRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's answer to a computer asking for the key (docs/design/server.md §6, D174): each request waits in line
 * and the first is [shown], as the dialog over the Server page and the docked view, or the notification when the app
 * is behind another. [answer] settles it. No answer within [EXPIRY_MILLIS] is a deny; the server cancels the question
 * at the same moment, and this is the same rule for a server that would wait longer.
 */
class PairingApprovals : Pairing {
    private class Waiting(val request: PairingRequest, val answer: CompletableDeferred<Boolean>)

    private val line = MutableStateFlow<List<Waiting>>(emptyList())
    private val first = MutableStateFlow<PairingRequest?>(null)

    /** The request to answer now; null when none waits. */
    val shown: StateFlow<PairingRequest?> = first.asStateFlow()

    override suspend fun approve(request: PairingRequest): Boolean {
        val waiting = Waiting(request, CompletableDeferred())
        change { it + waiting }
        return try {
            withTimeoutOrNull(EXPIRY_MILLIS) { waiting.answer.await() } ?: false
        } finally {
            change { it - waiting }
        }
    }

    /** Approves or denies [request], if it is the one [shown]: a tap meant for one never answers the next. */
    fun answer(request: PairingRequest, approved: Boolean) {
        line.value
            .firstOrNull()
            ?.takeIf { it.request == request }
            ?.answer
            ?.complete(approved)
    }

    // the line and its first change together, so a question arriving as another leaves never shows the wrong one
    private fun change(edit: (List<Waiting>) -> List<Waiting>) =
        synchronized(line) {
            line.update(edit)
            first.value = line.value.firstOrNull()?.request
        }

    companion object {
        /** The server's own expiry for a pairing: two minutes. */
        const val EXPIRY_MILLIS = 120_000L
    }
}
