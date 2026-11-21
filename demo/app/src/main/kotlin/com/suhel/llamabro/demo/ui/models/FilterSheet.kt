package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.suhel.llamabro.demo.designsystem.components.ChoiceChips
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.NumberField
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.ScrollStack
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.SwitchRow
import com.suhel.llamabro.demo.designsystem.components.Unfold
import com.suhel.llamabro.demo.designsystem.components.rememberDemoSheetState
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.models.FiltersChange
import com.suhel.llamabro.demo.models.SearchFilters
import com.suhel.llamabro.demo.models.SearchOrder
import com.suhel.llamabro.demo.models.SizeFilter
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

// The search's filters (models-search design §5): the sheet the field's filter action opens, and their words.

internal const val FILTER_SHEET = "filter_sheet"
internal const val RANGE_NOT_NUMBERS = "Enter both sizes as numbers."
internal const val RANGE_NOT_POSITIVE = "Both must be more than 0."
internal const val RANGE_REVERSED = "From must be no more than To."

/** The sort's words, in the order the sheet offers them (models-search design §5). */
internal val SearchOrder.words: String
    get() =
        when (this) {
            SearchOrder.MostDownloaded -> "Most downloaded"
            SearchOrder.Trending -> "Trending"
            SearchOrder.MostLiked -> "Most liked"
            SearchOrder.Newest -> "Newest"
            SearchOrder.RecentlyUpdated -> "Recently updated"
        }

private val sizeChoices = listOf("Fits this phone", "Any size", "Custom")

/**
 * The filters that differ from the defaults, named in one line under "From Hugging Face" ("Newest · Any size"); null
 * when every one is at its default, so the defaults are never spelled out.
 */
internal fun filtersLine(filters: SearchFilters): String? =
    listOfNotNull(
        filters.sort.takeIf { it != SearchOrder.MostDownloaded }?.words,
        when (val size = filters.size) {
            SizeFilter.FitsPhone -> null
            SizeFilter.Any -> "Any size"
            is SizeFilter.Range -> rangeWords(size)
        },
        "Sees images".takeIf { filters.seesImages },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** A range as the person typed it, in billions: "1–4 B", or with an open end (an older setting) "Up to 4 B". */
internal fun rangeWords(range: SizeFilter.Range): String {
    val min = range.min?.let(::billions)
    val max = range.max?.let(::billions)
    return when {
        min != null && max != null -> "$min–$max B"
        max != null -> "Up to $max B"
        min != null -> "From $min B"
        else -> "Any size"
    }
}

/** A parameter count in billions, as few digits as it needs: 1500000000 is "1.5". */
private fun billions(count: Long): String =
    BigDecimal
        .valueOf(count)
        .divide(BigDecimal.valueOf(BILLION), 2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

/** What the custom range's two fields hold: text, so a half-typed "1." stays as typed. */
@Immutable
internal data class RangeDraft(val min: String = "1", val max: String = "4") {
    /** Whether the two make a range: two numbers of billions, both more than 0, the first no more than the second. */
    val check: RangeCheck
        get() {
            val low = min.billionsOrNull()
            val high = max.billionsOrNull()
            if (low == null || high == null) return RangeCheck.Invalid(RANGE_NOT_NUMBERS)
            return when {
                low <= 0 || high <= 0 -> RangeCheck.Invalid(RANGE_NOT_POSITIVE)
                low > high -> RangeCheck.Invalid(RANGE_REVERSED)
                else -> RangeCheck.Valid(SizeFilter.Range(low, high))
            }
        }

    companion object {
        /** The range in use as the fields show it, else the 1 to 4 billion a phone usually runs. */
        fun of(size: SizeFilter): RangeDraft =
            if (size is SizeFilter.Range) {
                RangeDraft(size.min?.let(::billions).orEmpty(), size.max?.let(::billions).orEmpty())
            } else {
                RangeDraft()
            }

        val Saver = listSaver<RangeDraft, String>({ listOf(it.min, it.max) }, { RangeDraft(it[0], it[1]) })
    }
}

/** A count from "1.5" or "1,5" billions; null for anything that isn't a number. */
private fun String.billionsOrNull(): Long? =
    trim()
        .replace(',', '.')
        .toBigDecimalOrNull()
        ?.multiply(BigDecimal.valueOf(BILLION))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.toLong()

private const val BILLION = 1_000_000_000L

/** The custom range's two fields read: a range to search, or why not. */
internal sealed interface RangeCheck {
    data class Valid(val range: SizeFilter.Range) : RangeCheck

    data class Invalid(val reason: String) : RangeCheck
}

/**
 * The search's filters in a sheet over the page: each choice applies at once (the list behind it follows), Reset puts
 * the defaults back and Done closes it. A plain sheet: it is short enough not to need to grow.
 */
@Composable
internal fun FilterSheet(filters: SearchFilters, onFilters: (FiltersChange) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDemoSheetState()
    val scope = rememberCoroutineScope()
    DemoSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(FILTER_SHEET), state = state) {
        FilterSheetContent(filters, onFilters, onDone = { scope.launch { state.hideThenDismiss(onDismiss) } })
    }
}

/**
 * The sheet's contents, without the window. The only state is what the range's fields hold ([RangeDraft]); the chosen
 * size, the check and the line under the fields are read from it and from [filters], and a valid range is applied as
 * it is typed, so an invalid one leaves the last valid range searched and says why.
 */
@Composable
internal fun ColumnScope.FilterSheetContent(
    filters: SearchFilters,
    onFilters: (FiltersChange) -> Unit,
    onDone: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = RangeDraft.Saver) { mutableStateOf(RangeDraft.of(filters.size)) }
    val edit: (RangeDraft) -> Unit = { next ->
        draft = next
        (next.check as? RangeCheck.Valid)?.let { valid -> onFilters { it.copy(size = valid.range) } }
    }
    ScrollStack(Gap.Item, Modifier.weight(1f, fill = false)) {
        Inline(Gap.Block, Modifier.fillMaxWidth()) {
            Text(
                "Search filters",
                Modifier.weight(1f),
                color = LocalDemoColors.current.ink,
                style = DemoType.title,
            )
            QuietButton(
                "Reset",
                onClick = {
                    draft = RangeDraft()
                    onFilters { SearchFilters() }
                },
                enabled = !filters.isDefault,
            )
        }
        Stack(Gap.Block) {
            SectionLabel("Sort", inset = false, quiet = true)
            ChoiceChips(SearchOrder.entries.map { it.words }, filters.sort.ordinal, { index ->
                onFilters { it.copy(sort = SearchOrder.entries[index]) }
            })
        }
        Stack(Gap.Block) {
            SectionLabel("Size", inset = false, quiet = true)
            ChoiceChips(sizeChoices, sizeIndex(filters.size), { index ->
                when (index) {
                    0 -> onFilters { it.copy(size = SizeFilter.FitsPhone) }

                    1 -> onFilters { it.copy(size = SizeFilter.Any) }

                    // a range to search at once: the fields' own if they make one, else the usual 1 to 4 billion
                    else -> edit(draft.takeIf { it.check is RangeCheck.Valid } ?: RangeDraft())
                }
            })
            Unfold((filters.size as? SizeFilter.Range)?.let { it to draft.check }) { (range, check) ->
                RangeFields(draft, range, check, edit)
            }
        }
        GroupedList {
            row {
                SwitchRow(
                    "Sees images",
                    filters.seesImages,
                    { on -> onFilters { it.copy(seesImages = on) } },
                    // many uploads leave the Hub's tag blank: it can include, never safely exclude (models-search)
                    subtitle = "Only models tagged as taking pictures. Untagged ones are left out.",
                )
            }
        }
    }
    PrimaryButton("Done", onDone, Modifier.fillMaxWidth(), subtle = true)
}

/** Which size choice [size] is: Fits this phone, Any size or Custom. */
private fun sizeIndex(size: SizeFilter): Int =
    when (size) {
        SizeFilter.FitsPhone -> 0
        SizeFilter.Any -> 1
        is SizeFilter.Range -> 2
    }

/** The custom range: From and To in billions, and while they don't make a range, why, and what is still searched. */
@Composable
private fun RangeFields(draft: RangeDraft, range: SizeFilter.Range, check: RangeCheck, edit: (RangeDraft) -> Unit) {
    Stack(Gap.Inline) {
        Inline(Gap.Block, Modifier.fillMaxWidth()) {
            NumberField(draft.min, { edit(draft.copy(min = it)) }, "From", "B", Modifier.weight(1f))
            NumberField(draft.max, { edit(draft.copy(max = it)) }, "To", "B", Modifier.weight(1f))
        }
        Unfold((check as? RangeCheck.Invalid)?.reason) { reason ->
            SectionNote("$reason Still ${rangeWords(range)}.", Modifier.testTag("range_problem"))
        }
    }
}
