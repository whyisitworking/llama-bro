package com.suhel.llamabro.demo.ui.server

import com.suhel.llamabro.demo.server.LogLine
import com.suhel.llamabro.demo.server.Thermal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One request in the page's log: what was asked ([title]), when, by whom and how much ([line]), and its [status]. */
data class LogRow(val title: String, val line: String, val status: String)

/** The Server page's words for the server's numbers: the masked key, the log, the speed and the heat. */
object ServerFormat {
    /** The key while it is hidden, in its row and in every snippet: the same length whatever the key. */
    const val MASK = "••••••••"

    fun url(address: String, port: Int) = "http://$address:$port"

    /** "1 of 2 busy": the requests being answered of the ones the model takes at once. */
    fun busy(busy: Int, slots: Int) = "$busy of $slots busy"

    /**
     * "12:04:51 · 192.168.1.12 · 1,912 in / 233 out · 14.1 s" under "POST /v1/messages"; a request turned away before
     * the model is "turned away" under its path.
     */
    fun logRow(line: LogLine, zone: ZoneId, locale: Locale): LogRow {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss", locale).format(Instant.ofEpochMilli(line.time).atZone(zone))
        val count = NumberFormat.getIntegerInstance(locale)
        val facts =
            if (line.method == null) {
                listOf("turned away")
            } else {
                listOfNotNull(
                    if (line.promptTokens != null && line.predictedTokens != null) {
                        "${count.format(line.promptTokens)} in / ${count.format(line.predictedTokens)} out"
                    } else {
                        null
                    },
                    line.millis?.let { seconds(it, locale) },
                )
            }
        val title = if (line.method == null) line.path else "${line.method} ${line.path}"
        return LogRow(title, (listOf(time, line.client) + facts).joinToString(" · "), line.status.toString())
    }

    /**
     * The speed of the newest request that wrote anything, "18.4 tok/s": its tokens written over its whole time, the
     * prompt's reading included, as the person on the other end saw it. Null before any.
     */
    fun speed(log: List<LogLine>, locale: Locale): String? = tokensPerSecond(log, locale)?.let { "$it tok/s" }

    /** [speed]'s number alone, "18.4", for where the unit is written beside it. */
    fun tokensPerSecond(log: List<LogLine>, locale: Locale): String? =
        log.firstNotNullOfOrNull(::tokensPerSecond)?.let { String.format(locale, "%.1f", it) }

    /** "1 of 2": the requests being answered of the ones the model takes at once. */
    fun slots(busy: Int, slots: Int) = "$busy of $slots"

    /** Android's thermal status in one word, "Light": the docked view's, where the page's [heat] is a sentence. */
    fun temperature(thermal: Thermal): String = TEMPERATURE.getOrElse(thermal.status) { TEMPERATURE.last() }

    /** "POST /v1/messages · 200 · 12:04:51": the newest request, in a line. */
    fun lastRequest(line: LogLine, zone: ZoneId, locale: Locale): String {
        val row = logRow(line, zone, locale)
        return "${row.title} · ${row.status} · ${row.line.substringBefore(" · ")}"
    }

    private fun tokensPerSecond(line: LogLine): Double? {
        val tokens = line.predictedTokens ?: 0
        val millis = line.millis ?: 0L
        return if (tokens > 0 && millis > 0) tokens * MILLIS_PER_SECOND / millis else null
    }

    /**
     * "Lightly throttled · 62% of the way to heavy throttling": Android's status in words, then its forecast headroom
     * where the phone gives one (1 is `THERMAL_STATUS_SEVERE`).
     */
    fun heat(thermal: Thermal): String {
        val status = STATUS.getOrElse(thermal.status) { STATUS.last() }
        val headroom = thermal.headroom?.let { "${(it * PERCENT).toInt()}% of the way to heavy throttling" }
        return listOfNotNull(status, headroom).joinToString(" · ")
    }

    private fun seconds(millis: Long, locale: Locale) = String.format(locale, "%.1f s", millis / MILLIS_PER_SECOND)

    /** `THERMAL_STATUS_NONE` to `THERMAL_STATUS_SHUTDOWN`, in the order of their values. */
    private val STATUS =
        listOf(
            "Not throttled",
            "Lightly throttled",
            "Moderately throttled",
            "Heavily throttled",
            "Critically hot",
            "Too hot: Android may close apps",
            "Too hot: the phone may shut down",
        )

    private val TEMPERATURE = listOf("Normal", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown")

    private const val MILLIS_PER_SECOND = 1_000.0
    private const val PERCENT = 100
}
