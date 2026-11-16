package com.suhel.llamabro.demo.testing

import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.chat.SampleSource
import com.suhel.llamabro.demo.chat.Streamer
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * What a test changes about the app's container. Every config names its own database, DataStore, token and image
 * files, so tests never see each other's data. Later tasks add the engine, the hub endpoint and the scheduler.
 */
data class TestConfig(
    val databaseName: String,
    val settingsName: String,
    val tokenFileName: String,
    val imagesDirName: String,
    val clock: () -> Long,
    /** A fake engine and hub (`FakeLlamaBroHub`'s) for screens that need no model; null uses the real ones. */
    val llamaBro: LlamaBro? = null,
    val hub: LlamaBroHub? = null,
    val streamer: Streamer? = null,
    /** Records downloads instead of starting WorkManager's; null uses the real one. */
    val scheduler: DownloadScheduler? = null,
    /** Search and a repo's files over the fake hub, with sizes it can't hold; null uses the app's own. */
    val discovery: ModelDiscovery? = null,
    /** Tests never meet the system's notification dialog, except the journeys about that dialog. */
    val asksForNotifications: Boolean = false,
    /** Tests never record the room: a voice message's samples come from a script (D76). */
    val microphone: () -> SampleSource = { ScriptedVoice() },
) {
    companion object {
        fun fresh(
            clock: () -> Long = System::currentTimeMillis,
            llamaBro: LlamaBro? = null,
            hub: LlamaBroHub? = null,
            streamer: Streamer? = null,
            scheduler: DownloadScheduler? = null,
            discovery: ModelDiscovery? = null,
            asksForNotifications: Boolean = false,
        ): TestConfig {
            val id = UUID.randomUUID().toString().take(8)
            return TestConfig(
                "chats-$id.db",
                "settings-$id",
                "hf_token-$id.bin",
                "images-$id",
                clock,
                llamaBro,
                hub,
                streamer,
                scheduler,
                discovery,
                asksForNotifications,
            )
        }
    }
}

/** A steady voice for [blocks] reads of 50 ms, then the end, as a microphone taken by a call ends (D76). */
class ScriptedVoice(private val blocks: Int = 20) : SampleSource {
    private var reads = 0
    override val rate = 16_000

    override fun read(buffer: ShortArray): Int {
        if (reads++ >= blocks) return -1
        buffer.indices.forEach { buffer[it] = if (it % 2 == 0) 4_000 else -4_000 }
        return buffer.size
    }

    override fun close() = Unit
}
