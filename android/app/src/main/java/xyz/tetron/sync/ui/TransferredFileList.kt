// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.tetron.sync.pipeline.TransferredFileLine

/** SYNC-009: the newest-first "✓ path  ·  size" transfer list, shared by
 *  the Progress screen (live and persisted) and the History screen's
 *  expanded latest run. */
@Composable
fun TransferredFileList(lines: List<TransferredFileLine>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(lines) { line ->
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
                Text(formatFileSize(line.bytes), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Coarse, human-readable byte size ("0 B", "4.2 MB", "61 GB"). */
fun formatFileSize(bytes: Long): String {
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
