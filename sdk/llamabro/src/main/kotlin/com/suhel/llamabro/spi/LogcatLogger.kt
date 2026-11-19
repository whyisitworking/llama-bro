package com.suhel.llamabro.spi

import android.util.Log
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger

/**
 * The default sink when the app gives no [Logger] (D66): logcat under [tag], so init's lines are not lost (F144).
 * Lines below [minLevel] are dropped, for callers (the hub) that do not filter by level themselves.
 */
@InternalLlamaBroApi
public class LogcatLogger(private val tag: String, private val minLevel: LogLevel = LogLevel.Debug) : Logger {
    override fun log(level: LogLevel, message: String) {
        if (level < minLevel) return
        when (level) {
            LogLevel.Debug -> Log.d(tag, message)
            LogLevel.Info -> Log.i(tag, message)
            LogLevel.Warn -> Log.w(tag, message)
            LogLevel.Error -> Log.e(tag, message)
        }
    }
}
