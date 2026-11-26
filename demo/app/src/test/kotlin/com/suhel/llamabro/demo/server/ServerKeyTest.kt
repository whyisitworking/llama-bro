package com.suhel.llamabro.demo.server

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerKeyTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    /** A new store on the same file, as after a process restart. */
    private fun settings(): ServerSettings {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        return ServerSettings(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }

    private fun keys() = ServerKey(settings())

    /** Lets go of the file, as a process that ends does: DataStore refuses a second store on a file still held. */
    private suspend fun restart() = scopes.forEach { checkNotNull(it.coroutineContext[Job]).cancelAndJoin() }

    @After fun stop() = scopes.forEach { it.cancel() }

    @Test fun keyIsSixteenCrockford() =
        runBlocking {
            val keys = keys()
            val key = keys.current()
            // Crockford base32, stored upper case without dashes: 16 characters, 80 bits
            assertTrue(key, key.matches(Regex("[0-9A-HJKMNP-TV-Z]{16}")))
            assertEquals(key, keys.value.first())
            // random: a handful of keys share no value
            assertEquals(20, List(20) { keys.renew() }.toSet().size)
        }

    @Test fun anOldBase64KeyIsReplacedWithATypableOne() =
        runBlocking {
            val old = "q8Vw3nX0dJk2LmP5sT7uY9zA1bC4eF6gH8iK0lM2nO4"
            // as the app before typable keys stored it
            settings().setKey(old)
            restart()
            val keys = keys()
            // the old key is not shown as the current one, and the first use replaces it for good
            assertNull(keys.value.first())
            val key = keys.current()
            assertNotEquals(old, key)
            assertTrue(key, ServerKey.isTypable(key))
            restart()
            assertEquals(key, keys().current())
        }

    @Test fun groupedShowsFourGroupsOfFour() {
        assertEquals("K7QM-4RTX-9HCD-2WNB", ServerKey.grouped("K7QM4RTX9HCD2WNB"))
    }

    @Test fun keyIsStableAcrossRestarts() =
        runBlocking {
            val keys = keys()
            // two first reads at once agree on one key
            val first = listOf(async { keys.current() }, async { keys.current() }).awaitAll()
            assertEquals(first[0], first[1])
            restart()
            assertEquals(first[0], keys().current())
        }

    @Test fun renewChangesTheKey() =
        runBlocking {
            val keys = keys()
            val old = keys.current()
            val renewed = keys.renew()
            assertNotEquals(old, renewed)
            assertEquals(renewed, keys.current())
            restart()
            assertEquals(renewed, keys().current())
        }
}
