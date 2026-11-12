package com.suhel.llamabro.demo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Hugging Face token, encrypted with an Android Keystore AES-GCM key. The file holds a 12-byte IV followed by the
 * ciphertext. A file that is corrupt (too short, or fails its GCM tag, which includes a key that was lost) reads as no
 * token and is deleted; a transient Keystore or I/O error reads as no token and keeps the file.
 * The token is never logged.
 */
class TokenStore(
    context: Context,
    private val file: File = File(context.filesDir, "hf_token.bin"),
    private val alias: String = KEY_ALIAS,
    /** Where the AES key comes from; tests inject a failing one to simulate a transient Keystore error. */
    private val secretKey: () -> SecretKey = { keystoreKey(alias) },
) {
    private val lock = Mutex()

    suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!file.exists()) return@withLock null
                try {
                    val bytes = file.readBytes()
                    require(bytes.size > IV_BYTES) { "token file too short" }
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
                    String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
                } catch (_: AEADBadTagException) {
                    file.delete()
                    null
                } catch (_: IllegalArgumentException) {
                    file.delete()
                    null
                } catch (_: IllegalBlockSizeException) {
                    file.delete()
                    null
                } catch (_: IOException) {
                    null
                } catch (_: GeneralSecurityException) {
                    null
                } catch (_: ProviderException) {
                    null
                }
            }
        }

    /** Stores [token]; null or blank deletes it. */
    suspend fun write(token: String?) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (token.isNullOrBlank()) {
                    file.delete()
                    return@withLock
                }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                check(cipher.iv.size == IV_BYTES)
                val tmp = File(file.parentFile, file.name + ".tmp")
                try {
                    tmp.writeBytes(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
                    check(tmp.renameTo(file)) { "could not store the token" }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "llamabro_demo_hf_token"
        const val KEY_SIZE_BITS = 256
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        fun keystoreKey(alias: String): SecretKey {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
