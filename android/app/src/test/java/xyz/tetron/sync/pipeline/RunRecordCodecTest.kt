// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SYNC-011 found `cancelled` and `skippedOversize` had been added to
 * [RunRecord] without [SharedPreferencesRunHistoryStore] persisting them.
 * [round_trips_every_field] is the guard: a fully-populated record that
 * survives encode->decode unchanged means every field is carried, so a
 * new [RunRecord] field the codec forgets will fail here.
 */
class RunRecordCodecTest {

    private val full = RunRecord(
        timestampMillis = 1_787_000_000_000L,
        added = 12,
        skipped = 3,
        failed = 1,
        interrupted = true,
        failureReason = "connection refused",
        cancelled = true,
        skippedOversize = 4,
    )

    @Test
    fun round_trips_every_field() {
        assertEquals(full, RunRecordCodec.decode(RunRecordCodec.encode(full)))
    }

    @Test
    fun round_trips_a_minimal_clean_run() {
        val clean = RunRecord(
            timestampMillis = 42L,
            added = 0,
            skipped = 0,
            failed = 0,
            interrupted = false,
            failureReason = null,
        )
        assertEquals(clean, RunRecordCodec.decode(RunRecordCodec.encode(clean)))
    }

    @Test
    fun decode_returns_null_without_a_timestamp() {
        assertNull(RunRecordCodec.decode(emptyMap()))
        assertNull(RunRecordCodec.decode(mapOf("added" to 5)))
    }
}
