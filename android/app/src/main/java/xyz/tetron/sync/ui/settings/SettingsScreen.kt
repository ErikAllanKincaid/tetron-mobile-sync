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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.tetron.sync.bridge.BridgePeer

/**
 * SYNC-009 Settings screen: gate toggles + values, target config
 * (mesh-IP picker roster-based; module name; display name), delete-after-
 * backup opt-in, WorkManager cadence, notification coalescing window.
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
        SectionHeader("Backup target")
        TargetEditor(target = state.target, rosterPeers = state.rosterPeers, onSave = viewModel::setTarget)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetEditor(
    target: xyz.tetron.sync.pipeline.SyncTarget?,
    rosterPeers: List<BridgePeer>,
    onSave: (xyz.tetron.sync.pipeline.SyncTarget?) -> Unit,
) {
    var selectedPeer by remember(target) { mutableStateOf<BridgePeer?>(rosterPeers.firstOrNull { it.ip == target?.meshIp }) }
    var moduleName by remember(target) { mutableStateOf(target?.module ?: "") }
    var displayName by remember(target) { mutableStateOf(target?.displayName ?: "") }
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
    OutlinedTextField(
        value = displayName,
        onValueChange = { displayName = it },
        label = { Text("Display name") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            val peer = selectedPeer
            if (peer != null && moduleName.isNotBlank()) {
                onSave(
                    xyz.tetron.sync.pipeline.SyncTarget(
                        displayName = displayName.ifBlank { peer.hostname ?: peer.ip },
                        meshIp = peer.ip,
                        module = moduleName,
                    ),
                )
            }
        },
        enabled = selectedPeer != null && moduleName.isNotBlank(),
    ) {
        Text("Save target")
    }
}
