// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import xyz.tetron.sync.pipeline.RunRecord
import xyz.tetron.sync.pipeline.TransferredFileLine
import xyz.tetron.sync.ui.TransferredFileList

/**
 * SYNC-009 History screen (2026-09-02): a scrollable list of the last
 * [xyz.tetron.sync.pipeline.FileRunHistoryStore.MAX_RUNS] runs, newest
 * first. The top (current/last) row expands to its persisted transferred-
 * file list; older rows show the summary only. "Clear history" at the
 * bottom drops every row but the most recent, behind a confirm dialog --
 * it changes nothing about the receiver, resume state, or the next run.
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (state.runs.isEmpty()) {
            Text("No backup has run yet", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(state.runs) { index, run ->
                RunRow(
                    run = run,
                    latestFiles = if (index == 0) state.latestRunFiles else emptyList(),
                    expandable = index == 0,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }

        TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Clear history")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear backup history?") },
            text = {
                Text("This does not change your backed-up files or what the next backup does. The most recent run is kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RunRow(run: RunRecord, latestFiles: List<TransferredFileLine>, expandable: Boolean) {
    var expanded by remember(run.timestampMillis) { mutableStateOf(false) }
    val canExpand = expandable && latestFiles.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (canExpand) it.clickable { expanded = !expanded } else it }
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(run.timestampMillis)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${run.added} added · ${run.skipped} skipped" +
                        if (run.failed > 0) " · ${run.failed} failed" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (canExpand) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        }

        if (run.skippedOversize > 0) {
            Text(
                "${run.skippedOversize} skipped: larger than the size limit in Settings",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (run.interrupted) {
            Text("Interrupted — will resume next run", style = MaterialTheme.typography.bodySmall)
        }
        if (run.cancelled) {
            Text("Cancelled", style = MaterialTheme.typography.bodySmall)
        }
        run.failureReason?.let {
            Text(
                "Failed: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (canExpand && expanded) {
            Spacer(Modifier.height(8.dp))
            TransferredFileList(latestFiles, Modifier.fillMaxWidth().heightIn(max = 400.dp))
        }
    }
}
