package com.suhel.llamabro.demo.data.repository

import android.content.Context
import com.suhel.llamabro.demo.di.ApplicationScope
import com.suhel.llamabro.demo.model.CURATED_MODELS
import com.suhel.llamabro.demo.model.CurrentInferenceContext
import com.suhel.llamabro.demo.model.Model
import com.suhel.llamabro.demo.model.ModelDownloadState
import com.suhel.llamabro.sdk.LlamaEngine
import com.suhel.llamabro.sdk.model.ModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ModelRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope
) {
    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }

    private val downloadModelTrigger = MutableSharedFlow<Model>(
        extraBufferCapacity = 64
    )

    private val loadModelTrigger = MutableSharedFlow<Model?>(
        extraBufferCapacity = 1
    )

    private val downloadStateMap: StateFlow<Map<Model, ModelDownloadState>> =
        merge(
            downloadModelTrigger.flatMapMerge { model ->
                model.download().map { state ->
                    model to state
                }
            },
            CURATED_MODELS.map { model ->
                model to model.downloadState()
            }.asFlow()
        )
            .scan(emptyMap<Model, ModelDownloadState>()) { acc, pair -> acc + pair }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val currentInferenceContextFlow = loadModelTrigger
        .distinctUntilChanged()
        .flatMapLatest { model ->
            if (model != null) {
                LlamaEngine.createFlow(
                    ModelConfig(
                        modelPath = model.file().absolutePath,
                        promptFormat = model.promptFormat
                    )
                ).map { engine -> CurrentInferenceContext(model, engine) }
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000, 0),
            initialValue = null
        )

    fun getAllModels(): List<Model> = CURATED_MODELS

    fun getStateFor(model: Model): Flow<ModelDownloadState> = downloadStateMap.map {
        it.getOrDefault(model, ModelDownloadState.NotDownloaded)
    }

    fun startDownload(model: Model) {
        downloadModelTrigger.tryEmit(model)
    }

    fun loadModel(model: Model) {
        loadModelTrigger.tryEmit(model)
    }

    fun ejectModel() {
        loadModelTrigger.tryEmit(null)
    }

    private fun Model.downloadState(): ModelDownloadState =
        if (this.file().exists()) {
            ModelDownloadState.Downloaded
        } else {
            ModelDownloadState.NotDownloaded
        }

    private fun Model.file(): File = File(modelsDir, "${this.id}.gguf")

    private fun Model.download(): Flow<ModelDownloadState> = flow {
        emit(ModelDownloadState.Downloading(0f))
        val dest = this@download.file()
        try {
            val url = URL(this@download.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000

                if (dest.exists()) setRequestProperty("Range", "bytes=${dest.length()}-")
                connect()
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0 }
            val startBytes = if (dest.exists()) dest.length() else 0L
            val grandTotal = totalBytes?.let { it + startBytes }

            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    var downloaded = startBytes
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read

                        if (grandTotal != null) {
                            emit(ModelDownloadState.Downloading(downloaded.toFloat() / grandTotal))
                        }
                    }
                }
            }
            emit(ModelDownloadState.Downloaded)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                emit(ModelDownloadState.Error(e.message ?: "Download failed"))
            }
        }
    }.flowOn(Dispatchers.IO)
}
