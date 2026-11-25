package com.suhel.llamabro.demo.server

import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import java.util.Base64

/**
 * The key clients send to the server: 32 random bytes, base64url without padding, made on first use and kept in the
 * app's settings, so it stays the same across restarts until [renew] (docs/design/server.md §3). Renewing while
 * serving goes through [ServerMode.renewKey], which restarts the server with it.
 */
class ServerKey(private val settings: ServerSettings, private val random: SecureRandom = SecureRandom()) {
    /** The key as stored; null until the first [current]. */
    val value: Flow<String?> = settings.key

    suspend fun current(): String = settings.keyOr(::make)

    suspend fun renew(): String = make().also { settings.setKey(it) }

    private fun make(): String {
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
