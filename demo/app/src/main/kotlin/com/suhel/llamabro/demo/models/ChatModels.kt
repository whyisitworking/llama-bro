package com.suhel.llamabro.demo.models

import com.suhel.llamabro.demo.data.ModelSenses
import com.suhel.llamabro.hub.HfModel

/**
 * Whether [key] is a model a chat can talk to: not the Library's embedder (in any quant); else, once its [header] has
 * been read (D155), what it says: a GGUF with no chat template is an embedding or rerank model, which loads with
 * `ModelTask.Embedding` or `ModelTask.Rerank`; else, unread, not a model named for embedding or reranking. The one test
 * behind the chat's model switcher, the Models page and the Server page ([LibraryModel.chats]), so an embedder
 * downloaded for the Library is never offered as a chat model.
 */
fun isChatModel(key: HfModel, header: ModelSenses? = null): Boolean {
    val repo = key.repo.lowercase()
    if (repo ==
        Catalog.embedder.key.repo
            .lowercase()
    ) {
        return false
    }
    return header?.chats ?: NOT_CHAT.none { it in repo }
}

/** The chat models of a list, in its order ([LibraryModel.chats]). */
fun List<LibraryModel>.forChat(): List<LibraryModel> = filter { it.chats }

private val NOT_CHAT = listOf("embed", "rerank")
