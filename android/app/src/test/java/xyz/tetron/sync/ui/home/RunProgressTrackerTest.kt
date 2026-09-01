// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tetron_mobile_sync.SyncProgressEvent

/** SYNC-009: the Progress screen's per-file aggregation -- byte total, rate,
 *  ETA and the newest-first transfer list built from the engine's
 *  one-event-per-file stream. Pure JVM, no Android. */
class RunProgressTrackerTest {

    private var clock = 0L
    private fun tracker(totalBytes: Long) = RunProgressTracker(totalBytes) { clock }

    private fun event(
        path: String?,
        fileBytes: Long? = null,
        filesDone: Long = 1,
        filesTotal: Long = 10,
        isTransfer: Boolean = true,
        isFinal: Boolean = true,
    ) = SyncProgressEvent(
        path = path,
        totalBytes = fileBytes?.toULong(),
        // The engine's overallTransferred overshoots the real send; the
        // tracker must ignore it and sum per-file sizes instead.
        overallTransferred = 999_999_999UL,
        overallTotalBytes = null,
        filesDone = filesDone.toULong(),
        filesTotal = filesTotal.toULong(),
        flistEof = true,
        transferComplete = false,
        isTransfer = isTransfer,
        isFinal = isFinal,
    )

    @Test
    fun bytesTransferredIsTheSumOfCompletedFileSizes_notOverallTransferred() {
        val t = tracker(totalBytes = 1_000)
        clock = 1_000
        t.onEvent(event("a.jpg", fileBytes = 100))
        clock = 2_000
        val s = t.onEvent(event("b.mp4", fileBytes = 250, filesDone = 2))
        assertEquals(350L, s.bytesTransferred)
    }

    @Test
    fun listIsNewestFirst_andOnlyRealTransfers() {
        val t = tracker(totalBytes = 0)
        clock = 1_000; t.onEvent(event("a.jpg", fileBytes = 100))
        clock = 2_000; t.onEvent(event("dir", isTransfer = false))       // directory, not listed
        clock = 3_000; t.onEvent(event(null))                            // no path, not listed
        clock = 4_000; val s = t.onEvent(event("b.mp4", fileBytes = 200))
        assertEquals(listOf("b.mp4", "a.jpg"), s.files.map { it.path })
        assertEquals(200L, s.files[0].bytes)
        assertEquals(300L, s.bytesTransferred) // only the two real transfers
    }

    @Test
    fun listIsCappedAtMaxLines() {
        val t = tracker(totalBytes = 0)
        repeat(RunProgressTracker.MAX_LINES + 50) { i ->
            clock += 10
            t.onEvent(event("f$i.jpg", fileBytes = 10))
        }
        val s = t.snapshot()
        assertEquals(RunProgressTracker.MAX_LINES, s.files.size)
        assertEquals("f${RunProgressTracker.MAX_LINES + 49}.jpg", s.files.first().path)
    }

    @Test
    fun fractionIsByteBasedWhenTotalKnown_elseFileCount() {
        val withTotal = tracker(totalBytes = 1_000)
        clock = 1_000
        assertEquals(0.25f, withTotal.onEvent(event("a", fileBytes = 250, filesDone = 3, filesTotal = 12)).fraction!!, 1e-6f)

        clock = 0
        val noTotal = tracker(totalBytes = 0)
        clock = 1_000
        assertEquals(0.5f, noTotal.onEvent(event("a", fileBytes = 999, filesDone = 4, filesTotal = 8)).fraction!!, 1e-6f)
    }

    @Test
    fun rateAndEtaComeFromCompletedBytesOverElapsed() {
        val t = tracker(totalBytes = 10_000_000)
        // 2 MB done in 2s -> 1 MB/s average; 8 MB left -> ~8s.
        clock = 2_000
        val s = t.onEvent(event("a", fileBytes = 2_000_000))
        assertEquals(1_000_000.0, s.bytesPerSec, 1.0)
        assertEquals(8L, s.etaSeconds)
    }

    @Test
    fun etaIsNullWithoutAByteTotalOrWithATrivialRate() {
        clock = 0
        val noTotal = tracker(totalBytes = 0)
        clock = 1_000
        assertNull(noTotal.onEvent(event("a", fileBytes = 5_000_000)).etaSeconds)

        clock = 0
        val slow = tracker(totalBytes = 10_000_000)
        clock = 1_000_000 // 1000s elapsed, 1 KB done -> 1 B/s
        assertNull(slow.onEvent(event("a", fileBytes = 1_000)).etaSeconds)
    }

    @Test
    fun elapsedIsMeasuredFromConstruction() {
        clock = 5_000
        val t = tracker(totalBytes = 0)
        clock = 8_500
        assertEquals(3_500L, t.onEvent(event("a", fileBytes = 1)).elapsedMillis)
    }
}
