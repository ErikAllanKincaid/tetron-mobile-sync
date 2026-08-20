// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.tetron.sync.media.MediaAccessGrant
import xyz.tetron.sync.ui.describeGateReason
import xyz.tetron.sync.ui.describeTunnelState

/**
 * SYNC-009 Home screen: tunnel-state line, consent banner, target summary,
 * media-access banner, the "Back up now" button, and the current
 * [RunPhase] (blocked reason / in-progress / last result). The gated-run
 * "Transfer anyway?" confirm is the decision from this requirement's own
 * open item (spec/sync.py SYNC-009): shown only when [RunPhase.Gated
 * .canOverride] is true.
 *
 * [onRequestMediaPermission] launches the SYNC-008 runtime permission
 * request -- `ActivityResultContracts.RequestMultiplePermissions` needs an
 * `Activity` to register against, which this Composable does not have, so
 * [xyz.tetron.sync.MainActivity] owns the launcher and passes the trigger
 * down (same reason [xyz.tetron.sync.delete.DeletionRequester] is a
 * contract rather than built here). The result callback is deliberately a
 * no-op: [HomeViewModel]'s own ~3s poll picks up the new grant either way,
 * so there is nothing UI-specific to do the instant the system dialog
 * closes.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel, onRequestMediaPermission: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val phase = state.runPhase

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("tetron sync", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(describeTunnelState(state.tunnelState), style = MaterialTheme.typography.bodyMedium)

        state.consentCallerPackage?.let { callerPackage ->
            Spacer(Modifier.height(16.dp))
            ConsentBanner(callerPackage)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = state.targetDisplayName?.let { "Backing up to $it" } ?: "No backup target configured yet",
            style = MaterialTheme.typography.bodyMedium,
        )

        MediaAccessBanner(state.mediaAccessGrant, onRequestMediaPermission)

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::backUpNow,
            enabled = phase !is RunPhase.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (phase is RunPhase.Running) "Backing up…" else "Back up now")
        }

        Spacer(Modifier.height(16.dp))
        RunPhaseSummary(phase)
    }

    // A dismissed dialog must stay dismissed for THIS blocked reason, but
    // reappear for a fresh gated cycle (e.g. the user tries again and hits
    // a different -- or the same, after a state change -- block).
    var dismissedReason by remember { mutableStateOf<xyz.tetron.sync.gates.GateReason?>(null) }
    if (phase is RunPhase.Gated && phase.canOverride && phase.reason != dismissedReason) {
        TransferAnywayDialog(
            reason = phase.reason,
            onConfirm = { viewModel.transferAnyway(phase.reason) },
            onDismiss = { dismissedReason = phase.reason },
        )
    }
}

@Composable
private fun ConsentBanner(callerPackage: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text = "$callerPackage needs your consent to read mesh status. Open tetron-mobile to grant it.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MediaAccessBanner(grant: MediaAccessGrant, onRequestMediaPermission: () -> Unit) {
    when (grant) {
        MediaAccessGrant.NotGranted -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "tetron sync needs photo access to back up your camera roll.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRequestMediaPermission) { Text("Grant photo access") }
        }
        MediaAccessGrant.Partial -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Only selected photos will back up -- grant full photo access in system settings for the whole camera roll.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        MediaAccessGrant.Full -> Unit
    }
}

@Composable
private fun RunPhaseSummary(phase: RunPhase) {
    when (phase) {
        RunPhase.Idle -> Unit
        is RunPhase.Running -> {
            val event = phase.progress
            Text(
                text = if (event != null) {
                    "Transferring ${event.filesDone}/${event.filesTotal}" + (event.path?.let { ": $it" } ?: "")
                } else {
                    "Starting…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is RunPhase.Gated -> Text(
            text = "Blocked: ${describeGateReason(phase.reason)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is RunPhase.Finished -> {
            val record = phase.record
            val status = when {
                record.failed > 0 -> "Failed: ${record.failureReason ?: "unknown error"}"
                record.interrupted -> "Interrupted, will resume next run"
                else -> "Done"
            }
            Text(
                text = "$status -- ${record.added} added, ${record.skipped} skipped",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TransferAnywayDialog(
    reason: xyz.tetron.sync.gates.GateReason,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer anyway?") },
        text = { Text("${describeGateReason(reason)}. Run the backup anyway, just this once?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Transfer anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Wait") } },
    )
}
