package com.suhel.llamabro.demo.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.suhel.llamabro.demo.data.db.ChatDatabase
import com.suhel.llamabro.demo.data.db.DownloadRecordEntity
import com.suhel.llamabro.demo.data.db.HubCacheDao
import com.suhel.llamabro.demo.data.db.HubResultEntity
import com.suhel.llamabro.demo.data.db.HubSearchPageEntity
import com.suhel.llamabro.demo.data.db.RecentSearchEntity
import com.suhel.llamabro.demo.models.toRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Hub cache's tables: what is put comes back, the recent searches keep the newest few, records come and go. */
@RunWith(RobolectricTestRunner::class)
class HubCacheDaoTest {
    private lateinit var db: ChatDatabase
    private lateinit var dao: HubCacheDao

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        dao = db.hubCacheDao()
    }

    @After fun close() = db.close()

    @Test fun aSearchsRowsReadInTheHubsOrderAndOnlyItsOwn() =
        runBlocking {
            dao.putResults(
                listOf(
                    HubResultEntity("qwen|x", "b", 1, "{}"),
                    HubResultEntity("qwen|x", "a", 0, "{}"),
                    HubResultEntity("gemma|x", "c", 0, "{}"),
                ),
            )
            assertEquals(listOf("a", "b"), dao.resultsOf("qwen|x").map { it.groupKey })
            assertEquals(1, dao.lastPosition("qwen|x"))
            assertNull(dao.lastPosition("phi|x"))
            dao.deleteResults("qwen|x")
            assertEquals(emptyList<HubResultEntity>(), dao.resultsOf("qwen|x"))
            assertEquals(listOf("c"), dao.resultsOf("gemma|x").map { it.groupKey })
        }

    @Test fun aSearchsCursorIsReadBack() =
        runBlocking {
            assertNull(dao.searchPage("qwen|x"))
            dao.putSearchPage(HubSearchPageEntity("qwen|x", "https://hub.test/next", 5))
            assertEquals("https://hub.test/next", dao.searchPage("qwen|x")?.next)
            dao.putSearchPage(HubSearchPageEntity("qwen|x", null, 9))
            assertNull("the last page stored", dao.searchPage("qwen|x")!!.next)
        }

    @Test fun recentSearchesKeepTheNewestEightNewestFirst() =
        runBlocking {
            repeat(
                10,
            ) { dao.putRecentSearch(RecentSearchEntity(query = "q$it", text = "Q$it", searchedAt = it.toLong())) }
            dao.trimRecentSearches(8)
            val kept = dao.recentSearches(20).first()
            assertEquals((9 downTo 2).map { "q$it" }, kept.map { it.query })
            assertEquals(listOf("q9", "q8"), dao.recentSearches(2).first().map { it.query })
        }

    @Test fun aRecordRoundTrips() =
        runBlocking {
            val record = DownloadRecordEntity("r/m:Q4", "M", null, 3_400_000_000, 7)
            dao.putRecord(record)
            assertEquals(record, dao.record("r/m:Q4").first())
            assertEquals(listOf(record), dao.records().first())
            dao.deleteRecord("r/m:Q4")
            assertNull(dao.record("r/m:Q4").first())
            assertEquals(emptyList<DownloadRecordEntity>(), dao.records().first())
        }

    /** Version 6: a record keeps the model file's bytes beside its total, and reads them back after a restart. */
    @Test fun aRecordKeepsItsModelBytes() =
        runBlocking {
            val record = DownloadRecordEntity("r/m:Q8_0", "M", "Google", 4_400_000_000, 7, modelBytes = 4_130_000_000)
            dao.putRecord(record)
            assertEquals(4_130_000_000L, dao.record("r/m:Q8_0").first()!!.modelBytes)
            assertEquals(
                4_130_000_000L,
                dao
                    .records()
                    .first()
                    .single()
                    .toRecord()!!
                    .modelBytes,
            )
        }
}
