package com.suhel.llamabro.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Whether wrong keys are throttled: per client address, after [WrongKeys.MAX_WRONG] wrong keys within a minute, its
 * requests get 429 with `Retry-After`, the wait doubling from 30 s up to 5 min; a right key resets it
 * ([D173](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d173)). It applies only when a key
 * is set, and never to the open paths.
 */
public class Throttle internal constructor(
    internal val enabled: Boolean,
    /** Milliseconds on a monotonic clock; tests move it by hand. */
    internal val clock: () -> Long = monotonicMillis(),
) {
    override fun toString(): String = if (enabled) "Throttle.Default" else "Throttle.Off"

    public companion object {
        /** On, as the module ships: a deviation from upstream, which has no throttle (D173). */
        public val Default: Throttle = Throttle(enabled = true)

        /** Every wrong key gets its 401, however many come, as upstream answers them. */
        public val Off: Throttle = Throttle(enabled = false)
    }
}

internal fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/**
 * The wrong keys each client address sent lately, and how long it must wait. Bounded: an address idle for
 * [IDLE_MILLIS] is forgotten, its doubling with it, and past [MAX_TRACKED] addresses the one idle longest goes.
 */
internal class WrongKeys(private val clock: () -> Long) {
    private class Record {
        /** When the wrong keys of the last minute came, oldest first. */
        val misses = ArrayDeque<Long>()
        var waits = 0
        var until = 0L
        var seen = 0L
    }

    // in the order the addresses were last seen, the one idle longest first
    private val records = LinkedHashMap<String, Record>()
    private val lock = Mutex()

    /**
     * Judges one request from [client] in a single step, so parallel requests can't all pass the check before any of
     * them is recorded: the milliseconds it must still wait, or null when it was let through to be judged, in which
     * case a right key ([ok]) starts it over and a wrong one ([guessed]: a key was presented and was not [ok]) counts
     * towards the [MAX_WRONG]th within a minute that starts a wait, double the last one. A request with no key ([ok]
     * and [guessed] both false) guesses nothing and changes nothing.
     */
    suspend fun check(client: String, ok: Boolean, guessed: Boolean): Long? =
        lock.withLock {
            val now = clock()
            forgetIdle(now)
            val existing = records[client]
            val wait = existing?.let { it.until - now }?.takeIf { it > 0 }
            when {
                wait != null -> touch(client, existing, now)
                ok -> records.remove(client)
                guessed -> wrong(client, existing ?: Record(), now)
                existing != null -> touch(client, existing, now)
            }
            wait
        }

    private fun wrong(client: String, record: Record, now: Long) {
        touch(client, record, now)
        while (record.misses.firstOrNull()?.let { now - it >= WINDOW_MILLIS } == true) record.misses.removeFirst()
        record.misses.addLast(now)
        if (record.misses.size >= MAX_WRONG) {
            record.misses.clear()
            record.until = now + waitAfter(record.waits)
            record.waits++
        }
        // only a wrong key adds an address, so trimming here keeps the map within its cap
        while (records.size > MAX_TRACKED) records.remove(records.keys.first())
    }

    suspend fun tracked(): Int = lock.withLock { records.size }

    private fun touch(client: String, record: Record, now: Long) {
        record.seen = now
        records.remove(client)
        records[client] = record
    }

    private fun forgetIdle(now: Long) {
        val oldest = records.values.iterator()
        while (oldest.hasNext() && now - oldest.next().seen > IDLE_MILLIS) oldest.remove()
    }

    private fun waitAfter(waits: Int): Long =
        (FIRST_WAIT_MILLIS shl waits.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_WAIT_MILLIS)

    companion object {
        const val MAX_WRONG = 5
        const val MAX_TRACKED = 1_024
        const val WINDOW_MILLIS = 60_000L
        const val FIRST_WAIT_MILLIS = 30_000L
        const val MAX_WAIT_MILLIS = 300_000L
        const val IDLE_MILLIS = 600_000L

        // 30 s doubled 4 times passes 5 min; the cap keeps the shift far from overflowing
        private const val MAX_DOUBLINGS = 4
    }
}
