// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.home

import uniffi.tetron_mobile_sync.SyncProgressEvent
import xyz.tetron.sync.pipeline.TransferredFileLine

/** An immutable view of a run's progress, rebuilt from [RunProgressTracker]
 *  on every engine event and stashed in [RunPhase.Running]. */
data class RunProgressSnapshot(
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    /** Sum of the sizes of the files transferred so far this run. Accurate
     *  (from each completed file's own size), unlike the engine's
     *  `overallTransferred` which counts matched/checksummed bytes too and
     *  overshoots the real send by several times. */
    val bytesTransferred: Long = 0,
    /** Best-effort denominator (the in-scope backlog size); `0` when the
     *  media store could not give one, in which case the UI falls back to
     *  the file-count fraction. */
    val totalBytes: Long = 0,
    /** Whole-run average send rate ([bytesTransferred] / elapsed). */
    val bytesPerSec: Double = 0.0,
    val elapsedMillis: Long = 0,
    /** Newest first, capped at [RunProgressTracker.MAX_LINES]. */
    val files: List<TransferredFileLine> = emptyList(),
) {
    /** Whole-run fraction 0f..1f: byte-based when [totalBytes] is known,
     *  else file-count based. `null` when neither is available yet. */
    val fraction: Float?
        get() = when {
            totalBytes > 0 -> (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)
            filesTotal > 0 -> (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
            else -> null
        }

    /** Seconds remaining, or `null` when it cannot be estimated (no byte
     *  total, or the rate is too low/noisy to divide by). */
    val etaSeconds: Long?
        get() {
            if (totalBytes <= 0 || bytesPerSec < MIN_RATE_FOR_ETA) return null
            return ((totalBytes - bytesTransferred).coerceAtLeast(0) / bytesPerSec).toLong()
        }

    private companion object {
        /** Below ~32 KB/s the average is dominated by the initial file-list
         *  build and the ETA is meaningless. */
        const val MIN_RATE_FOR_ETA = 32_000.0
    }
}

/**
 * Turns the engine's per-file `SyncProgressEvent` stream (one event per
 * completed file -- there are no within-file ticks, see spec/sync.py
 * SYNC-009 Progress) into a [RunProgressSnapshot] the Progress screen
 * renders: a running transfer rate, an ETA, and a live list of files as
 * they land.
 *
 * Pure and single-threaded by contract: the engine invokes the progress
 * callback synchronously on its own transfer thread, one call at a time, so
 * no locking is needed here. Instances are per-run (a fresh one for each
 * [HomeViewModel] run).
 */
class RunProgressTracker(
    private val totalBytes: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = nowMillis()
    private val lines = ArrayList<TransferredFileLine>()

    private var completedBytes = 0L
    private var filesDone = 0
    private var filesTotal = 0

    fun onEvent(event: SyncProgressEvent): RunProgressSnapshot {
        filesDone = event.filesDone.toInt()
        filesTotal = event.filesTotal.toInt()

        // Only real data transfers land in the list and the byte total --
        // skips (already on the server), directories, and symlinks also
        // produce events.
        if (event.isTransfer && event.isFinal) {
            event.path?.let { path ->
                val bytes = event.totalBytes?.toLong() ?: 0
                completedBytes += bytes
                lines.add(0, TransferredFileLine(path, bytes))
                if (lines.size > MAX_LINES) lines.removeAt(lines.size - 1)
            }
        }

        return snapshot(nowMillis())
    }

    /** A snapshot with no new event -- lets the UI keep a live "elapsed"
     *  ticking between the sparse per-file events. */
    fun snapshot(now: Long = nowMillis()): RunProgressSnapshot {
        val elapsed = (now - startedAt).coerceAtLeast(0)
        return RunProgressSnapshot(
            filesDone = filesDone,
            filesTotal = filesTotal,
            bytesTransferred = completedBytes,
            totalBytes = totalBytes,
            bytesPerSec = if (elapsed > 0) completedBytes * 1000.0 / elapsed else 0.0,
            elapsedMillis = elapsed,
            files = lines.toList(),
        )
    }

    companion object {
        /** Newest-first cap: a full camera-roll first backup can be
         *  thousands of files; the user only looks at the tail. */
        const val MAX_LINES = 200
    }
}
