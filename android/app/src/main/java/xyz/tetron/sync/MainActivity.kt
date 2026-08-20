// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import xyz.tetron.sync.media.AndroidMediaAccess
import xyz.tetron.sync.ui.TetronSyncApp

/**
 * SYNC-009: this app's only Activity, hosting the [TetronSyncApp] Compose
 * shell (bottom nav across Home/Progress/History/Settings) over
 * [TetronSyncApplication.container]. SYNC-001's original scaffold (a bare
 * "call `SyncEngine.version()`, render the result" screen, proving the FFI
 * chain end to end) is gone -- that job is done; [xyz.tetron.sync.ui.home
 * .HomeViewModel] now calls into the real pipeline instead.
 *
 * SYNC-008's runtime permission request must be registered here, before
 * `onCreate`'s `STARTED` state (`registerForActivityResult` requires it),
 * which is why [xyz.tetron.sync.ui.home.HomeScreen] cannot own the
 * launcher itself -- it only gets a trigger lambda. The result callback is
 * a no-op: [xyz.tetron.sync.ui.home.HomeViewModel]'s own poll picks up
 * whatever grant the user ended up with either way.
 *
 * The notification permission (SYNC-009's [xyz.tetron.sync.notifications
 * .SyncNotifier]) is requested unconditionally here at first launch,
 * unlike media access's press-a-button flow: unlike photo access there is
 * no single natural in-app action to hang the request on, and a
 * background-triggered run (WorkManager/network-change) has no UI moment
 * at all to request it from -- unusual for this app's own "not eagerly"
 * precedent (SYNC-008), but the least-bad option given no better trigger
 * point exists.
 */
class MainActivity : ComponentActivity() {
    private val requestMediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val container = (application as TetronSyncApplication).container
        setContent {
            TetronSyncApp(
                container = container,
                onRequestMediaPermission = {
                    requestMediaPermissionLauncher.launch(AndroidMediaAccess.requiredPermissions(Build.VERSION.SDK_INT))
                },
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
