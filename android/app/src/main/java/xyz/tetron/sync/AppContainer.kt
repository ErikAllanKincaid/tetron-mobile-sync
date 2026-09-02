// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.Executors
import uniffi.tetron_mobile_sync.SyncEngine
import uniffi.tetron_mobile_sync.SyncEngineInterface
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.bridge.MeshBridge
import xyz.tetron.sync.bridge.ProviderStatusCaller
import xyz.tetron.sync.delete.DeleteIntentSenderLauncher
import xyz.tetron.sync.delete.MediaStoreDeletionRequester
import xyz.tetron.sync.gates.GateNotificationCoalescer
import xyz.tetron.sync.media.AndroidMediaAccess
import xyz.tetron.sync.notifications.SyncNotifier
import xyz.tetron.sync.pipeline.AndroidDeviceStateProvider
import xyz.tetron.sync.pipeline.EngineTransferRunner
import xyz.tetron.sync.pipeline.FileRunHistoryStore
import xyz.tetron.sync.pipeline.RunFileLog
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
 * [pipeline]'s `deletionRequester` is the real [MediaStoreDeletionRequester],
 * but it needs an `Activity` to launch the system confirm dialog
 * (`ActivityResultContracts.StartIntentSenderForResult`), which this
 * process-scoped container does not have -- same seam split as the
 * SYNC-008 permission launchers. [deleteIntentSenderLauncher] is the
 * mutable indirection that makes that work without making [pipeline]
 * itself mutable: [xyz.tetron.sync.MainActivity] sets it once in
 * `onCreate`, and [MediaStoreDeletionRequester] forwards through whatever
 * is currently set (`null` only in the impossible window before that
 * happens, in which case a delete request is silently dropped rather than
 * crashing the pipeline thread).
 *
 * [notifier] finally gives SYNC-004's `onNotify` gate hook and SYNC-005's
 * completion result somewhere real to go -- both existed as plumbing with
 * no listener since their own requirements landed.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val bridge = MeshBridge(ProviderStatusCaller(appContext.contentResolver))

    /** `ownHostname` is read from the bridge's short-TTL cache lazily, only
     *  the first time a device label is needed (and by then a roster-facing
     *  screen has already warmed the cache), so this never forces a fresh
     *  cross-process call on the caller's thread. */
    val settingsStore: SettingsStore = SharedPreferencesSettingsStore(appContext) {
        (bridge.current() as? BridgeResponse.Snapshot)?.snapshot?.ownHostname
    }
    val mediaAccess = AndroidMediaAccess(appContext, scopeProvider = { settingsStore.backupScope() })
    val deviceState = AndroidDeviceStateProvider(appContext)
    /** SYNC-009: a rotating [FileRunHistoryStore.MAX_RUNS]-run log. On
     *  first construction it seeds itself from the pre-2026-09-02
     *  single-record `SharedPreferences` store so an existing install's
     *  last run is not lost. */
    val historyStore: RunHistoryStore =
        FileRunHistoryStore(java.io.File(appContext.filesDir, "run_history.log")).also { store ->
            if (store.lastRun() == null) {
                SharedPreferencesRunHistoryStore(appContext).lastRun()?.let(store::recordRun)
            }
        }

    /** SYNC-009: the last run's transferred-file list, persisted for the
     *  Progress screen (written by [xyz.tetron.sync.ui.home.HomeViewModel]
     *  on completion, read by it and the Progress screen). Covers
     *  Home-triggered runs; a periodic/network-change run still records a
     *  History summary but not a per-file list. */
    val runFileLog = RunFileLog(java.io.File(appContext.filesDir, "last_run_files.tsv"))

    /** Fixed ~6h window ([GateNotificationCoalescer.DEFAULT_WINDOW_MILLIS]).
     *  It used to be a Settings field; that was jargon with no user value
     *  and was removed. */
    private val coalescer = GateNotificationCoalescer()
    private val notifier = SyncNotifier(appContext)

    /** Set by [xyz.tetron.sync.MainActivity] once its `ActivityResultLauncher`
     *  is registered; see the class doc for why this is mutable. */
    var deleteIntentSenderLauncher: DeleteIntentSenderLauncher? = null

    private val deletionRequester = MediaStoreDeletionRequester(
        contentResolver = appContext.contentResolver,
        launcher = DeleteIntentSenderLauncher { intentSender -> deleteIntentSenderLauncher?.launch(intentSender) },
    )

    /** Held directly (not just wrapped inside [EngineTransferRunner]) so
     *  [xyz.tetron.sync.ui.home.HomeViewModel] can call `cancel()` (TODO #8)
     *  on the same engine instance a run is in flight on -- cancellation is
     *  a process-global request in the vendored fork (mirrors real Ctrl+C),
     *  so any instance's `cancel()` would work, but reusing this one avoids
     *  a second, pointless [SyncEngine] object. */
    val syncEngine: SyncEngineInterface = SyncEngine()

    val pipeline: SyncPipeline = SyncPipeline(
        bridge = bridge,
        targetProvider = { settingsStore.target() },
        sourcePathProvider = mediaAccess,
        deviceState = deviceState,
        transferRunner = EngineTransferRunner(syncEngine),
        historyStore = historyStore,
        coalescer = coalescer,
        gateConfig = { settingsStore.gateConfig() },
        backupScope = { settingsStore.backupScope() },
        deleteConfig = { settingsStore.deleteAfterBackupConfig() },
        onNotify = notifier::notifyGated,
        onRunCompleted = notifier::notifyRunCompleted,
        deletionRequester = deletionRequester,
        runFileLog = runFileLog,
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

    /** Called once at app startup ([TetronSyncApplication]). Uses `KEEP` so
     *  relaunching never resets an already-scheduled timer (spec/sync.py
     *  SYNC-006). A `null` cadence ("never") cancels any existing job. */
    fun schedulePeriodicWork() = applyPeriodicSchedule(ExistingPeriodicWorkPolicy.KEEP)

    /** Apply the current [SettingsStore.workCadenceHours] to WorkManager
     *  now. The Settings screen calls this on a user change with the
     *  default `UPDATE` policy so the new interval (or "never") takes effect
     *  immediately, not at next launch. */
    fun applyPeriodicSchedule(policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE) {
        val workManager = WorkManager.getInstance(appContext)
        val hours = settingsStore.workCadenceHours()
        if (hours == null) {
            SyncWorkScheduler.cancel(workManager)
        } else {
            SyncWorkScheduler.schedule(workManager, Duration.ofHours(hours), policy)
        }
    }
}
