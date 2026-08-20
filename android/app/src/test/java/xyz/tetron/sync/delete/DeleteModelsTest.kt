// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.delete

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tetron_mobile_sync.SyncProgressEvent
import uniffi.tetron_mobile_sync.SyncProgressListener

/**
 * SYNC-007 ACCEPTANCE (Android-side): the delete set is exactly the
 * transferred set, and the opt-in gate ([resolveDeleteSet]) is exercised
 * independent of [xyz.tetron.sync.pipeline.SyncPipeline] -- see
 * `SyncPipelineTest` for the end-to-end wiring through [DeletionRequester].
 */
class DeleteModelsTest {

    private fun event(
        path: String?,
        isTransfer: Boolean,
        isFinal: Boolean,
    ) = SyncProgressEvent(
        path = path,
        totalBytes = null,
        overallTransferred = 0UL,
        overallTotalBytes = null,
        filesDone = 1UL,
        filesTotal = 1UL,
        flistEof = true,
        transferComplete = false,
        isTransfer = isTransfer,
        isFinal = isFinal,
    )

    @Test
    fun resolveDeleteSet_disabled_isAlwaysEmpty() {
        val result = resolveDeleteSet(DeleteAfterBackupConfig(enabled = false), listOf("a.jpg", "b.jpg"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun resolveDeleteSet_enabled_isExactlyTheTransferredSet() {
        val transferred = listOf("a.jpg", "b.jpg")
        val result = resolveDeleteSet(DeleteAfterBackupConfig(enabled = true), transferred)
        assertEquals(transferred, result)
    }

    @Test
    fun collector_recordsOnly_transferAndFinal_events() {
        val collector = TransferredFileCollector()

        collector.onProgress(event("a.jpg", isTransfer = true, isFinal = true))
        // Mid-transfer tick for a still-copying file: not final yet.
        collector.onProgress(event("b.jpg", isTransfer = true, isFinal = false))
        // Already-present skip (mtime+size match): never a byte-verified transfer.
        collector.onProgress(event("c.jpg", isTransfer = false, isFinal = true))
        // Directory entry: is_progress but not is_transfer.
        collector.onProgress(event("sub/", isTransfer = false, isFinal = true))
        // Synthetic end-of-run summary line has no path.
        collector.onProgress(event(null, isTransfer = false, isFinal = true))

        assertEquals(listOf("a.jpg"), collector.transferredPaths())
    }

    @Test
    fun collector_forwardsEveryEvent_toDownstream() {
        val seen = mutableListOf<SyncProgressEvent?>()
        val collector = TransferredFileCollector(
            downstream = object : SyncProgressListener {
                override fun onProgress(event: SyncProgressEvent) {
                    seen.add(event)
                }
            },
        )

        val e1 = event("a.jpg", isTransfer = true, isFinal = true)
        val e2 = event("b.jpg", isTransfer = false, isFinal = true)
        collector.onProgress(e1)
        collector.onProgress(e2)

        assertEquals(listOf(e1, e2), seen)
    }
}
