package com.suhel.llamabro.server

import com.suhel.llamabro.Model

/** What the server serves: the app decides which models exist and which one a request gets. */
public interface ServedModels {
    /** What `GET /v1/models` lists. */
    public suspend fun list(): List<ServedModel>

    /**
     * The model for a request's `model` field (null: the default). The server calls [ServedLease.release] when the
     * request ends, however it ends.
     *
     * @throws ModelUnavailable for an id the app doesn't serve, or when none is loaded.
     */
    public suspend fun acquire(id: String?): ServedLease
}

/** One entry of `GET /v1/models`; [created] is Unix seconds. */
public data class ServedModel(val id: String, val created: Long)

/** A model held for one request. */
public interface ServedLease {
    public val model: Model

    public fun release()
}

/** [ServedModels.acquire] has no model for [id]. */
public class ModelUnavailable(public val id: String?) : Exception(if (id == null) "no model loaded" else "no model $id")
