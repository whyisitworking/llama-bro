package com.suhel.llamabro.demo.models

import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.hub.SearchSort

/**
 * One search as Hugging Face is asked it: the query and every criterion resolved, so its stored pages are keyed by
 * [key] and a change of any filter is a list of its own. Gated repos are always left out (§4), so that isn't here.
 */
data class HubQuery(
    val text: String,
    val sort: SearchSort,
    val minParameters: Long?,
    val maxParameters: Long?,
    val seesImages: Boolean,
) {
    val key: String get() =
        listOf(
            text,
            sort.name,
            minParameters ?: "",
            maxParameters ?: "",
            seesImages,
        ).joinToString("|")
}

/** What the Hub is asked for [text] on a phone with [facts]: "Fits this phone" becomes its cap here. */
fun SearchFilters.query(text: String, facts: DeviceFacts): HubQuery {
    val (min, max) =
        when (size) {
            SizeFilter.FitsPhone -> null to Guess.fitCap(facts)
            SizeFilter.Any -> null to null
            is SizeFilter.Range -> size.min to size.max
        }
    return HubQuery(text.trim().lowercase(), sort.sort, min, max, seesImages)
}
