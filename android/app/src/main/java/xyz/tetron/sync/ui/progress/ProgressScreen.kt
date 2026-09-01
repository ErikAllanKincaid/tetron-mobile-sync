// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.tetron.sync.ui.describeGateReason
import xyz.tetron.sync.ui.home.HomeViewModel
import xyz.tetron.sync.ui.home.RunPhase
import xyz.tetron.sync.ui.home.RunProgressSnapshot

/**
 * SYNC-009: the live view of the in-flight run [HomeViewModel] started
 * (same Activity-scoped instance). The engine's rsync-daemon path reports
 * one event per *completed* file -- there is no within-file byte tick (that
 * is a documented future item, see spec/sync.py SYNC-009 Progress) -- so
 * this screen shows: a whole-run progress bar (byte fraction when a backlog
 * size is known, else file count), a running transfer rate + ETA, and a
 * list of files as they land, newest first. A CircularProgressIndicator
 * keeps visible motion while a single large file is transferring and the
 * numbers are momentarily static.
 *
 * A cancel affordance is spec'd but not built here: the engine's
 * `run_client` (src/lib.rs) is a fully synchronous, blocking FFI call with
 * no cancellation token in its surface. Real cancellation needs new
 * Rust-side capability (a follow-up requirement, not a UI gap).
 */
@Composable
fun ProgressScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        when (val phase = state.runPhase) {
            RunPhase.Idle -> Text("No backup in progress", style = MaterialTheme.typography.bodyLarge)
            is RunPhase.Running -> RunningView(phase.detail ?: RunProgressSnapshot())
            is RunPhase.Gated -> Text(
                "Last attempt was blocked: ${describeGateReason(phase.reason)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            is RunPhase.Finished -> {
                val record = phase.record
                Text(
                    "Last run: ${record.added} added, ${record.skipped} skipped, ${record.failed} failed",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun RunningView(d: RunProgressSnapshot) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Backing up…", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))

        val fraction = d.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))

        val bytesPart = if (d.totalBytes > 0) {
            "${formatBytes(d.bytesTransferred)} / ${formatBytes(d.totalBytes)}"
        } else {
            "${formatBytes(d.bytesTransferred)} sent"
        }
        Text("${d.filesDone} / ${d.filesTotal} files  ·  $bytesPart", style = MaterialTheme.typography.bodyMedium)

        val rateEta = buildString {
            append(formatRate(d.bytesPerSec))
            d.etaSeconds?.let { append("  ·  ~${formatDuration(it)} left") }
        }
        Text(rateEta, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        if (d.files.isEmpty()) {
            Text("Waiting for the first file…", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(d.files) { line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "✓ ${line.path}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(end = 12.dp),
                        )
                        Text(formatBytes(line.bytes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Coarse, human-readable byte size ("0 B", "4.2 MB", "61 GB"). */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "${value.toInt()} ${units[unit]}" else "%.1f %s".format(value, units[unit])
}

private fun formatRate(bytesPerSec: Double): String =
    if (bytesPerSec < 1.0) "—" else "${formatBytes(bytesPerSec.toLong())}/s"

private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
