package com.suhel.llamabro.hub

import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// D70: one hub per cache folder; the registry is tested through its context-free seam.
internal class HubCreateTest {
    private val folders = mutableListOf<File>()
    private var builds = 0

    @Before fun reset() = LlamaBroHub.resetCreatedForTest()

    @After fun cleanUp() {
        LlamaBroHub.resetCreatedForTest()
        folders.forEach { it.deleteRecursively() }
    }

    private fun folder(): File = Files.createTempDirectory("hub-create-").toFile().also { folders += it }

    private fun configFor(dir: File): HubConfig = HubConfig { cacheDir = dir }

    private fun build(dir: File, config: HubConfig): LlamaBroHub {
        builds++
        return LlamaBroHub(config, dir, HttpTransport(config.endpoint, null, null), JvmStorage())
    }

    private fun create(dir: File, config: HubConfig): LlamaBroHub =
        LlamaBroHub.create(dir, config) { build(dir, config) }

    @Test fun sameFolderEqualConfigReturnsTheSameHub() {
        val dir = folder()
        val first = create(dir, configFor(dir))
        val second = create(dir, configFor(dir))
        assertSame(first, second)
        assertEquals(1, builds)
    }

    @Test fun aNewLoggerAndTokenWithTheSameSettingsGetTheSameHub() {
        val dir = folder()
        val first =
            create(
                dir,
                HubConfig {
                    cacheDir = dir
                    logger = Logger { _, _ -> }
                    token { "a" }
                },
            )
        assertSame(
            first,
            create(
                dir,
                HubConfig {
                    cacheDir = dir
                    logger = Logger { _, _ -> }
                    token { "b" }
                },
            ),
        )
        assertEquals(1, builds)
    }

    @Test fun sameFolderDifferentConfigThrows() {
        val dir = folder()
        create(dir, configFor(dir))
        val other =
            HubConfig {
                cacheDir = dir
                progressInterval = 5.seconds
            }
        val error = assertThrows(IllegalStateException::class.java) { create(dir, other) }
        assertTrue(error.message, error.message!!.contains(dir.canonicalPath))
        assertEquals(1, builds)
    }

    @Test fun differentFoldersCoexist() {
        val a = folder()
        val b = folder()
        val configA = configFor(a)
        val configB =
            HubConfig {
                cacheDir = b
                progressInterval = 5.seconds
            }
        val hubA = create(a, configA)
        val hubB = create(b, configB)
        assertNotSame(hubA, hubB)
        assertSame(hubA, create(a, configA))
        assertSame(hubB, create(b, configB))
        assertEquals(2, builds)
    }

    @Test fun nullCacheDirAndTheDefaultFolderAreOneKey() {
        val noBackup = folder()
        val viaNullConfig = HubConfig.Default
        val viaNull = LlamaBroHub.cacheDirFor(viaNullConfig, noBackup)
        val explicitConfig = HubConfig { cacheDir = File(noBackup, "llamabro/hub") }
        val viaPath = LlamaBroHub.cacheDirFor(explicitConfig, noBackup)
        assertEquals(viaNull.canonicalPath, viaPath.canonicalPath)
        val first = LlamaBroHub.create(viaNull, viaNullConfig) { build(viaNull, viaNullConfig) }
        val again = LlamaBroHub.create(viaPath, explicitConfig) { build(viaPath, explicitConfig) }
        assertSame(first, again)
        assertEquals(1, builds)
    }

    @Test fun twoSpellingsOfOneFolderAreOneHub() {
        val dir = folder()
        val spelled = File(dir, "sub/../hub").also { File(dir, "sub").mkdirs() }
        val plain = File(dir, "hub")
        val first = create(spelled, configFor(spelled))
        val again = create(plain, configFor(plain))
        assertSame(first, again)
        assertEquals(1, builds)
    }

    @Test fun aThrowingBuildLeavesTheRegistryClean() {
        val dir = folder()
        assertThrows(IllegalArgumentException::class.java) {
            LlamaBroHub.create(dir, configFor(dir)) { throw IllegalArgumentException("boom") }
        }
        val hub = create(dir, configFor(dir))
        assertSame(hub, create(dir, configFor(dir)))
        assertEquals(1, builds)
    }

    @Test fun concurrentCreateBuildsOnce() {
        val dir = folder()
        val sharedLogger = Logger { _, _ -> }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results =
                (1..8).map {
                    pool.submit<LlamaBroHub> {
                        start.await()
                        val config =
                            HubConfig {
                                cacheDir = dir
                                logger = sharedLogger
                            }
                        create(dir, config)
                    }
                }
            start.countDown()
            val hubs = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, builds)
            assertTrue(hubs.all { it === hubs.first() })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun hubConfigEquality() {
        val dir = File("cache")
        val sharedLogger = Logger { _, _ -> }
        val sharedToken: suspend () -> String? = { null }

        fun config(
            endpoint: String = "https://example.test/",
            cacheDir: File? = dir,
            interval: kotlin.time.Duration = 2.seconds,
            logger: Logger? = sharedLogger,
            token: (suspend () -> String?)? = sharedToken,
        ) = HubConfig {
            this.endpoint = endpoint
            this.cacheDir = cacheDir
            this.progressInterval = interval
            this.logger = logger
            token?.let { token(it) }
        }
        assertEquals(config(), config())
        assertEquals(config().hashCode(), config().hashCode())
        assertNotEquals(config(), config(endpoint = "https://other.test/"))
        assertNotEquals(config(), config(cacheDir = File("other")))
        assertNotEquals(config(), config(cacheDir = null))
        assertNotEquals(config(), config(interval = 3.seconds))
        assertNotEquals(config(), config(logger = Logger { _, _ -> }))
        assertNotEquals(config(), config(token = { "t" }))
    }
}
