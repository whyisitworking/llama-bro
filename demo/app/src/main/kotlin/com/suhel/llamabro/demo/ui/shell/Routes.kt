package com.suhel.llamabro.demo.ui.shell

import java.net.URLEncoder

/** String routes: the app avoids the serialization plugin, so no type-safe route objects. */
object Routes {
    const val FIRST_RUN = "first_run"
    const val CHATS = "chats"
    const val MODELS = "models"
    const val GET_MODELS = "get_models"
    const val QUERY_ARG = "q"
    const val GET_MODELS_PATTERN = "get_models?q={q}"
    const val SETTINGS = "settings"
    const val APPEARANCE = "settings/appearance"
    const val INSTRUCTIONS = "settings/instructions"
    const val DEFAULT_MODEL = "settings/default_model"
    const val TOKEN = "settings/token"
    const val ABOUT = "settings/about"

    /** The id of a chat that doesn't exist yet: it is created with the first message. */
    const val NEW_CHAT_ID = 0L

    const val CHAT_ARG = "chatId"
    const val CHAT_PATTERN = "chat/{$CHAT_ARG}"

    /** Get models, searching for [query] on arrival (first run's search bar); empty shows the suggestions. */
    fun getModels(query: String = "") = "get_models?q=" + URLEncoder.encode(query, "UTF-8")

    /** `chat/0` is a new chat: it is created with the first message. */
    fun chat(id: Long) = "chat/$id"

    /** The chat id of a `chat/{chatId}` route, or null for other routes. */
    fun chatIdOf(route: String?): Long? =
        route?.takeIf { it.startsWith("chat/") }?.removePrefix("chat/")?.toLongOrNull()
}
