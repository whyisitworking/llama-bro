package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.models.SearchFilters
import com.suhel.llamabro.demo.models.SearchOrder
import com.suhel.llamabro.demo.models.SizeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The filters' words on the page and the custom range's check, in billions of parameters (models-search design §5). */
class FilterWordsTest {
    @Test
    fun theDefaultsSayNothingAndAChangeNamesOnlyWhatIsOn() {
        assertNull(filtersLine(SearchFilters()))
        assertEquals("Trending", filtersLine(SearchFilters(sort = SearchOrder.Trending)))
        assertEquals(
            "Recently updated · 1.5–8 B · Sees images",
            filtersLine(
                SearchFilters(SearchOrder.RecentlyUpdated, SizeFilter.Range(1_500_000_000, 8_000_000_000), true),
            ),
        )
        assertEquals("Up to 4 B", filtersLine(SearchFilters(size = SizeFilter.Range(null, 4_000_000_000))))
        assertEquals("From 0.5 B", filtersLine(SearchFilters(size = SizeFilter.Range(500_000_000, null))))
    }

    @Test
    fun aRangeIsTwoNumbersOfBillionsMoreThanZeroTheFirstNoMoreThanTheSecond() {
        assertEquals(RangeCheck.Valid(SizeFilter.Range(1_000_000_000, 4_000_000_000)), RangeDraft().check)
        assertEquals(
            RangeCheck.Valid(SizeFilter.Range(500_000_000, 500_000_000)),
            RangeDraft(" 0,5", "0.5 ").check,
        )
        assertEquals(RangeCheck.Invalid(RANGE_NOT_NUMBERS), RangeDraft("", "4").check)
        assertEquals(RangeCheck.Invalid(RANGE_NOT_NUMBERS), RangeDraft("a", "4").check)
        assertEquals(RangeCheck.Invalid(RANGE_NOT_POSITIVE), RangeDraft("0", "4").check)
        assertEquals(RangeCheck.Invalid(RANGE_NOT_POSITIVE), RangeDraft("-1", "4").check)
        assertEquals(RangeCheck.Invalid(RANGE_REVERSED), RangeDraft("8", "4").check)
    }

    @Test
    fun aDraftStartsFromTheRangeInUseElseOneToFour() {
        assertEquals(RangeDraft("1.5", "8"), RangeDraft.of(SizeFilter.Range(1_500_000_000, 8_000_000_000)))
        assertEquals(RangeDraft(), RangeDraft.of(SizeFilter.Any))
        assertEquals(RangeDraft(), RangeDraft.of(SizeFilter.FitsPhone))
    }
}
