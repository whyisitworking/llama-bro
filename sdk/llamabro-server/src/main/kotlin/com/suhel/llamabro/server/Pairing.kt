package com.suhel.llamabro.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * How the app approves a computer that asks for the key from its browser
 * ([D174](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d174)): with
 * [ServerConfig.pairing] set, `GET /` serves a page that asks to pair, and the server calls [approve] once per pairing.
 * The person compares [PairingRequest.code] with the one the page shows, and true hands the page the key. The call is
 * cancelled when the pairing expires after two minutes, which counts as a deny, and it is never made twice at once for
 * the same address.
 */
public fun interface Pairing {
    public suspend fun approve(request: PairingRequest): Boolean
}

/**
 * A computer at [client] (its address) asks for the key; its page shows [code], four digits, for the person to check
 * against the app's. [userAgent] is the browser's, as it sent it.
 */
public data class PairingRequest(val code: String, val client: String, val userAgent: String?)

/**
 * The running server's pairings: at most one waiting per address and [MAX_PENDING] in all, each expiring after
 * [EXPIRY_MILLIS]. An answered or expired pairing is told once to the address that started it and then forgotten, as
 * it is when no one asks within [EXPIRY_MILLIS] more. [approve] runs on [scope], so stopping the server cancels it.
 */
internal class Pairings(
    private val approve: Pairing,
    private val scope: CoroutineScope,
    private val key: String,
    private val expiryMillis: Long = EXPIRY_MILLIS,
    private val clock: () -> Long = monotonicMillis(),
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Start {
        data class Begun(val id: String, val code: String) : Start

        /** [MAX_PENDING] wait already: the first of them ends within [retryAfterMillis]. */
        data class Full(val retryAfterMillis: Long) : Start
    }

    sealed interface State {
        data object Pending : State

        data object Denied : State

        data object Expired : State

        data class Approved(val key: String) : State
    }

    private class Entry(val id: String, val client: String, val code: String, val started: Long) {
        var state: State = State.Pending
        var ended = 0L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Mutex()

    /** A pairing for [client]: the one it already has waiting, else a new one, unless [MAX_PENDING] wait. */
    suspend fun start(client: String, userAgent: String?): Start {
        val entry =
            lock.withLock {
                val now = clock()
                forgetStale(now)
                entries.values.firstOrNull { it.client == client && it.state == State.Pending }?.let {
                    return Start.Begun(it.id, it.code)
                }
                val pending = entries.values.filter { it.state == State.Pending }
                if (pending.size >= MAX_PENDING) {
                    return Start.Full((pending.minOf { it.started } + expiryMillis - now).coerceAtLeast(1))
                }
                Entry(newId(), client, newCode(), now).also { entries[it.id] = it }
            }
        // asked outside the lock; the entry stays Pending, and so the only one for its address, until this ends
        scope.launch(start = CoroutineStart.UNDISPATCHED) { ask(entry, PairingRequest(entry.code, client, userAgent)) }
        return Start.Begun(entry.id, entry.code)
    }

    /** Whether [id] is a pairing [client] started and has not yet been told the end of. */
    suspend fun exists(client: String, id: String): Boolean =
        lock.withLock {
            forgetStale(clock())
            entries[id]?.client == client
        }

    /**
     * Where [client]'s pairing [id] is; null for an id that isn't one, or is another address's. An answer is given
     * once: after it the id is gone.
     */
    suspend fun poll(client: String, id: String): State? =
        lock.withLock {
            forgetStale(clock())
            val entry = entries[id]?.takeIf { it.client == client } ?: return@withLock null
            entry.state.also { if (it != State.Pending) entries.remove(id) }
        }

    private suspend fun ask(entry: Entry, request: PairingRequest) {
        val approved =
            try {
                withTimeoutOrNull(expiryMillis) { approve.approve(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                // the app failed to ask: nobody approved, and the module has no log to tell
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                false
            }
        lock.withLock {
            entry.state =
                when (approved) {
                    null -> State.Expired
                    true -> State.Approved(key)
                    false -> State.Denied
                }
            entry.ended = clock()
        }
    }

    // an answer no one came for goes after as long again, the key with it
    private fun forgetStale(now: Long) {
        entries.values.removeAll { it.state != State.Pending && now - it.ended > expiryMillis }
    }

    private fun newId(): String =
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(ByteArray(ID_BYTES).also(random::nextBytes))

    private fun newCode(): String = random.nextInt(CODES).toString().padStart(CODE_DIGITS, '0')

    companion object {
        const val EXPIRY_MILLIS = 120_000L
        const val MAX_PENDING = 3
        private const val ID_BYTES = 16
        private const val CODES = 10_000
        private const val CODE_DIGITS = 4
    }
}
