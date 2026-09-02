// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import java.io.File
import java.util.Base64

/**
 * SYNC-009 (2026-09-02): the production [RunHistoryStore] -- a short
 * rotating log instead of the single record
 * [SharedPreferencesRunHistoryStore] kept. One [RunRecordLineCodec] line
 * per run in a `filesDir` file, newest first, at most [MAX_RUNS]; [clear]
 * trims it back to the most recent run. All I/O is best-effort
 * ([runCatching]) -- a history read/write failure must never affect a run.
 * The `SharedPreferences` glue stays untested (same bar as
 * [SharedPreferencesRunHistoryStore]); [RunRecordLineCodec] carries the
 * logic and is round-tripped in `RunRecordLineCodecTest`.
 */
class FileRunHistoryStore(private val file: File) : RunHistoryStore {

    override fun recordRun(record: RunRecord) {
        val kept = rawLines().take(MAX_RUNS - 1)
        writeLines(listOf(RunRecordLineCodec.encode(record)) + kept)
    }

    override fun lastRun(): RunRecord? = recentRuns(1).firstOrNull()

    override fun recentRuns(limit: Int): List<RunRecord> =
        rawLines().asSequence()
            .mapNotNull(RunRecordLineCodec::decode)
            .take(limit.coerceAtLeast(0))
            .toList()

    override fun clear() {
        val newest = rawLines().firstOrNull { RunRecordLineCodec.decode(it) != null }
        writeLines(listOfNotNull(newest))
    }

    private fun rawLines(): List<String> =
        runCatching {
            if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
        }.getOrDefault(emptyList())

    private fun writeLines(lines: List<String>) {
        runCatching { file.writeText(lines.joinToString("") { it + "\n" }) }
    }

    companion object {
        /** SYNC-009: the history the user actually revisits is recent; a
         *  fixed cap makes the file self-bounding, so there is no retention
         *  picker. */
        const val MAX_RUNS = 25
    }
}

/**
 * Pure `RunRecord` <-> one-line codec for [FileRunHistoryStore]. Pipe-
 * delimited with a `v1` tag; the only free-text field ([RunRecord.failureReason])
 * is Base64'd so it can carry any bytes, delimiter or newline included. A
 * line that does not parse (truncated write, a future `v2`) yields `null`
 * and is skipped by the caller rather than corrupting the log read.
 */
object RunRecordLineCodec {
    private const val TAG = "v1"
    private const val SEP = "|"

    fun encode(r: RunRecord): String = listOf(
        TAG,
        r.timestampMillis.toString(),
        r.added.toString(),
        r.skipped.toString(),
        r.skippedOversize.toString(),
        r.failed.toString(),
        if (r.interrupted) "1" else "0",
        if (r.cancelled) "1" else "0",
        r.failureReason?.let { Base64.getEncoder().encodeToString(it.toByteArray()) } ?: "",
    ).joinToString(SEP)

    fun decode(line: String): RunRecord? {
        val p = line.split(SEP)
        if (p.size != 9 || p[0] != TAG) return null
        return runCatching {
            RunRecord(
                timestampMillis = p[1].toLong(),
                added = p[2].toInt(),
                skipped = p[3].toInt(),
                skippedOversize = p[4].toInt(),
                failed = p[5].toInt(),
                interrupted = p[6] == "1",
                cancelled = p[7] == "1",
                failureReason = p[8].takeIf { it.isNotEmpty() }
                    ?.let { String(Base64.getDecoder().decode(it)) },
            )
        }.getOrNull()
    }
}
