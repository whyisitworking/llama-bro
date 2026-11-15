package com.suhel.llamabro.hub

import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.RemoteModelSource
import com.suhel.llamabro.hub.internal.HeaderFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** What [LlamaBroHub.model] returns: core fetches it before a load and reads its headers for a fit (D64, D75). */
internal class HubModelSource(
    private val hub: LlamaBroHub,
    private val model: HfModel,
    private val configure: PlanOptions.() -> Unit,
) : RemoteModelSource {
    /**
     * The cache first, with no request and no token lookup (the app's provider may itself use the network); on a miss
     * the hub's shared, resumable transfer, then the files it left (D64, F114-F116). Collected here, so [onProgress]
     * runs only while this does; a cancel detaches from the transfer and keeps its partial file.
     */
    override suspend fun fetch(onProgress: (DownloadProgress) -> Unit): ModelSource {
        cached()?.let { return it }
        hub.download(model, configure).collect { onProgress(it) }
        return hub.source(model, configure)
    }

    /**
     * A complete cached copy is its own header. Otherwise bounded GGUF headers of the model and drafts, and a sparse
     * placeholder for the image encoder (its size is all the projection reads), in a folder of their own that goes when
     * [block] ends, also when it throws or is cancelled (D42, F84, F88).
     */
    override suspend fun <T> withHeaders(block: suspend (local: ModelSource) -> T): T {
        cached()?.let { return block(it) }
        var folder: File? = null
        try {
            val stubs = withContext(Dispatchers.IO) { headerStubs { folder = it } }
            return block(stubs)
        } finally {
            folder?.let {
                withContext(NonCancellable + Dispatchers.IO) {
                    it.deleteRecursively()
                    it.parentFile?.delete() // the per-model folder, once no other estimate uses it
                }
            }
        }
    }

    /** The complete cached copy, read from disk only, or null when the phone doesn't hold all of it. */
    private suspend fun cached(): ModelSource? =
        try {
            hub.source(model, configure)
        } catch (_: HubException.NotFound) {
            null
        }

    /** Fetches the stubs into a new folder, handed to [created] before the first byte so a failure still removes it. */
    private suspend fun headerStubs(created: (File) -> Unit): ModelSource {
        val transport = hub.operationTransport()
        // A look, not a download: the cache's refs and plan records stay on the copy they name.
        val plan = hub.planInternal(model, PlanOptions().apply(configure), transport, record = false)
        val models = plan.files.filter { it.role == HubFile.Role.Model }
        val primary = models.firstOrNull() ?: throw HubException.NotFound("plan has no model GGUF", emptyList())
        val drafts = plan.files.filter { it.role == HubFile.Role.Draft }
        val projector = plan.files.firstOrNull { it.role == HubFile.Role.Mmproj }
        val root = File(hub.cacheDir, ".llamabro/estimate/${primary.oid ?: "offline"}/${UUID.randomUUID()}")
        created(root)
        val headers = HeaderFetch(hub.config.endpoint, transport)

        fun destination(role: String, file: HubFile): File = File(root, "$role/${file.path}")
        for (file in models) headers.fetch(file, destination("model", file))
        for (file in drafts) headers.fetch(file, destination("draft", file))
        val mmproj =
            projector?.let { file ->
                val target = destination("mmproj", file)
                requireNotNull(target.parentFile).mkdirs()
                RandomAccessFile(target, "rw").use { it.setLength(file.sizeBytes) }
                target.absolutePath
            }
        return ModelSource.file(destination("model", primary).absolutePath) {
            this.mmproj = mmproj
            this.draft = drafts.firstOrNull()?.let { destination("draft", it).absolutePath }
        }
    }

    override fun toString(): String = "hub:$model"
}
