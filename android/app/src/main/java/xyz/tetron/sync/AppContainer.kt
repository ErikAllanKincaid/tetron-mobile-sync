// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.Executors
import uniffi.tetron_mobile_sync.SyncEngine
import xyz.tetron.sync.bridge.MeshBridge
import xyz.tetron.sync.bridge.ProviderStatusCaller
import xyz.tetron.sync.gates.GateNotificationCoalescer
import xyz.tetron.sync.media.AndroidMediaAccess
import xyz.tetron.sync.notifications.SyncNotifier
import xyz.tetron.sync.pipeline.AndroidDeviceStateProvider
import xyz.tetron.sync.pipeline.EngineTransferRunner
import xyz.tetron.sync.pipeline.RunHistoryStore
import xyz.tetron.sync.pipeline.SharedPreferencesRunHistoryStore
import xyz.tetron.sync.pipeline.SyncPipeline
import xyz.tetron.sync.settings.SettingsStore
import xyz.tetron.sync.settings.SharedPreferencesSettingsStore
import xyz.tetron.sync.trigger.AndroidNetworkChangeTrigger
import xyz.tetron.sync.trigger.ManualTrigger
import xyz.tetron.sync.trigger.NetworkChangeDispatcher
import xyz.tetron.sync.trigger.PipelineRunner
import xyz.tetron.sync.trigger.SyncWorkScheduler
import xyz.tetron.sync.trigger.SyncWorkerFactory

/**
 * SYNC-009: hand-rolled composition root -- no DI framework, matching this
 * repo's own minimal-dependency convention. One instance per process, held
 * by [TetronSyncApplication] and handed down through Compose, wiring the
 * real seams SYNC-002..008 built and left as contracts: [bridge]
 * (SYNC-003), [pipeline] (SYNC-005), [mediaAccess] as the real
 * `SourcePathProvider` (SYNC-008), and [settingsStore]-backed
 * `TargetProvider`/`gateConfig`/`deleteConfig` suppliers (SYNC-004/007/009)
 * so a Settings-screen toggle reaches the very next pipeline run with no
 * rebuild of [pipeline] itself.
 *
 * [pipeline]'s `deletionRequester` stays the SYNC-007 no-op default here --
 * the real `MediaStoreDeletionRequester` needs an `Activity` to launch the
 * system confirm dialog and lands in a later slice of this same
 * requirement; until then delete-after-backup is inert even if a tester
 * flips the setting on, which is the correct default for "never silently
 * deletes" rather than a half-finished delete path.
 *
 * [notifier] finally gives SYNC-004's `onNotify` gate hook and SYNC-005's
 * completion result somewhere real to go -- both existed as plumbing with
 * no listener since their own requirements landed.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore: SettingsStore = SharedPreferencesSettingsStore(appContext)
    val bridge = MeshBridge(ProviderStatusCaller(appContext.contentResolver))
    val mediaAccess = AndroidMediaAccess(appContext)
    val deviceState = AndroidDeviceStateProvider(appContext)
    val historyStore: RunHistoryStore = SharedPreferencesRunHistoryStore(appContext)

    /** Read once at construction, not live like `gateConfig`/`deleteConfig`
     *  -- same "takes effect at next app start" bar as [schedulePeriodicWork],
     *  since the coalescer (unlike those two suppliers) is a stateful
     *  per-reason timer, not a pure per-run read. */
    private val coalescer = GateNotificationCoalescer(
        windowMillis = Duration.ofHours(settingsStore.coalesceWindowHours()).toMillis(),
    )
    private val notifier = SyncNotifier(appContext)

    val pipeline: SyncPipeline = SyncPipeline(
        bridge = bridge,
        targetProvider = { settingsStore.target() },
        sourcePathProvider = mediaAccess,
        deviceState = deviceState,
        transferRunner = EngineTransferRunner(SyncEngine()),
        historyStore = historyStore,
        coalescer = coalescer,
        gateConfig = { settingsStore.gateConfig() },
        deleteConfig = { settingsStore.deleteAfterBackupConfig() },
        onNotify = notifier::notifyGated,
        onRunCompleted = notifier::notifyRunCompleted,
    )

    /** SYNC-006's trigger layer, real wiring deferred until SYNC-008/009 --
     *  this is that follow-up. [pipelineRunner] is the one seam all three
     *  triggers (manual/periodic/network-change) share. */
    val pipelineRunner = PipelineRunner { pipeline.run() }
    val manualTrigger = ManualTrigger(pipelineRunner)
    val workerFactory = SyncWorkerFactory(pipelineRunner)

    private val networkChangeExecutor = Executors.newSingleThreadExecutor()
    val networkChangeTrigger = AndroidNetworkChangeTrigger(
        connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager,
        dispatcher = NetworkChangeDispatcher(pipelineRunner, networkChangeExecutor),
    )

    /** Called once at app startup ([TetronSyncApplication]). `KEEP` (inside
     *  [SyncWorkScheduler]) means a cadence change here only takes effect
     *  the next time the app process starts, not retroactively -- v1's
     *  accepted bar (spec/sync.py SYNC-006: relaunching the app must not
     *  reset an already-running timer). */
    fun schedulePeriodicWork() {
        SyncWorkScheduler.schedule(
            WorkManager.getInstance(appContext),
            interval = Duration.ofHours(settingsStore.workCadenceHours()),
        )
    }
}
