package com.suhel.llamabro.hexagon

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Installs the HTP skeletons into `<root>/<set>/`, where `<set>` is derived from their SHA-256s. A set's directory is
 * only ever created whole, by renaming a staging directory, and is checked against the hashes the build wrote next to
 * each skeleton every time, so a truncated copy from a crash or a tampered file is replaced and an app update (a new
 * set) never sees an old file. Threads in one process are serialised by a lock object, processes by a file lock in
 * [root]. Other sets' directories are deleted once this one is in place.
 */
internal class SkeletonInstall(private val root: File, private val source: Source) {
    interface Source {
        fun list(): List<String>

        fun open(name: String): InputStream
    }

    /** The skeletons in the APK don't match the hashes built with them: a broken build or a modified APK. */
    class MismatchException(message: String) : IllegalStateException(message)

    fun install(): File {
        val names = source.list().filter { it.endsWith(".so") }.sorted()
        check(names.isNotEmpty()) { "no HTP skeletons in the APK's assets" }
        val expected =
            names.associateWith { name ->
                source.open("$name.sha256").use { it.readBytes().decodeToString().trim() }.also {
                    if (!HEX64.matches(it)) throw MismatchException("$name.sha256 isn't a SHA-256: $it")
                }
            }
        val set = sha256(names.joinToString("\n") { "$it ${expected.getValue(it)}" }.encodeToByteArray()).take(16)
        val dir = File(root, set)
        synchronized(LOCK) {
            root.mkdirs()
            RandomAccessFile(File(root, ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    if (!isComplete(dir, expected)) {
                        val staging = File(root, ".staging-${UUID.randomUUID()}")
                        try {
                            copyVerified(staging, names, expected)
                            dir.deleteRecursively()
                            if (!staging.renameTo(dir)) error("couldn't move $staging to $dir")
                        } finally {
                            staging.deleteRecursively()
                        }
                    }
                    root.listFiles()?.filter { it.name != dir.name && it.name != ".lock" }?.forEach {
                        it
                            .deleteRecursively()
                    }
                }
            }
        }
        return dir
    }

    private fun isComplete(dir: File, expected: Map<String, String>): Boolean =
        dir.isDirectory &&
            expected.all { (name, hash) -> File(dir, name).let { it.isFile && sha256(it.readBytes()) == hash } }

    private fun copyVerified(staging: File, names: List<String>, expected: Map<String, String>) {
        check(staging.mkdirs()) { "couldn't create $staging" }
        for (name in names) {
            val actual = copyAndHash(name, File(staging, name))
            if (actual != expected.getValue(name)) {
                throw MismatchException("$name in the APK has SHA-256 $actual, expected ${expected.getValue(name)}")
            }
        }
    }

    /** Copies [name] from the APK to [target], flushed to disk, and returns the SHA-256 of what was copied. */
    private fun copyAndHash(name: String, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(target).use { out ->
            source.open(name).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            out.fd.sync()
        }
        return digest.digest().toHex()
    }

    private companion object {
        val LOCK = Any()
        val HEX64 = Regex("[0-9a-f]{64}")

        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
