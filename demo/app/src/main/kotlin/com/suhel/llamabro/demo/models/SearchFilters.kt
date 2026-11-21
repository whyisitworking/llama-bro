package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.SearchSort

/** The order Hugging Face lists search results in (models-search design §5), kept as the Hub gives it. */
enum class SearchOrder(val sort: SearchSort) {
    MostDownloaded(SearchSort.Downloads),
    Trending(SearchSort.Trending),
    MostLiked(SearchSort.Likes),
    Newest(SearchSort.Newest),
    RecentlyUpdated(SearchSort.RecentlyUpdated),
}

/** Which parameter counts the search asks for (models-search design §3, §5). */
sealed interface SizeFilter {
    /** Up to the largest count that isn't Too big here ([Guess.fitCap], F176): the default. */
    data object FitsPhone : SizeFilter

    /** No cap. */
    data object Any : SizeFilter

    /** The person's own range; either end open. */
    data class Range(val min: Long?, val max: Long?) : SizeFilter

    /** As stored in the settings: `fits`, `any`, or `range:<min>:<max>` with an empty end for an open one. */
    fun encode(): String =
        when (this) {
            FitsPhone -> FITS
            Any -> ANY
            is Range -> "$RANGE:${min ?: ""}:${max ?: ""}"
        }

    companion object {
        private const val FITS = "fits"
        private const val ANY = "any"
        private const val RANGE = "range"

        /** [encode]'s text back; anything it can't read is the default, so a bad value never stops the search. */
        fun decode(text: String?): SizeFilter {
            val parts = text?.split(':').orEmpty()
            return when {
                text == ANY -> {
                    Any
                }

                parts.size == RANGE_PARTS && parts[0] == RANGE -> {
                    val min = parts[1].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return FitsPhone }
                    val max = parts[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return FitsPhone }
                    Range(min, max)
                }

                else -> {
                    FitsPhone
                }
            }
        }

        private const val RANGE_PARTS = 3
    }
}

/**
 * One change to the search's filters, applied to the stored ones ("Newest", whatever the rest are), so changes made
 * faster than the store writes them compose rather than one copying a stale value over another (D128).
 */
typealias FiltersChange = (SearchFilters) -> SearchFilters

/**
 * The search's filters (models-search design §5), remembered in the settings. They shape only what Hugging Face is
 * asked; the models on the phone and the suggestions are never filtered by them (§6).
 */
data class SearchFilters(
    val sort: SearchOrder = SearchOrder.MostDownloaded,
    val size: SizeFilter = SizeFilter.FitsPhone,
    val seesImages: Boolean = false,
) {
    /** Whether every filter is as it starts: the filter action shows a dot when not. */
    val isDefault: Boolean get() = this == SearchFilters()

    /** What the Hub is asked for [text] on a phone with [facts]: "Fits this phone" becomes its cap here. */
    fun query(text: String, facts: DeviceFacts): HubQuery {
        val (min, max) =
            when (size) {
                SizeFilter.FitsPhone -> null to Guess.fitCap(facts)
                SizeFilter.Any -> null to null
                is SizeFilter.Range -> size.min to size.max
            }
        return HubQuery(text.trim().lowercase(), sort.sort, min, max, seesImages)
    }
}

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
