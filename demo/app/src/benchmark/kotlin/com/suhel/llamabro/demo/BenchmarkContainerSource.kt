package com.suhel.llamabro.demo

import android.util.Log
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.benchmark.BenchmarkDiscovery
import com.suhel.llamabro.demo.benchmark.BenchmarkHub
import com.suhel.llamabro.demo.benchmark.BenchmarkSeed
import com.suhel.llamabro.demo.benchmark.NoDownloads
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The container of the `benchmarkRelease` and `nonMinifiedRelease` builds: the real app, release-like, over the SDK's
 * fake engine, its fake hub holding two models "on the phone", and chats seeded once ([BenchmarkSeed]). UI cost is
 * measured alone: no model load, no thermal noise from inference, no network.
 */
class BenchmarkContainerSource : ContainerSource {
    private val fake = FakeLlamaBro()
    private val hub = BenchmarkHub()

    override fun create(app: DemoApp): AppContainer =
        AppContainer(
            app = app,
            llamaBro = fake.llamaBro,
            hub = hub.hub,
            scheduler = NoDownloads,
            databaseName = "benchmark.db",
            settingsName = "benchmark-settings",
            discoveryOverride = BenchmarkDiscovery(hub.hub, fake.llamaBro),
            asksForNotifications = false,
            streamer = { _, _, _, _ -> stream() },
        )

    /**
     * Puts the two models in the fake hub and seeds on first launch, off the main thread; the benchmarks wait for the
     * Big chat to show in the list, which comes after the models are listed.
     */
    override fun onStart(app: DemoApp) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                hub.install()
                app.container.models.changed()
                BenchmarkSeed.seedOnce(app, app.container)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ContainerFactory", "seeding failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** A reply of markdown with a code block, 60 tokens a second for 20 seconds. */
    private fun stream() =
        flow {
            for (word in BenchmarkSeed.replyText(TOKENS).split(' ')) {
                emit(ChatEvent.ContentDelta("$word "))
                delay(TOKEN_MILLIS)
            }
        }

    private companion object {
        const val TOKENS = 1_200
        const val TOKEN_MILLIS = 1_000L / 60
    }
}
