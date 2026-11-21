package com.suhel.llamabro.demo.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One DAO for the cache's seven tables, readers and writers each, so it is long by construction. */
@Suppress("TooManyFunctions")
@Dao
interface HubCacheDao {
    /** A search's stored rows in the Hub's order, a page at a time, for the Models page's Pager. */
    @Query("SELECT * FROM hub_result WHERE search = :search ORDER BY position")
    fun results(search: String): PagingSource<Int, HubResultEntity>

    @Query("SELECT * FROM hub_result WHERE search = :search ORDER BY position")
    suspend fun resultsOf(search: String): List<HubResultEntity>

    @Query("SELECT * FROM hub_result WHERE search = :search AND groupKey IN (:groupKeys)")
    suspend fun resultsOf(search: String, groupKeys: List<String>): List<HubResultEntity>

    @Query("SELECT MAX(position) FROM hub_result WHERE search = :search")
    suspend fun lastPosition(search: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putResults(entities: List<HubResultEntity>)

    @Query("DELETE FROM hub_result WHERE search = :search")
    suspend fun deleteResults(search: String)

    @Query("SELECT * FROM hub_search_page WHERE search = :search")
    suspend fun searchPage(search: String): HubSearchPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSearchPage(entity: HubSearchPageEntity)

    @Query("SELECT * FROM hub_repo WHERE repo = :repo")
    fun repo(repo: String): Flow<HubRepoEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRepo(entity: HubRepoEntity)

    @Query("SELECT * FROM hub_files WHERE repo = :repo")
    fun files(repo: String): Flow<HubFilesEntity?>

    /** The cached listings of [repos], in one query: the library reads its models' image readers from them. */
    @Query("SELECT * FROM hub_files WHERE repo IN (:repos)")
    fun filesOf(repos: List<String>): Flow<List<HubFilesEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFiles(entity: HubFilesEntity)

    @Query("SELECT * FROM hub_page WHERE repo = :repo")
    fun page(repo: String): Flow<HubPageEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPage(entity: HubPageEntity)

    @Query("SELECT * FROM recent_search ORDER BY searchedAt DESC LIMIT :limit")
    fun recentSearches(limit: Int): Flow<List<RecentSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecentSearch(entity: RecentSearchEntity)

    /** Keeps the [keep] newest searches. */
    @Query(
        "DELETE FROM recent_search WHERE `query` NOT IN " +
            "(SELECT `query` FROM recent_search ORDER BY searchedAt DESC LIMIT :keep)",
    )
    suspend fun trimRecentSearches(keep: Int)

    @Query("SELECT * FROM download_record WHERE model = :model")
    fun record(model: String): Flow<DownloadRecordEntity?>

    @Query("SELECT * FROM download_record ORDER BY startedAt")
    fun records(): Flow<List<DownloadRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecord(entity: DownloadRecordEntity)

    @Query("DELETE FROM download_record WHERE model = :model")
    suspend fun deleteRecord(model: String)
}
