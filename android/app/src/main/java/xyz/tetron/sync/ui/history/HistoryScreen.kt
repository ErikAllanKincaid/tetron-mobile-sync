// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val record by viewModel.lastRun.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        val r = record
        if (r == null) {
            Text("No backup has run yet.", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        Text(
            DateFormat.getDateTimeInstance().format(Date(r.timestampMillis)),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text("${r.added} added, ${r.skipped} skipped, ${r.failed} failed", style = MaterialTheme.typography.bodyLarge)
        if (r.skippedOversize > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${r.skippedOversize} skipped: larger than the size limit in Settings",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (r.interrupted) {
            Spacer(Modifier.height(8.dp))
            Text("Interrupted -- will resume next run", style = MaterialTheme.typography.bodyMedium)
        }
        r.failureReason?.let {
            Spacer(Modifier.height(8.dp))
            Text("Last failure: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}
