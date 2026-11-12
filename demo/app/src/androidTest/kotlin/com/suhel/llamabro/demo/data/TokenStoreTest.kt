package com.suhel.llamabro.demo.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.ProviderException
import java.util.UUID

class TokenStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.filesDir, "hf_token-test-${UUID.randomUUID()}.bin")
    private val store = TokenStore(context, file)

    @After fun cleanUp() {
        file.delete()
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray) =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    @Test fun writeThenReadRoundTrips() =
        runBlocking<Unit> {
            assertNull(store.read())
            store.write("hf_abcDEF123456")
            assertEquals("hf_abcDEF123456", store.read())
            store.write("hf_replaced")
            assertEquals("hf_replaced", store.read())
            assertEquals("hf_replaced", TokenStore(context, file).read())
        }

    @Test fun theFileHoldsNoPlainTokenBytes() =
        runBlocking<Unit> {
            val token = "hf_plainTextMustNotAppear"
            store.write(token)
            val bytes = file.readBytes()
            assertTrue(bytes.size > 12 + token.length)
            assertFalse(containsBytes(bytes, token.toByteArray()))
            assertFalse(containsBytes(bytes, "plainText".toByteArray()))
        }

    @Test fun twoWritesOfTheSameTokenDifferOnDisk() =
        runBlocking<Unit> {
            store.write("hf_same")
            val first = file.readBytes()
            store.write("hf_same")
            assertFalse(first.contentEquals(file.readBytes()))
        }

    @Test fun writeNullDeletesIt() =
        runBlocking<Unit> {
            store.write("hf_abc")
            assertTrue(file.exists())
            store.write(null)
            assertFalse(file.exists())
            assertNull(store.read())
            store.write("hf_abc")
            store.write("  ")
            assertFalse(file.exists())
        }

    @Test fun corruptTokenFileReadsAsNoToken() =
        runBlocking<Unit> {
            store.write("hf_abc")
            val bytes = file.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x55).toByte()
            file.writeBytes(bytes)
            assertNull(store.read())
            assertFalse(file.exists())

            file.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(store.read())
            assertFalse(file.exists())
        }

    @Test fun transientKeystoreErrorKeepsTheFile() =
        runBlocking<Unit> {
            store.write("hf_abc")
            val failing = TokenStore(context, file, secretKey = { throw ProviderException("keystore busy") })
            assertNull(failing.read())
            assertTrue(file.exists())
            assertEquals("hf_abc", store.read())
            assertThrows(ProviderException::class.java) { runBlocking { failing.write("hf_new") } }
            assertFalse(File(file.path + ".tmp").exists())
            assertEquals("hf_abc", store.read())
        }
}
