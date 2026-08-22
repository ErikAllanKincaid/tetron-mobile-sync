// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.tetron.sync.bridge.BridgePeer
import xyz.tetron.sync.media.MediaAccessGrant
import xyz.tetron.sync.pipeline.SyncTarget
import xyz.tetron.sync.ui.describeGateReason
import xyz.tetron.sync.ui.describeTunnelState

/**
 * SYNC-009 Home screen: the backup target editor (mesh peer + module,
 * moved here from Settings -- this is the thing you look at right before
 * tapping "Back up now", so it belongs beside that button, not on a
 * separate settings page), tunnel-state line, consent banner, media-access
 * banner, the "Back up now" button, and the current [RunPhase] (blocked
 * reason / in-progress / last result). Port stays on the Settings screen
 * instead (see SettingsScreen's own "Backup target" note) -- it is a
 * fixed, rarely-changed technical value tied to the receiver's own setup,
 * not something you reconsider each time you pick a peer/module.
 *
 * The gated-run "Transfer anyway?" confirm is the decision from this
 * requirement's own open item (spec/sync.py SYNC-009): shown only when
 * [RunPhase.Gated.canOverride] is true.
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
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("tetron sync", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text("BACKUP TARGET", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        TargetEditor(target = state.target, rosterPeers = state.rosterPeers, onSave = viewModel::setTarget)

        Spacer(Modifier.height(20.dp))
        Text(describeTunnelState(state.tunnelState), style = MaterialTheme.typography.bodyMedium)

        state.consentCallerPackage?.let { callerPackage ->
            Spacer(Modifier.height(16.dp))
            ConsentBanner(callerPackage)
        }

        MediaAccessBanner(state.mediaAccessGrant, onRequestMediaPermission)

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::backUpNow,
            enabled = phase !is RunPhase.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (phase is RunPhase.Running) "Backing up…" else "Back up now")
        }

        // TODO #8: only shown while a run is actually in progress -- there
        // is nothing to cancel otherwise, and the disabled "Back up now"
        // button above already communicates that state.
        if (phase is RunPhase.Running) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = viewModel::cancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }

        Spacer(Modifier.height(16.dp))
        // Selectable so a failure (e.g. "Failed: exitCode=10") can be
        // copy-pasted for a bug report instead of hand-transcribed --
        // plain Text is not selectable by default in Compose.
        SelectionContainer {
            RunPhaseSummary(phase)
        }
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
                record.cancelled -> "Cancelled"
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

/**
 * Moved here from Settings (SYNC-009 revision, 2026-08-21): this is what
 * you look at right before tapping "Back up now", so it belongs on Home.
 * Only mesh peer + module -- no Display name (redundant with the peer's
 * own hostname shown right here, and could drift out of sync with it; see
 * [HomeViewModel]'s own note) and no Port (moved to Settings instead,
 * since it is a fixed technical value tied to the receiver's own setup,
 * not something reconsidered alongside peer/module).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetEditor(
    target: SyncTarget?,
    rosterPeers: List<BridgePeer>,
    onSave: (SyncTarget?) -> Unit,
) {
    var selectedPeer by remember(target) { mutableStateOf<BridgePeer?>(rosterPeers.firstOrNull { it.ip == target?.meshIp }) }
    var moduleName by remember(target) { mutableStateOf(target?.module ?: "") }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(rosterPeers, target) {
        if (selectedPeer == null) {
            selectedPeer = rosterPeers.firstOrNull { it.ip == target?.meshIp }
        }
    }

    if (rosterPeers.isEmpty()) {
        Text(
            "No mesh peers seen yet -- open tetron-mobile and join a network first.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedPeer?.let { it.hostname ?: it.ip } ?: "Choose a mesh peer",
            onValueChange = {},
            readOnly = true,
            label = { Text("Mesh peer") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            rosterPeers.forEach { peer ->
                DropdownMenuItem(
                    text = { Text("${peer.hostname ?: peer.ip} (${peer.ip})") },
                    onClick = {
                        selectedPeer = peer
                        expanded = false
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = moduleName,
        onValueChange = { moduleName = it },
        label = { Text("rsync module name") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            val peer = selectedPeer
            if (peer != null && moduleName.isNotBlank()) {
                val newTarget = target?.copy(meshIp = peer.ip, module = moduleName)
                    ?: SyncTarget(meshIp = peer.ip, module = moduleName)
                onSave(newTarget)
            }
        },
        enabled = selectedPeer != null && moduleName.isNotBlank(),
    ) {
        Text("Save target")
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
