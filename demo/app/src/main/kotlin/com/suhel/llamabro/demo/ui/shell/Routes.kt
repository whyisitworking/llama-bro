package com.suhel.llamabro.demo.ui.shell

import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.hub.HfModel
import java.net.URLEncoder

/** String routes: the app avoids the serialization plugin, so no type-safe route objects. */
object Routes {
    const val CHATS = "chats"
    const val QUERY_ARG = "q"
    const val MODELS = "models?q={q}"
    const val SETTINGS = "settings"
    const val APPEARANCE = "settings/appearance"
    const val INSTRUCTIONS = "settings/instructions"
    const val DEFAULT_MODEL = "settings/default_model"
    const val TOKEN = "settings/token"
    const val MEMORY = "settings/memory"
    const val VOICE = "settings/voice"
    const val SAMPLING = "settings/sampling"

    /** One model's values; the key is URL-encoded, as it holds a slash and a colon. */
    const val SAMPLING_KEY_ARG = "key"
    const val SAMPLING_MODEL = "settings/sampling/{$SAMPLING_KEY_ARG}"

    fun samplingModel(key: String) = "settings/sampling/" + android.net.Uri.encode(key)

    const val ABOUT = "settings/about"

    /** Settings › Appearance › Glass (D152). */
    const val GLASS = "settings/appearance/glass"

    /** The id of a chat that doesn't exist yet: it is created with the first message. */
    const val NEW_CHAT_ID = 0L

    const val CHAT_ARG = "chatId"

    /** The model a new chat starts on (a model card's "Chat with"); URL-encoded, as it holds a slash and a colon. */
    const val CHAT_MODEL_ARG = "model"
    const val CHAT_PATTERN = "chat/{$CHAT_ARG}?$CHAT_MODEL_ARG={$CHAT_MODEL_ARG}"

    /** Models, searching for [query] on arrival; empty shows the browse sections. */
    fun models(query: String = "") = "models?q=" + URLEncoder.encode(query, "UTF-8")

    /**
     * Where the app starts: the Models page on a fresh install, whose empty state first run is (models-page design
     * §2, §7); else a new chat in the Sidebar layout, the chat list in the other.
     */
    fun start(startsWithFirstRun: Boolean, layout: AppLayout): String =
        when {
            startsWithFirstRun -> MODELS
            layout == AppLayout.Sidebar -> chat(NEW_CHAT_ID)
            else -> CHATS
        }

    /** `chat/0` is a new chat: it is created with the first message. */
    fun chat(id: Long) = "chat/$id"

    /** A new chat on [key], whatever the default model is. */
    fun newChatWith(key: HfModel) = "chat/$NEW_CHAT_ID?$CHAT_MODEL_ARG=" + URLEncoder.encode(key.toString(), "UTF-8")

    /** The chat id of a `chat/{chatId}` route, or null for other routes. */
    fun chatIdOf(route: String?): Long? =
        route
            ?.takeIf { it.startsWith("chat/") }
            ?.removePrefix("chat/")
            ?.substringBefore('?')
            ?.toLongOrNull()
}
