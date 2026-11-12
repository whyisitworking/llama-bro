package com.suhel.llamabro.demo.testing

import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.HubGateway
import com.suhel.llamabro.demo.models.ModelDiscovery
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
    /** A fake engine and hub for screens that need no model; null uses the real ones. */
    val llamaBro: LlamaBro? = null,
    val hub: HubGateway? = null,
    val streamer: ((Conversation, String, Array<Content.Image>, ThinkLevel?) -> Flow<ChatEvent>)? = null,
    /** Records downloads instead of starting WorkManager's; null uses the real one. */
    val scheduler: DownloadScheduler? = null,
    /** A fake Hugging Face (search and a repo's files); null uses the real one. */
    val discovery: ModelDiscovery? = null,
    /** Tests never meet the system's notification dialog, except the journeys about that dialog. */
    val asksForNotifications: Boolean = false,
) {
    companion object {
        fun fresh(
            clock: () -> Long = System::currentTimeMillis,
            llamaBro: LlamaBro? = null,
            hub: HubGateway? = null,
            streamer: ((Conversation, String, Array<Content.Image>, ThinkLevel?) -> Flow<ChatEvent>)? = null,
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
