package com.suhel.llamabro.demo.models

/**
 * A Hub fact as the screens show it (docs/design/models-page.md §1): what is stored, whether a refresh is under way,
 * and how the last one failed. A failed refresh leaves [value] as it was.
 */
data class Cached<T>(val value: T?, val refreshing: Boolean, val failure: Throwable?) {
    /** Something is stored, however old. */
    val known: Boolean get() = value != null
}
