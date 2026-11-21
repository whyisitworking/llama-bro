package com.suhel.llamabro.demo.models

import com.suhel.llamabro.demo.data.db.DownloadRecordEntity
import com.suhel.llamabro.hub.HfModel

/**
 * What the person chose, kept from Get until the model lands or the download is cancelled: the hub knows only the
 * repo and quant, so the row and the card read the name, maker and whole size (model plus image reader) from here, in
 * Room, and show them from the first frame after a restart. [modelBytes] is the model file alone, the fit's speed
 * (one fit for one file, D121); 0 when not known (a record kept before it was stored).
 */
data class DownloadRecord(
    val model: HfModel,
    val name: String,
    val maker: String?,
    val totalBytes: Long,
    val modelBytes: Long = 0,
)

internal fun DownloadRecord.toEntity(startedAt: Long) =
    DownloadRecordEntity(model.toString(), name, maker, totalBytes, startedAt, modelBytes)

internal fun DownloadRecordEntity.toRecord(): DownloadRecord? =
    runCatching { DownloadRecord(HfModel.parse(model), name, maker, totalBytes, modelBytes) }.getOrNull()
