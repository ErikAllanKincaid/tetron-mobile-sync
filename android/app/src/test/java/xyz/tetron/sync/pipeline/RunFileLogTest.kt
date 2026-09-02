// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.tetron_mobile_sync.SyncProgressEvent

/** SYNC-009 ACCEPTANCE: the persisted last-run transfer list round-trips,
 *  a new run replaces it, and a garbled line is skipped rather than
 *  crashing the reader. */
class RunFileLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun log() = RunFileLog(File(tmp.root, "last_run_files.tsv"))

    @Test fun write_then_read_round_trips_order_and_sizes() {
        val log = log()
        val lines = listOf(
            TransferredFileLine("DCIM/Camera/newest.jpg", 1_234),
            TransferredFileLine("Pictures/older.png", 55),
        )
        log.write(lines)
        assertEquals(lines, log.read())
    }

    @Test fun read_of_a_missing_file_is_empty_not_an_error() {
        assertEquals(emptyList<TransferredFileLine>(), log().read())
    }

    @Test fun write_replaces_it_does_not_append() {
        val log = log()
        log.write(listOf(TransferredFileLine("a.jpg", 1)))
        log.write(listOf(TransferredFileLine("b.jpg", 2), TransferredFileLine("c.jpg", 3)))
        assertEquals(listOf("b.jpg", "c.jpg"), log.read().map { it.path })
    }

    @Test fun clear_leaves_an_empty_list() {
        val log = log()
        log.write(listOf(TransferredFileLine("a.jpg", 1)))
        log.clear()
        assertEquals(emptyList<TransferredFileLine>(), log.read())
    }

    @Test fun a_garbled_line_is_skipped() {
        val file = File(tmp.root, "last_run_files.tsv")
        file.writeText("good/path.jpg\t100\ngarbage-no-tab\nother.jpg\tnotanumber\nfine.jpg\t42\n")
        val out = RunFileLog(file).read()
        assertEquals(listOf("good/path.jpg", "fine.jpg"), out.map { it.path })
        assertEquals(listOf(100L, 42L), out.map { it.bytes })
    }

    @Test fun tabs_and_newlines_in_a_path_are_neutralised_on_write() {
        val log = log()
        log.write(listOf(TransferredFileLine("weird\tname\nfile.jpg", 7)))
        val out = log.read()
        assertEquals(1, out.size)
        assertEquals(7L, out.first().bytes)
        assertTrue(!out.first().path.contains('\t'))
        assertTrue(!out.first().path.contains('\n'))
    }

    // --- RunFileLogCollector ---

    private fun event(path: String?, bytes: Long?, isTransfer: Boolean, isFinal: Boolean) =
        SyncProgressEvent(
            path = path,
            totalBytes = bytes?.toULong(),
            overallTransferred = 0UL,
            overallTotalBytes = null,
            filesDone = 1UL,
            filesTotal = 1UL,
            flistEof = true,
            transferComplete = false,
            isTransfer = isTransfer,
            isFinal = isFinal,
        )

    @Test fun collector_keeps_only_completed_transfers_newest_first_with_sizes() {
        val collector = RunFileLogCollector()
        collector.onProgress(event("first.jpg", 10, isTransfer = true, isFinal = true))
        collector.onProgress(event("mid.jpg", 20, isTransfer = true, isFinal = false)) // not final
        collector.onProgress(event("skip.jpg", 30, isTransfer = false, isFinal = true)) // not a transfer
        collector.onProgress(event("second.jpg", 40, isTransfer = true, isFinal = true))
        collector.onProgress(event(null, 50, isTransfer = true, isFinal = true)) // no path
        assertEquals(
            listOf(TransferredFileLine("second.jpg", 40), TransferredFileLine("first.jpg", 10)),
            collector.lines(),
        )
    }

    @Test fun collector_tees_the_downstream_listener() {
        val seen = ArrayList<String?>()
        val collector = RunFileLogCollector(
            object : uniffi.tetron_mobile_sync.SyncProgressListener {
                override fun onProgress(event: SyncProgressEvent) { seen.add(event.path) }
            },
        )
        collector.onProgress(event("a.jpg", 1, isTransfer = true, isFinal = true))
        collector.onProgress(event("b/", null, isTransfer = false, isFinal = true))
        assertEquals(listOf("a.jpg", "b/"), seen)
    }
}
