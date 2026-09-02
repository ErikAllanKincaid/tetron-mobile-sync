// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import java.io.File
import uniffi.tetron_mobile_sync.SyncProgressEvent
import uniffi.tetron_mobile_sync.SyncProgressListener

/** One file that finished transferring in a run -- the Progress screen's
 *  transfer-list row (live and persisted). */
data class TransferredFileLine(val path: String, val bytes: Long)

/**
 * SYNC-009: accumulates the `(path, bytes)` of every completed regular-file
 * transfer in a run (`isTransfer && isFinal`, same predicate as
 * [xyz.tetron.sync.delete.TransferredFileCollector] -- kept separate to
 * avoid a `delete` <-> `pipeline` package cycle and because this one needs
 * the byte size too), teeing any [downstream] listener the caller supplied.
 * [SyncPipeline] hands [lines] to [RunFileLog.write] once the run ends, so
 * every trigger path (manual / periodic / network-change) persists a list,
 * not just Home-started runs. Newest first, to match the live view and the
 * persisted format.
 */
class RunFileLogCollector(
    private val downstream: SyncProgressListener? = null,
) : SyncProgressListener {
    private val entries = LinkedHashMap<String, Long>()

    override fun onProgress(event: SyncProgressEvent) {
        if (event.isTransfer && event.isFinal) {
            event.path?.let { entries.putIfAbsent(it, event.totalBytes?.toLong() ?: 0L) }
        }
        downstream?.onProgress(event)
    }

    fun lines(): List<TransferredFileLine> =
        entries.entries.reversed().map { TransferredFileLine(it.key, it.value) }
}

/**
 * SYNC-009: the last run's transferred-file list, persisted so the Progress
 * screen still shows it after the run ends and across process death. One
 * `path \t bytes` line per file, newest first; [write] replaces the whole
 * file, so it always reflects exactly the most recent run (a new run
 * [clear]s it first, a finished run [write]s its list). Uncapped -- unlike
 * the live in-run list ([RunProgressTracker.MAX_LINES]) the Progress screen
 * scrolls the whole thing; a first backup of ~10k files is a few hundred KB
 * of text. All I/O is best-effort ([runCatching]) -- a failure to persist
 * the list must never affect the run itself.
 *
 * Only files that actually transferred appear (skips / directories /
 * symlinks do not), so an empty list from a completed run means "nothing
 * new to back up", which the Progress screen says explicitly rather than
 * showing a blank pane.
 */
class RunFileLog(private val file: File) {

    fun write(lines: List<TransferredFileLine>) {
        runCatching {
            file.writeText(
                lines.joinToString("") { line ->
                    line.path.replace('\t', ' ').replace('\n', ' ') + "\t" + line.bytes + "\n"
                },
            )
        }
    }

    fun clear() = write(emptyList())

    fun read(): List<TransferredFileLine> =
        runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                file.readLines().mapNotNull { raw ->
                    if (raw.isEmpty()) return@mapNotNull null
                    val tab = raw.lastIndexOf('\t')
                    val bytes = if (tab > 0) raw.substring(tab + 1).toLongOrNull() else null
                    if (bytes == null) null else TransferredFileLine(raw.substring(0, tab), bytes)
                }
            }
        }.getOrDefault(emptyList())
}
