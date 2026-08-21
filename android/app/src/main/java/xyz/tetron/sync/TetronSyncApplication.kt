// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import android.app.Application
import androidx.work.Configuration

/**
 * SYNC-009: registers [AppContainer.workerFactory] with WorkManager (its
 * default reflective factory can not carry [xyz.tetron.sync.trigger
 * .PipelineRunner] into [xyz.tetron.sync.trigger.SyncWorker], per SYNC-006's
 * own doc comment) and starts the two triggers that must run for the
 * lifetime of the process rather than a single screen: the periodic job
 * ([AppContainer.schedulePeriodicWork], `KEEP` so relaunching never resets
 * an already-scheduled timer) and the network-change callback
 * ([AndroidNetworkChangeTrigger.register] -- "not a wake-up mechanism" per
 * spec/sync.py SYNC-006, so tying it to the process rather than one
 * Activity's onStart/onStop is the right lifetime for this single-activity
 * app: there is no other component whose narrower lifecycle would make
 * more sense, and the OS killing the whole process is the only real
 * teardown that ever happens).
 */
class TetronSyncApplication : Application(), Configuration.Provider {
    val container by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        container.schedulePeriodicWork()
        container.networkChangeTrigger.register()
    }
}
