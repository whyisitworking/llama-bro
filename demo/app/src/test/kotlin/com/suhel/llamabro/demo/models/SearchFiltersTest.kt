package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.hub.SearchSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The search's filters as the Hub is asked (models-search design §3–5). */
class SearchFiltersTest {
    private val phone = DeviceFacts(4L shl 30, DeviceKind.Npu)

    @Test fun theDefaultIsMostDownloadedThatFitsThisPhoneAnyInput() {
        val filters = SearchFilters()
        assertEquals(SearchOrder.MostDownloaded, filters.sort)
        assertEquals(SizeFilter.FitsPhone, filters.size)
        assertFalse(filters.seesImages)
        assertTrue(filters.isDefault)
        assertFalse(filters.copy(seesImages = true).isDefault)
    }

    @Test fun fitsThisPhoneCapsTheParameterCount() {
        val query = SearchFilters().query(" Qwen ", phone)
        assertEquals("qwen", query.text)
        assertEquals(SearchSort.Downloads, query.sort)
        assertNull(query.minParameters)
        assertEquals(Guess.fitCap(phone), query.maxParameters)
        assertFalse(query.seesImages)
    }

    @Test fun anySizeHasNoCapAndARangeIsItsOwn() {
        val any = SearchFilters(size = SizeFilter.Any).query("qwen", phone)
        assertNull(any.minParameters)
        assertNull(any.maxParameters)
        val range = SearchFilters(size = SizeFilter.Range(1_000_000_000, 4_000_000_000)).query("qwen", phone)
        assertEquals(1_000_000_000L, range.minParameters)
        assertEquals(4_000_000_000L, range.maxParameters)
        val floor = SearchFilters(size = SizeFilter.Range(1_000_000_000, null)).query("qwen", phone)
        assertNull(floor.maxParameters)
    }

    @Test fun eachOrderIsTheHubsSort() {
        assertEquals(
            listOf(
                SearchSort.Downloads,
                SearchSort.Trending,
                SearchSort.Likes,
                SearchSort.Newest,
                SearchSort.RecentlyUpdated,
            ),
            SearchOrder.entries.map { it.sort },
        )
    }

    @Test fun aQueryIsKeyedByEverythingTheHubIsAsked() {
        val one = SearchFilters().query("Qwen", phone)
        assertEquals(one.key, SearchFilters().query(" qwen ", phone).key)
        val others =
            listOf(
                SearchFilters(sort = SearchOrder.Newest).query("qwen", phone),
                SearchFilters(size = SizeFilter.Any).query("qwen", phone),
                SearchFilters(seesImages = true).query("qwen", phone),
                SearchFilters().query("qwen", DeviceFacts(6L shl 30, DeviceKind.Npu)),
                SearchFilters().query("gemma", phone),
            )
        assertEquals(others.size + 1, (others.map { it.key } + one.key).toSet().size)
    }

    @Test fun aRangeSurvivesItsTextForm() {
        listOf(
            SizeFilter.FitsPhone,
            SizeFilter.Any,
            SizeFilter.Range(1_000_000_000, 4_000_000_000),
            SizeFilter.Range(null, 4_000_000_000),
            SizeFilter.Range(1_000_000_000, null),
        ).forEach { assertEquals(it, SizeFilter.decode(it.encode())) }
        assertEquals("anything unreadable is the default", SizeFilter.FitsPhone, SizeFilter.decode("range:x:y"))
        assertEquals(SizeFilter.FitsPhone, SizeFilter.decode(null))
    }
}
