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
        // Cached bytes establish the baseline; only bytes received during transfer affect speed.
        if (phase == DownloadPhase.Downloading && windowNanos == null) {
            windowNanos = now
            windowBytes = bytesDone
        }
        if (phase == DownloadPhase.Downloading && windowNanos != null) {
            val elapsed = now - windowNanos!!
            if (elapsed >= 1_000_000_000L) {
                val sample = ((bytesDone - windowBytes).coerceAtLeast(0).toDouble() * 1_000_000_000.0) / elapsed
                averageBytesPerSecond = averageBytesPerSecond?.let { 0.3 * sample + 0.7 * it } ?: sample
                windowCount++
                windowNanos = now
                windowBytes = bytesDone
            }
        }
        val changed = phase != lastPhase
        val due = lastEmitNanos == null || now - lastEmitNanos!! >= interval.inWholeNanoseconds
        if (changed || due || force || phase == DownloadPhase.Complete) {
            val speed = averageBytesPerSecond?.takeIf { windowCount >= 2 && it > 0.0 }?.roundToLong()
            val eta = speed?.takeIf { it > 0L }?.let {
                ((bytesTotal - bytesDone).coerceAtLeast(0) + it - 1) / it
            }
            emit(DownloadProgress(phase, bytesDone, bytesTotal, currentFile, fileIndex, fileCount, speed, eta))
            lastPhase = phase
            lastEmitNanos = now
        }
    }
}
