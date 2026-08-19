// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.tetron_mobile_sync.SyncEngine

/**
 * SYNC-001: minimal scaffold screen. The only real behavior is proving the
 * whole FFI chain end to end: construct [SyncEngine] across UniFFI/JNA,
 * call `version()` off the main thread (same pattern tetron-mobile uses
 * for every FFI call), and render the result. The product UI is the
 * SYNC-009 pass; nothing here survives it except the pattern.
 */
class MainActivity : ComponentActivity() {
    private var engineVersion by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(120.dp))
                    Text("tetron sync", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = engineVersion?.let { AppInfo.describeEngine(it) } ?: "engine not started",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Back up now",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .clickable { fetchVersion() }
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    private fun fetchVersion() {
        lifecycleScope.launch {
            engineVersion = withContext(Dispatchers.IO) {
                SyncEngine().version()
            }
        }
    }
}