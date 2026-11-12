package com.suhel.llamabro.demo.models

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

/** Numbers as the app writes them. "GB" is 10^9 bytes, "MB" 10^6. */
object Format {
    private const val BYTES_PER_MB = 1_000_000.0
    private const val BYTES_PER_GB = 1_000_000_000.0
    private const val SECONDS_PER_MINUTE = 60

    /** F93 measured about 10 MB/s on Wi-Fi; the estimate on buttons assumes 8. */
    private const val ASSUMED_BYTES_PER_SECOND = 8 * BYTES_PER_MB

    /** From this size up, the library's storage line drops the decimal: "120 GB". */
    private const val WHOLE_GB_FROM_BYTES = 10_000_000_000L

    private const val THOUSAND = 1_000L
    private const val TEN_THOUSAND = 10_000L
    private const val MILLION = 1_000_000L

    private const val DAYS_IN_TWO_WEEKS = 14
    private const val DAYS_IN_TWO_MONTHS = 60
    private const val DAYS_PER_WEEK = 7
    private const val DAYS_PER_MONTH = 30

    /** 412, 880k, 1.2M. */
    fun downloads(count: Long): String =
        when {
            count < THOUSAND -> count.toString()
            count < TEN_THOUSAND -> tenths(count / (THOUSAND / 10)) + "k"
            count < MILLION -> (count / THOUSAND).toString() + "k"
            else -> tenths(count / (MILLION / 10)) + "M"
        }

    /** "2.7 GB". */
    fun sizeInGb(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / BYTES_PER_GB)

    /** "6.1 GB", and from 10 GB up without the decimal: "120 GB" (the storage line of the library). */
    fun storageGb(bytes: Long): String =
        if (bytes >= WHOLE_GB_FROM_BYTES) {
            String.format(Locale.ROOT, "%.0f GB", bytes / BYTES_PER_GB)
        } else {
            sizeInGb(bytes)
        }

    /** "about 3 min", or "under a minute", for a button. */
    fun downloadTime(bytes: Long): String {
        val seconds = bytes / ASSUMED_BYTES_PER_SECOND
        return if (seconds < SECONDS_PER_MINUTE) "under a minute" else "about ${minutes(seconds)} min"
    }

    /** "1.2 GB of 2.8 GB · 9.8 MB/s · 3 min left"; the speed and the time are left out when unknown. */
    fun progress(bytesDone: Long, bytesTotal: Long, bytesPerSecond: Long?, etaSeconds: Long?): String {
        val parts = mutableListOf("${size(bytesDone)} of ${size(bytesTotal)}")
        if (bytesPerSecond != null) parts += String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / BYTES_PER_MB)
        if (etaSeconds != null) parts += timeLeftText(etaSeconds)
        return parts.joinToString(" · ")
    }

    /** "under a minute left" or "3 min left": the end of a progress line or of the download notification. */
    fun timeLeftText(etaSeconds: Long): String =
        if (etaSeconds < SECONDS_PER_MINUTE) "under a minute left" else "${minutes(etaSeconds.toDouble())} min left"

    /** "about 40 s" or "about 3 min": the time left on a row. */
    fun timeLeft(etaSeconds: Long): String =
        if (etaSeconds < SECONDS_PER_MINUTE) {
            "about ${etaSeconds.coerceAtLeast(1)} s"
        } else {
            "about ${minutes(etaSeconds.toDouble())} min"
        }

    /** "Used today", "Yesterday", "3 days ago", "2 weeks ago", "5 months ago"; "Not used yet" when never. */
    fun usedAgo(lastUsed: Long?, now: Long, zone: ZoneId): String {
        if (lastUsed == null) return "Not used yet"
        val then = Instant.ofEpochMilli(lastUsed).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then, today).coerceAtLeast(0)
        return when {
            days == 0L -> "Used today"
            days == 1L -> "Yesterday"
            days < DAYS_IN_TWO_WEEKS -> "$days days ago"
            days < DAYS_IN_TWO_MONTHS -> "${days / DAYS_PER_WEEK} weeks ago"
            else -> "${days / DAYS_PER_MONTH} months ago"
        }
    }

    private fun minutes(seconds: Double): Int = ceil(seconds / SECONDS_PER_MINUTE).toInt()

    private fun size(bytes: Long): String =
        if (bytes >= BYTES_PER_GB) sizeInGb(bytes) else "${(bytes / BYTES_PER_MB).toLong()} MB"

    /** [tenthsOfUnit] is a count of tenths: 12 gives "1.2", 10 gives "1". */
    private fun tenths(tenthsOfUnit: Long): String =
        if (tenthsOfUnit % 10 == 0L) (tenthsOfUnit / 10).toString() else "${tenthsOfUnit / 10}.${tenthsOfUnit % 10}"
}
