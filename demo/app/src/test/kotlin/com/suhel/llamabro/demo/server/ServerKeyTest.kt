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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ServerKeyTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    /** A new store on the same file, as after a process restart. */
    private fun keys(): ServerKey {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        return ServerKey(ServerSettings(store))
    }

    /** Lets go of the file, as a process that ends does: DataStore refuses a second store on a file still held. */
    private suspend fun restart() = scopes.forEach { checkNotNull(it.coroutineContext[Job]).cancelAndJoin() }

    @After fun stop() = scopes.forEach { it.cancel() }

    @Test fun keyIsThirtyTwoRandomBytesInBase64Url() =
        runBlocking {
            val key = keys().current()
            assertTrue(key, key.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(32, Base64.getUrlDecoder().decode(key).size)
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
