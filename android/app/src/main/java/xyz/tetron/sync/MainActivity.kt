// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import xyz.tetron.sync.ui.TetronSyncApp

/**
 * SYNC-009: this app's only Activity, hosting the [TetronSyncApp] Compose
 * shell (bottom nav across Home/Progress/History/Settings) over
 * [TetronSyncApplication.container]. SYNC-001's original scaffold (a bare
 * "call `SyncEngine.version()`, render the result" screen, proving the FFI
 * chain end to end) is gone -- that job is done; [xyz.tetron.sync.ui.home
 * .HomeViewModel] now calls into the real pipeline instead.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TetronSyncApplication).container
        setContent {
            TetronSyncApp(container)
        }
    }
}