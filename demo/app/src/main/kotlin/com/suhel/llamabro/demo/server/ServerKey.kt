package com.suhel.llamabro.demo.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

/**
 * The key clients send to the server: 16 random characters of Crockford base32 (80 bits), made on first use and kept in
 * the app's settings, upper case without dashes, so it stays the same across restarts until [renew]
 * (docs/design/server.md §6, D173). It is short enough to type on a laptop: the page shows it in four groups
 * ([grouped]), and the server matches it with `KeyMatching.Typable`, so case, spaces and dashes don't matter; wrong
 * keys are throttled, which is what makes 80 bits ample. A key stored before typable keys (43 characters of base64url)
 * is replaced on first use, so a computer paired with it must enter the new one. Renewing while serving goes through
 * [ServerMode.renewKey], which restarts the server with it.
 */
class ServerKey(private val settings: ServerSettings, private val random: SecureRandom = SecureRandom()) {
    /** The key as stored; null until the first [current], and while an old one waits to be replaced. */
    val value: Flow<String?> = settings.key.map { stored -> stored?.takeIf(::isTypable) }

    suspend fun current(): String = settings.keyOr(::isTypable, ::make)

    suspend fun renew(): String = make().also { settings.setKey(it) }

    private fun make(): String = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    companion object {
        /** Crockford's base32: the digits and the letters but I, L, O and U, which read as 1, 1, 0 and V. */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val LENGTH = 16
        private const val GROUP = 4

        /** Whether [key] is a key as [current] makes and stores it. */
        fun isTypable(key: String): Boolean = key.length == LENGTH && key.all { it in ALPHABET }

        /** [key] as a person reads and types it: `K7QM-4RTX-9HCD-2WNB`. */
        fun grouped(key: String): String = key.chunked(GROUP).joinToString("-")
    }
}
