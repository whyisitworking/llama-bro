package com.suhel.llamabro.hexagon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/** P2 T3 `skeletonCopyIsAtomicAndVerified`, split by case (review focus 2). */
class SkeletonInstallTest {
    private val root = Files.createTempDirectory("htp").toFile()

    /** Fake APK assets: skeleton bytes plus the .sha256 files the build writes, optionally wrong. */
    private class Assets(val files: Map<String, ByteArray>, val hashOverride: Map<String, String> = emptyMap()) :
        SkeletonInstall.Source {
        var opens = 0

        override fun list() = files.keys.flatMap { listOf(it, "$it.sha256") }

        override fun open(name: String): InputStream {
            opens++
            val bytes =
                if (name.endsWith(".sha256")) {
                    val so = name.removeSuffix(".sha256")
                    (hashOverride[so] ?: sha(files.getValue(so))).plus("\n").encodeToByteArray()
                } else {
                    files.getValue(name)
                }
            return bytes.inputStream()
        }
    }

    private val v1 =
        mapOf(
            "libggml-htp-v79.so" to ByteArray(300_000) { it.toByte() },
            "libggml-htp-v81.so" to ByteArray(1000) { 7 },
        )

    @Test
    fun installsEverySkeletonVerified() {
        val dir = SkeletonInstall(root, Assets(v1)).install()
        assertEquals(root, dir.parentFile)
        for ((name, bytes) in v1) assertArrayEquals(name, bytes, File(dir, name).readBytes())
        assertEquals(setOf(dir.name, ".lock"), root.list()!!.toSet()) // no staging left behind
    }

    @Test
    fun secondInstallReusesTheDirectoryWithoutCopying() {
        val first = SkeletonInstall(root, Assets(v1)).install()
        val stamp = File(first, "libggml-htp-v79.so").lastModified()
        val assets = Assets(v1)
        val second = SkeletonInstall(root, assets).install()
        assertEquals(first, second)
        assertEquals(stamp, File(second, "libggml-htp-v79.so").lastModified())
        assertEquals(v1.size, assets.opens) // only the .sha256 files were read
    }

    @Test
    fun corruptOrTruncatedFileIsReplaced() {
        val dir = SkeletonInstall(root, Assets(v1)).install()
        File(dir, "libggml-htp-v79.so").writeBytes(ByteArray(10)) // a crash mid-copy, or tampering
        File(dir, "libggml-htp-v81.so").delete()
        val again = SkeletonInstall(root, Assets(v1)).install()
        assertEquals(dir, again)
        for ((name, bytes) in v1) assertArrayEquals(name, bytes, File(again, name).readBytes())
    }

    @Test
    fun wrongHashInTheApkFailsWithATypedReason() {
        val assets = Assets(v1, hashOverride = mapOf("libggml-htp-v81.so" to "0".repeat(64)))
        try {
            SkeletonInstall(root, assets).install()
            fail("installed a skeleton that doesn't match its hash")
        } catch (e: SkeletonInstall.MismatchException) {
            assertTrue(e.message!!, "libggml-htp-v81.so" in e.message!!)
        }
        assertEquals(setOf(".lock"), root.list()!!.toSet()) // nothing half-installed
    }

    @Test
    fun appUpdateGetsANewDirectoryAndTheOldOneIsDeleted() {
        val old = SkeletonInstall(root, Assets(v1)).install()
        val v2 = v1 + ("libggml-htp-v79.so" to ByteArray(300_000) { (it * 3).toByte() })
        val new = SkeletonInstall(root, Assets(v2)).install()
        assertNotEquals(old, new)
        assertTrue(!old.exists())
        assertArrayEquals(v2.getValue("libggml-htp-v79.so"), File(new, "libggml-htp-v79.so").readBytes())
    }

    @Test
    fun concurrentInstallsGiveOneCompleteDirectory() {
        repeat(20) {
            root.deleteRecursively()
            val barrier = CyclicBarrier(4)
            val results = Collections.synchronizedList(mutableListOf<File>())
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            (1..4)
                .map {
                    thread {
                        try {
                            barrier.await()
                            results += SkeletonInstall(root, Assets(v1)).install()
                        } catch (t: Throwable) {
                            errors += t
                        }
                    }
                }.forEach { it.join() }
            assertEquals(emptyList<Throwable>(), errors)
            assertEquals(1, results.toSet().size)
            for ((name, bytes) in v1) assertArrayEquals(name, bytes, File(results[0], name).readBytes())
            assertEquals(setOf(results[0].name, ".lock"), root.list()!!.toSet())
        }
    }

    private companion object {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
