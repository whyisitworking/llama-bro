package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.HfModel

/**
 * Whether [key] is a model a chat can talk to: not the Library's embedder (in any quant), nor a model named for
 * embedding or reranking, which loads with `ModelTask.Embedding` or `ModelTask.Rerank` and has no chat to give. The one
 * test the chat's model switcher, the Models page and the Server page share, so an embedder downloaded for the Library
 * is never offered as a chat model.
 */
fun isChatModel(key: HfModel): Boolean {
    val repo = key.repo.lowercase()
    return repo !=
        Catalog.embedder.key.repo
            .lowercase() && NOT_CHAT.none { it in repo }
}

/** The chat models of a list, in its order ([isChatModel]). */
fun List<LibraryModel>.forChat(): List<LibraryModel> = filter { isChatModel(it.key) }

private val NOT_CHAT = listOf("embed", "rerank")
