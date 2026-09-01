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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import xyz.tetron.sync.scope.BacklogEstimate
import xyz.tetron.sync.scope.BackupScope
import xyz.tetron.sync.scope.MediaEntry
import xyz.tetron.sync.scope.MediaKind
import xyz.tetron.sync.pipeline.SyncTarget
import xyz.tetron.sync.scope.Preset
import xyz.tetron.sync.settings.DeviceLabel
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * SYNC-009 Settings screen. Mesh-peer selection lives on Home
 * ([xyz.tetron.sync.ui.home.HomeScreen]); this screen carries gate toggles,
 * what-gets-backed-up scope, an Advanced section (bandwidth cap + the
 * device-label override), delete-after-backup opt-in, the periodic-backup
 * interval (opt-in, "Never" by default), and a bottom "Connection" section
 * holding the two values that must match the receiver exactly -- the port
 * and, for advanced setups, the module name. Notification channel copy is a
 * separate, not-yet-built slice (spec/sync.py: "exact copy is
 * implementation-time").
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
            "Which mesh peer to back up to is configured on the Home screen.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(24.dp))
        WhatGetsBackedUpSection(
            scope = state.backupScope,
            preset = state.preset,
            estimate = state.estimate,
            estimateLoading = state.estimateLoading,
            onScopeChange = viewModel::setBackupScope,
            onPresetChange = viewModel::selectPreset,
        )

        Spacer(Modifier.height(24.dp))
        AdvancedSection(
            scope = state.backupScope,
            onScopeChange = viewModel::setBackupScope,
            target = state.target,
            deviceLabelError = state.deviceLabelError,
            ownHostname = state.ownHostname,
            onCommitDeviceLabel = viewModel::setDeviceLabel,
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("Delete after backup")
        SwitchRow("Delete photos from this device once backed up", state.deleteAfterBackupEnabled) {
            viewModel.setDeleteAfterBackupEnabled(it)
        }
        Text(
            "Off by default. Only files this run actually transferred are ever deleted, and the system always asks to confirm. Types you turn off above stay on your phone.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("Schedule")
        PeriodicIntervalRow(
            value = state.workCadenceHours,
            onValueChange = viewModel::setWorkCadenceHours,
        )
        Text(
            "Off by default. \"Back up now\" on the Home screen always works regardless of this.",
            style = MaterialTheme.typography.bodySmall,
        )

        // Port and the module name are the two values that must match the
        // receiver exactly or the connection fails -- install-once,
        // rarely touched, so they sit in their own section at the bottom
        // rather than up top.
        Spacer(Modifier.height(24.dp))
        SectionHeader("Connection")
        IntSettingRow(
            label = "Port",
            value = state.target?.port ?: 28873,
            onValueChange = viewModel::setTargetPort,
        )
        PortWarning()
        Spacer(Modifier.height(16.dp))
        ModuleNameField(
            target = state.target,
            error = state.moduleNameError,
            onCommit = viewModel::setTargetModule,
        )

        Spacer(Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(state.engineInfoLine, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The phone's own mesh IP, informational only (SYNC-010, amended
 * 2026-08-31): the receiver allow-lists this phone by hostname from its own
 * mesh roster (`tetron-sync-receiver allow add-peer <hostname>`), so there
 * is nothing for the user to copy or transcribe here.
 */
@Composable
private fun OwnMeshIpRow(ownMeshIp: String?) {
    Text(
        text = ownMeshIp?.let { "This device's mesh IP: $it" } ?: "This device's mesh IP is not available yet",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * SYNC-010: the per-device folder name the receiver stores this phone's
 * backup under. Defaults to this phone's mesh hostname (else `phone-<hex>`),
 * so most people never touch it -- it lives in Advanced. Changing it after
 * backups have run starts a fresh folder on the receiver and leaves the old
 * one untouched, so a confirm dialog gates the change.
 */
@Composable
private fun DeviceLabelField(
    target: SyncTarget?,
    error: String?,
    currentHostname: String?,
    onCommit: (String) -> Unit,
) {
    if (target == null) return
    val persisted = target.deviceLabel
    var text by remember(persisted) { mutableStateOf(persisted) }
    var showConfirm by remember { mutableStateOf(false) }
    val trimmed = text.trim()
    val changed = trimmed != persisted
    val liveValid = DeviceLabel.validate(text) is DeviceLabel.Result.Valid
    // M6c: the phone's mesh hostname changed since the label was snapshotted.
    // Offer to adopt it (through the same confirm dialog), never silently.
    val hostnameSuggestion = currentHostname
        ?.takeIf { it != persisted && DeviceLabel.normalizedOrNull(it) == it }

    Text("Device label", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = error != null || (text.isNotEmpty() && !liveValid),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { showConfirm = true },
            enabled = changed && liveValid,
        ) { Text("Save") }
    }
    Text(
        text = error
            ?: "Files land in $trimmed/ inside your backup folder on the receiver.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (hostnameSuggestion != null) {
        Text(
            "This phone's mesh hostname is now \"$hostnameSuggestion\". Backups still go to \"$persisted\".",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            text = hostnameSuggestion
            showConfirm = true
        }) { Text("Use \"$hostnameSuggestion\"") }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Change device label?") },
            text = {
                Text(
                    "Photos already backed up under \"$persisted\" stay on the " +
                        "receiver in that folder. New backups go to \"$trimmed\". " +
                        "Nothing is moved.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onCommit(text)
                }) { Text("Change") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Advanced: the rsync module name. A fixed default (`tetron-sync`) that the
 * receiver uses too; overriding it only makes sense for a multi-folder
 * receiver, and then it must be set to match on both sides by hand -- the
 * same "coordinated value, no discovery" contract as the port, which is why
 * it sits beside it. Reachable only once a target exists. Blank or the
 * literal default clears the override.
 */
@Composable
private fun ModuleNameField(
    target: SyncTarget?,
    error: String?,
    onCommit: (String) -> Unit,
) {
    if (target == null) return
    val persisted = target.module
    var text by remember(persisted) { mutableStateOf(persisted) }
    val trimmed = text.trim()
    val changed = trimmed != persisted

    Text("Receiver module name", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = error != null,
            placeholder = { Text(SyncTarget.DEFAULT_MODULE) },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCommit(text) }, enabled = changed) { Text("Save") }
    }
    Text(
        text = error
            ?: "Leave as \"${SyncTarget.DEFAULT_MODULE}\" unless you changed it on the receiver. Must match exactly.",
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
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

private val PERIODIC_INTERVAL_CHOICES_HOURS = listOf(6L, 12L, 24L, 48L)

/**
 * "Periodic backup interval" -- a `null` value is "Never" (the default;
 * periodic backup is opt-in). Applies immediately, so there is no
 * "takes effect next launch" caption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodicIntervalRow(value: Long?, onValueChange: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value?.let { "Every ${it}h" } ?: "Never",
            onValueChange = {},
            readOnly = true,
            label = { Text("Periodic backup interval") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Never") },
                onClick = {
                    onValueChange(null)
                    expanded = false
                },
            )
            PERIODIC_INTERVAL_CHOICES_HOURS.forEach { hours ->
                DropdownMenuItem(
                    text = { Text("Every ${hours}h") },
                    onClick = {
                        onValueChange(hours)
                        expanded = false
                    },
                )
            }
        }
    }
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

// --- SYNC-012: what gets backed up (scope toggles, size cap, presets,
//     backlog estimate + Preview sheet) ---

/** ~1.5 GB, the default the size cap jumps to when first switched on
 *  (matches the mockup); the user then edits it. */
private const val DEFAULT_MAX_SIZE_MB = 1_500L

/** The default the bandwidth limit jumps to when first switched on. */
private const val DEFAULT_BWLIMIT_KIB = 1_024L

private const val BYTES_PER_MB = 1024L * 1024L

private fun presetLabel(preset: Preset): String = when (preset) {
    Preset.Everything -> "Everything"
    Preset.PhotosOnly -> "Photos only"
    Preset.Lean -> "Lean"
    Preset.Custom -> "Custom"
}

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.Jpeg -> "JPEG photos"
    MediaKind.Heic -> "HEIC photos"
    MediaKind.Raw -> "Raw photos"
    MediaKind.Video -> "Videos"
    MediaKind.Other -> "Other files"
}

/** "1 photo" / "3 photos" -- the estimate line has small counts often
 *  enough that a bare "1 videos" reads wrong. */
private fun countOf(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

/** Coarse, human-readable byte size ("0 B", "4.2 MB", "61 GB"). Not exact
 *  -- this is an estimate surface. */
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

@Composable
private fun WhatGetsBackedUpSection(
    scope: BackupScope,
    preset: Preset,
    estimate: BacklogEstimate,
    estimateLoading: Boolean,
    onScopeChange: (BackupScope) -> Unit,
    onPresetChange: (Preset) -> Unit,
) {
    SectionHeader("What gets backed up")

    PresetDropdownRow(preset = preset, onPresetChange = onPresetChange)
    Spacer(Modifier.height(8.dp))

    SwitchRow("JPEG photos", scope.includeJpeg) { onScopeChange(scope.copy(includeJpeg = it)) }
    SwitchRow("HEIC photos", scope.includeHeic) { onScopeChange(scope.copy(includeHeic = it)) }
    SwitchRow("Raw photos", scope.includeRaw) { onScopeChange(scope.copy(includeRaw = it)) }
    SwitchRow("Videos", scope.includeVideos) { onScopeChange(scope.copy(includeVideos = it)) }
    SwitchRow("Other files", scope.includeOtherFiles) { onScopeChange(scope.copy(includeOtherFiles = it)) }
    Text(
        "\"Other files\" covers anything new your camera starts saving. Turn it off and only the types above are backed up.",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(8.dp))
    val capOn = scope.maxSizeBytes != null
    SwitchRow("Skip files larger than", capOn) { on ->
        onScopeChange(scope.copy(maxSizeBytes = if (on) DEFAULT_MAX_SIZE_MB * BYTES_PER_MB else null))
    }
    if (capOn) {
        IntSettingRow(
            label = "Maximum size (MB)",
            value = ((scope.maxSizeBytes ?: 0L) / BYTES_PER_MB).toInt(),
            onValueChange = { mb ->
                onScopeChange(scope.copy(maxSizeBytes = mb.coerceAtLeast(1).toLong() * BYTES_PER_MB))
            },
        )
    }

    Spacer(Modifier.height(12.dp))
    BacklogEstimateCard(estimate = estimate, loading = estimateLoading)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDropdownRow(preset: Preset, onPresetChange: (Preset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Custom is shown as the current value once a field is hand-edited, but
    // it is not a selectable template.
    val choices = listOf(Preset.Everything, Preset.PhotosOnly, Preset.Lean)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = presetLabel(preset),
            onValueChange = {},
            readOnly = true,
            label = { Text("Preset") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(presetLabel(choice)) },
                    onClick = {
                        onPresetChange(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BacklogEstimateCard(estimate: BacklogEstimate, loading: Boolean) {
    var showPreview by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("On this phone", style = MaterialTheme.typography.labelMedium)
            Text(
                "${countOf(estimate.photoCount, "photo")} · ${countOf(estimate.videoCount, "video")} · ${formatBytes(estimate.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text("This scope will upload", style = MaterialTheme.typography.labelMedium)
            Text(
                if (loading) "estimating…" else "~ ${formatBytes(estimate.includedBytes)} · ${countOf(estimate.includedCount, "file")}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showPreview = true }) { Text("Preview") }
            }
        }
    }
    if (showPreview) {
        ScopePreviewSheet(estimate = estimate, onDismiss = { showPreview = false })
    }
}

/**
 * SYNC-012: the "show me the detail behind this number" drill-down -- a
 * Material 3 modal bottom sheet over Settings, from the current scope, no
 * tunnel needed (the deferred real dry-run later adds server-accurate
 * columns to this same sheet). Not the Progress tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopePreviewSheet(estimate: BacklogEstimate, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Preview — current scope", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            Text("Will upload", style = MaterialTheme.typography.labelLarge)
            estimate.includedByKind.forEach { TallyRow(kindLabel(it.kind), it.count, it.bytes) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            TallyRow("Total", estimate.includedCount, estimate.includedBytes, bold = true)

            if (estimate.excludedByKind.isNotEmpty() || estimate.oversizeCount > 0) {
                Spacer(Modifier.height(16.dp))
                Text("Skipped", style = MaterialTheme.typography.labelLarge)
                estimate.excludedByKind.forEach { TallyRow("${kindLabel(it.kind)} (off)", it.count, it.bytes) }
                if (estimate.oversizeCount > 0) {
                    TallyRow("Over the size limit", estimate.oversizeCount, estimate.oversizeBytes)
                }
            }

            if (estimate.largestIncluded.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Largest included", style = MaterialTheme.typography.labelLarge)
                estimate.largestIncluded.forEach { LargestRow(it) }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "From this phone. The server may already have some; a run sends only what is missing.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TallyRow(label: String, count: Int, bytes: Long, bold: Boolean = false) {
    val weight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
        Text(
            "$count · ${formatBytes(bytes)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
        )
    }
}

@Composable
private fun LargestRow(entry: MediaEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            entry.displayName,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f, fill = false).padding(end = 16.dp),
        )
        Text(formatBytes(entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AdvancedSection(
    scope: BackupScope,
    onScopeChange: (BackupScope) -> Unit,
    target: SyncTarget?,
    deviceLabelError: String?,
    ownHostname: String?,
    onCommitDeviceLabel: (String) -> Unit,
) {
    SectionHeader("Advanced")
    val limitOn = scope.bwlimitKib != null
    SwitchRow("Limit upload bandwidth", limitOn) { on ->
        onScopeChange(scope.copy(bwlimitKib = if (on) DEFAULT_BWLIMIT_KIB else null))
    }
    if (limitOn) {
        IntSettingRow(
            label = "Bandwidth limit (KB/s)",
            value = (scope.bwlimitKib ?: 0L).toInt(),
            onValueChange = { kbs -> onScopeChange(scope.copy(bwlimitKib = kbs.coerceAtLeast(1).toLong())) },
        )
    }

    Spacer(Modifier.height(16.dp))
    DeviceLabelField(
        target = target,
        error = deviceLabelError,
        currentHostname = ownHostname,
        onCommit = onCommitDeviceLabel,
    )
}
