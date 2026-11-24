package com.suhel.llamabro.hub

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

internal class HubLoggerTest {
    @Before fun reset() = hub.reset()

    // D66: with no app logger the hub's warnings go to the default (logcat `llamabro-hub` in production); the app's
    // wins.
    @Test fun hubWarningsGoToDefaultLoggerWhenAppGivesNone() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-default-logger-").toFile()
            val config =
                HubConfig {
                    endpoint = hub.apiUrl
                    cacheDir = cache
                }
            val warnings = mutableListOf<String>()
            val client =
                LlamaBroHub(
                    config,
                    cache,
                    HttpTransport(hub.apiUrl, null, null),
                    JvmStorage(),
                    createSymlink = { _, _ -> throw IOException("symlinks unavailable") },
                    defaultLogger = Logger { level, message -> if (level == LogLevel.Warn) warnings += message },
                )
            client.download(HfModel("test/download")).toList()
            assertTrue(warnings.any { it.startsWith("failed to create symlink") })
        }

    @Test fun appLoggerWinsOverDefaultLogger() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-app-logger-").toFile()
            val app = mutableListOf<String>()
            val fallback = mutableListOf<String>()
            val config =
                HubConfig {
                    endpoint = hub.apiUrl
                    cacheDir = cache
                    logger = Logger { _, message -> app += message }
                }
            val client =
                LlamaBroHub(
                    config,
                    cache,
                    HttpTransport(hub.apiUrl, null, null),
                    JvmStorage(),
                    createSymlink = { _, _ -> throw IOException("symlinks unavailable") },
                    defaultLogger = Logger { _, message -> fallback += message },
                )
            client.download(HfModel("test/download")).toList()
            assertTrue(app.any { it.startsWith("failed to create symlink") })
            assertTrue(fallback.isEmpty())
        }

    private companion object {
        lateinit var hub: FakeHub

        @JvmStatic @BeforeClass
        fun startHub() {
            hub = FakeHub()
        }

        @JvmStatic @AfterClass
        fun stopHub() {
            hub.close()
        }
    }
}
