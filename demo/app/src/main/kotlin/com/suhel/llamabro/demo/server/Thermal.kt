package com.suhel.llamabro.demo.server

import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * How hot the phone runs, as Android says (docs/design/server.md §3: shown, not acted on): [status] is
 * `PowerManager.currentThermalStatus` (`THERMAL_STATUS_NONE` 0 to `THERMAL_STATUS_SHUTDOWN` 6), [headroom] is
 * `getThermalHeadroom(10)`, its forecast of how far the phone is towards severe throttling (1 is there), or null where
 * the phone gives none (before Android 11, or not supported).
 */
data class Thermal(val status: Int, val headroom: Float?)

/**
 * The phone's [Thermal] now and every [periodMillis] after, while collected. Polled rather than listened to: the
 * headroom has no listener, and Android returns NaN for it when asked more than once a second.
 */
fun thermalReadings(power: PowerManager, periodMillis: Long = PERIOD_MILLIS): Flow<Thermal> =
    flow {
        while (true) {
            emit(Thermal(power.currentThermalStatus, headroom(power)))
            delay(periodMillis)
        }
    }.distinctUntilChanged()

private fun headroom(power: PowerManager): Float? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        power.getThermalHeadroom(FORECAST_SECONDS).takeIf { it.isFinite() && it >= 0f }
    } else {
        null
    }

/** The forecast `getThermalHeadroom` is asked for: the design's ten seconds ahead. */
private const val FORECAST_SECONDS = 10
private const val PERIOD_MILLIS = 10_000L
