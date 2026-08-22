// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * SYNC-009 Settings screen: gate toggles + values, the target's port
 * (peer/module selection itself lives on Home now, see
 * [xyz.tetron.sync.ui.home.HomeScreen] -- this screen just carries the
 * receiver's port, since that's a fixed
 * technical value tied to the receiver's own setup, not something
 * reconsidered alongside picking a peer/module), delete-after-backup
 * opt-in, WorkManager cadence, notification coalescing window.
 * Notification channel copy itself is a separate, not-yet-built slice of
 * this requirement (spec/sync.py: "exact copy is implementation-time").
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        SectionHeader("Gates")
        SwitchRow("Wi-Fi only", state.gateConfig.wifiOnly) {
            viewModel.setGateConfig(state.gateConfig.copy(wifiOnly = it))
        }
        SwitchRow("Direct connections only", state.gateConfig.directOnly) {
            viewModel.setGateConfig(state.gateConfig.copy(directOnly = it))
        }
        SwitchRow("Require charging", state.gateConfig.chargingRequired) {
            viewModel.setGateConfig(state.gateConfig.copy(chargingRequired = it))
        }
        SwitchRow("Pause on low battery", state.gateConfig.lowBatteryPauseEnabled) {
            viewModel.setGateConfig(state.gateConfig.copy(lowBatteryPauseEnabled = it))
        }
        if (state.gateConfig.lowBatteryPauseEnabled) {
            IntSettingRow(
                label = "Low battery threshold (%)",
                value = state.gateConfig.lowBatteryThresholdPercent,
                onValueChange = { viewModel.setGateConfig(state.gateConfig.copy(lowBatteryThresholdPercent = it)) },
            )
        }

        Spacer(Modifier.height(24.dp))
        OwnMeshIpRow(state.ownMeshIp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Which peer/module to back up to is configured on the Home screen.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        IntSettingRow(
            label = "Port",
            value = state.target?.port ?: 28873,
            onValueChange = viewModel::setTargetPort,
        )
        PortWarning()

        Spacer(Modifier.height(24.dp))
        SectionHeader("Delete after backup")
        SwitchRow("Delete photos from this device once backed up", state.deleteAfterBackupEnabled) {
            viewModel.setDeleteAfterBackupEnabled(it)
        }
        Text(
            "Off by default. Only files this run actually transferred are ever deleted, and the system always asks to confirm.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("Schedule")
        LongSettingRow(
            label = "Periodic backup interval (hours)",
            value = state.workCadenceHours,
            onValueChange = viewModel::setWorkCadenceHours,
        )
        Text(
            "Takes effect next app launch.",
            style = MaterialTheme.typography.bodySmall,
        )
        LongSettingRow(
            label = "Notification coalescing window (hours)",
            value = state.coalesceWindowHours,
            onValueChange = viewModel::setCoalesceWindowHours,
        )
        Text(
            "Takes effect next app launch.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(state.engineInfoLine, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * plan §IPC bridge enrollment UX: the phone's own mesh IP is what the home
 * side's `rsyncd.conf hosts allow` line needs (SYNC-010), so copying it
 * out of the app is the whole point -- typing a mesh IP by hand invites
 * transcription errors.
 */
@Composable
private fun OwnMeshIpRow(ownMeshIp: String?) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ownMeshIp?.let { "Your mesh IP: $it" } ?: "Your mesh IP is not available yet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (ownMeshIp != null) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(ownMeshIp)) }) { Text("Copy") }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IntSettingRow(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun LongSettingRow(label: String, value: Long, onValueChange: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toLongOrNull()?.takeIf { it > 0 }?.let(onValueChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

/** Same warning-text convention [xyz.tetron.sync.ui.home.MediaAccessBanner]
 *  already uses (`MaterialTheme.colorScheme.error`) rather than inventing a
 *  separate amber "warning" color the app's theme does not otherwise have. */
@Composable
private fun PortWarning() {
    Text(
        "Must match the port your receiver is actually running on. If it doesn't, backups will fail with a socket error.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}
