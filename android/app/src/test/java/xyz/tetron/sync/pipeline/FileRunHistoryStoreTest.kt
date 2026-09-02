// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SYNC-009 (2026-09-02) ACCEPTANCE: the rotating run log keeps at most
 * [FileRunHistoryStore.MAX_RUNS] newest-first, "Clear history" keeps the
 * single most recent run, and [RunRecordLineCodec] round-trips every
 * [RunRecord] field (a garbled line is skipped, not fatal).
 */
class FileRunHistoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = FileRunHistoryStore(File(tmp.root, "run_history.log"))

    private fun run(ts: Long, added: Int = 1) = RunRecord(
        timestampMillis = ts,
        added = added,
        skipped = 0,
        failed = 0,
        interrupted = false,
        failureReason = null,
    )

    // --- store ---

    @Test fun records_are_newest_first() {
        val s = store()
        s.recordRun(run(100))
        s.recordRun(run(200))
        s.recordRun(run(300))
        assertEquals(listOf(300L, 200L, 100L), s.recentRuns(10).map { it.timestampMillis })
        assertEquals(300L, s.lastRun()?.timestampMillis)
    }

    @Test fun the_log_rotates_at_MAX_RUNS() {
        val s = store()
        repeat(FileRunHistoryStore.MAX_RUNS + 8) { i -> s.recordRun(run(i.toLong())) }
        val kept = s.recentRuns(1000)
        assertEquals(FileRunHistoryStore.MAX_RUNS, kept.size)
        // newest is the last recorded; oldest kept is (total - MAX_RUNS)
        assertEquals((FileRunHistoryStore.MAX_RUNS + 7).toLong(), kept.first().timestampMillis)
        assertEquals(8L, kept.last().timestampMillis)
    }

    @Test fun recentRuns_limit_is_respected() {
        val s = store()
        repeat(5) { i -> s.recordRun(run(i.toLong())) }
        assertEquals(2, s.recentRuns(2).size)
        assertEquals(listOf(4L, 3L), s.recentRuns(2).map { it.timestampMillis })
    }

    @Test fun clear_keeps_only_the_most_recent_run() {
        val s = store()
        s.recordRun(run(1))
        s.recordRun(run(2))
        s.recordRun(run(3))
        s.clear()
        assertEquals(listOf(3L), s.recentRuns(10).map { it.timestampMillis })
    }

    @Test fun clear_on_an_empty_log_is_a_no_op() {
        val s = store()
        s.clear()
        assertEquals(emptyList<RunRecord>(), s.recentRuns(10))
        assertNull(s.lastRun())
    }

    @Test fun missing_file_reads_as_empty() {
        assertEquals(emptyList<RunRecord>(), store().recentRuns(10))
        assertNull(store().lastRun())
    }

    @Test fun a_garbled_line_in_the_middle_is_skipped() {
        val file = File(tmp.root, "run_history.log")
        val good = RunRecordLineCodec.encode(run(999))
        file.writeText("$good\nnot-a-valid-line\nv1|only|three\n${RunRecordLineCodec.encode(run(1))}\n")
        val out = FileRunHistoryStore(file).recentRuns(10)
        assertEquals(listOf(999L, 1L), out.map { it.timestampMillis })
    }

    // --- line codec ---

    @Test fun line_codec_round_trips_every_field() {
        val full = RunRecord(
            timestampMillis = 1_787_000_000_000L,
            added = 12,
            skipped = 3,
            failed = 1,
            interrupted = true,
            failureReason = "weird | reason \n with delimiters",
            cancelled = true,
            skippedOversize = 4,
        )
        assertEquals(full, RunRecordLineCodec.decode(RunRecordLineCodec.encode(full)))
    }

    @Test fun line_codec_round_trips_a_clean_run_with_no_failure_reason() {
        val clean = run(42)
        assertEquals(clean, RunRecordLineCodec.decode(RunRecordLineCodec.encode(clean)))
    }

    @Test fun line_codec_rejects_a_wrong_tag_or_arity() {
        assertNull(RunRecordLineCodec.decode("v2|1|2|3|4|5|0|0|"))
        assertNull(RunRecordLineCodec.decode("garbage"))
        assertNull(RunRecordLineCodec.decode("v1|1|2|3"))
    }
}
