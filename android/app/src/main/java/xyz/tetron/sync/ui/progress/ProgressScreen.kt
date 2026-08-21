// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.tetron.sync.ui.describeGateReason
import xyz.tetron.sync.ui.home.HomeViewModel
import xyz.tetron.sync.ui.home.RunPhase

/**
 * SYNC-009: per-file phase from the same in-flight run [HomeViewModel]
 * started (files_done/total + per-file path, SYNC-002's progress stream).
 * A cancel affordance is spec'd but not built here: the engine's
 * `run_client` (src/lib.rs) is a fully synchronous, blocking FFI call with
 * no cancellation token in its surface, so there is nothing this screen
 * could invoke that would actually stop an in-flight transfer -- adding a
 * button that does not work would be worse than none. Real cancellation
 * needs new Rust-side capability (a follow-up requirement, not a UI gap).
 */
@Composable
fun ProgressScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val phase = state.runPhase) {
            RunPhase.Idle -> Text("No backup in progress", style = MaterialTheme.typography.bodyLarge)
            is RunPhase.Running -> {
                val event = phase.progress
                val total = event?.filesTotal?.toInt() ?: 0
                val done = event?.filesDone?.toInt() ?: 0
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Text("$done / $total files", style = MaterialTheme.typography.bodyLarge)
                event?.path?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
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
