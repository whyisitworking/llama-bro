package com.suhel.llamabro.demo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The offline-first cache of Hugging Face facts and the records of downloads (docs/design/models-page.md §1). Every
 * `fetchedAt` is epoch milliseconds. The JSON columns hold kotlinx.serialization strings whose shapes belong to the
 * repository that reads them, so a shape can change without a schema change.
 */

/**
 * One model row of a Hugging Face search's pages (models-search design §1, §2): [search] is the query with its
 * filters (`HubQuery.key`), [position] the Hub's order of the row's first appearance, kept when a later page's upload
 * of the same model ([groupKey]) merges into it. [rowJson] is the row's facts.
 */
@Entity(
    tableName = "hub_result",
    primaryKeys = ["search", "groupKey"],
    indices = [Index(value = ["search", "position"])],
)
data class HubResultEntity(val search: String, val groupKey: String, val position: Int, val rowJson: String)

/** Where a search's next page starts: the Hub's cursor ([next], null once the last page is stored). */
@Entity(tableName = "hub_search_page")
data class HubSearchPageEntity(
    @PrimaryKey val search: String,
    val next: String?,
    val fetchedAt: Long,
)

/** A repo's facts. [repo] is lower case. */
@Entity(tableName = "hub_repo")
data class HubRepoEntity(
    @PrimaryKey val repo: String,
    val factsJson: String,
    val fetchedAt: Long,
)

/** A repo's files and their sizes. */
@Entity(tableName = "hub_files")
data class HubFilesEntity(
    @PrimaryKey val repo: String,
    val filesJson: String,
    val fetchedAt: Long,
)

/** A repo's model page: its README and the repo it was quantized from, either of which the repo may lack. */
@Entity(tableName = "hub_page")
data class HubPageEntity(
    @PrimaryKey val repo: String,
    val markdown: String?,
    val baseModel: String?,
    val fetchedAt: Long,
)

/** A query the user searched for, for the recent searches. [query] is normalised; [text] is as last typed. */
@Entity(tableName = "recent_search")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val text: String,
    val searchedAt: Long,
)

/**
 * A download in progress or paused, so the card and the row show its name and size from the first frame after a
 * restart. [model] is `HfModel.toString()`. [modelBytes] is the model file's share of [totalBytes] (the fit's speed);
 * 0 for a record kept before version 6, which didn't store it.
 */
@Entity(tableName = "download_record")
data class DownloadRecordEntity(
    @PrimaryKey val model: String,
    val name: String,
    val maker: String?,
    val totalBytes: Long,
    val startedAt: Long,
    @ColumnInfo(defaultValue = "0") val modelBytes: Long = 0,
)
