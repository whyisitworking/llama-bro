package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.DownloadPhase
import com.suhel.llamabro.hub.DownloadProgress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong
import kotlin.time.Duration

/** Serializes progress from concurrent file transfers and limits ordinary emissions. */
internal class ProgressMeter(
    private val interval: Duration,
    private val emit: suspend (DownloadProgress) -> Unit,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val mutex = Mutex()
    private var lastPhase: DownloadPhase? = null
    private var lastEmitNanos: Long? = null
    private var windowNanos: Long? = null
    private var windowBytes = 0L
    private var windowCount = 0
    private var averageBytesPerSecond: Double? = null

    suspend fun update(
        phase: DownloadPhase,
        bytesDone: Long,
        bytesTotal: Long,
        currentFile: String?,
        fileIndex: Int,
        fileCount: Int,
        force: Boolean = false,
    ) = mutex.withLock {
        val now = clockNanos()
        if (phase == DownloadPhase.Downloading) sampleSpeed(now, bytesDone)
        val changed = phase != lastPhase
        val due = lastEmitNanos?.let { now - it >= interval.inWholeNanoseconds } ?: true
        val mustEmit = changed || due || force
        if (mustEmit || phase == DownloadPhase.Complete) {
            val speed = averageBytesPerSecond?.takeIf { windowCount >= 2 && it > 0.0 }?.roundToLong()
            val eta = speed?.takeIf { it > 0L }?.let { ((bytesTotal - bytesDone).coerceAtLeast(0) + it - 1) / it }
            emit(DownloadProgress(phase, bytesDone, bytesTotal, currentFile, fileIndex, fileCount, speed, eta))
            lastPhase = phase
            lastEmitNanos = now
        }
    }

    /** Once a second, folds the bytes received since the last sample into the smoothed speed. */
    private fun sampleSpeed(now: Long, bytesDone: Long) {
        val started = windowNanos
        // Cached bytes establish the baseline; only bytes received during transfer affect speed.
        if (started == null) {
            windowNanos = now
            windowBytes = bytesDone
            return
        }
        val elapsed = now - started
        if (elapsed < NANOS_PER_SECOND) return
        val sample = ((bytesDone - windowBytes).coerceAtLeast(0).toDouble() * NANOS_PER_SECOND) / elapsed
        averageBytesPerSecond = averageBytesPerSecond?.let { NEW_WEIGHT * sample + OLD_WEIGHT * it } ?: sample
        windowCount++
        windowNanos = now
        windowBytes = bytesDone
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NEW_WEIGHT = 0.3
        const val OLD_WEIGHT = 0.7
    }
}
