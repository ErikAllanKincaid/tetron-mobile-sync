// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import uniffi.tetron_mobile_sync.SyncProgressListener
import uniffi.tetron_mobile_sync.SyncRunOptions
import uniffi.tetron_mobile_sync.SyncTransferOutcome
import xyz.tetron.sync.gates.GateReason

/**
 * SYNC-005: the single reconfigurable backup target (decision #9). The
 * roster is the only place peers are discovered (SYNC-003); [meshIp] is
 * matched against the bridge snapshot's peer list to find a `ConnKind` for
 * the direct-only gate.
 *
 * [module] is a fixed coordinated default ([DEFAULT_MODULE], `tetron-sync`):
 * the receiver names its one rsync module the same thing, and normal setups
 * never touch it on either side. An advanced user can override it, but then
 * it must match the receiver exactly -- the same "coordinated value, no
 * discovery" contract as [port].
 *
 * [deviceLabel] is the top path component the pipeline writes under on the
 * receiver: `rsync://<meshIp>:<port>/<module>/<deviceLabel>/...` (SYNC-010).
 * One receiver module holds every device; the client creates `<deviceLabel>/`
 * itself with `--mkpath`. The persisted store guarantees a non-blank value
 * (defaults to the phone's own mesh hostname, else a stable first-run
 * fallback), so consumers do not handle blank here; `""` from a bare
 * constructor means "let the store fill it in" and is only seen before
 * [SettingsStore.setTarget] normalizes.
 */
data class SyncTarget(
    val meshIp: String,
    val module: String = DEFAULT_MODULE,
    val port: Int = 28873,
    val deviceLabel: String = "",
) {
    companion object {
        /** The rsync module every phone uses unless an advanced user
         *  overrides it on both sides. Must equal `tetron-sync-receiver`'s
         *  `config::DEFAULT_MODULE`. Not photo-specific -- it is a protocol
         *  token, not a folder. */
        const val DEFAULT_MODULE = "tetron-sync"
    }
}

/**
 * A settings-backed provider for the configured target (SYNC-009 owns the
 * real persisted implementation; this is the contract the pipeline
 * consumes). `null` means no target has been configured yet.
 */
fun interface TargetProvider {
    fun currentTarget(): SyncTarget?
}

/**
 * The resolved backup source for one run. [rootPath] is the transfer_args
 * source operand oc-rsync opens files under; [filesFromPath] is an optional
 * local `--files-from` list (newline-delimited relative filenames) that, when
 * set, makes oc-rsync open exactly those entries under [rootPath] instead of
 * recursively walking the directory itself.
 *
 * [filesFromPath] exists because raw directory *enumeration*
 * (`readdir`/`jwalk`) is filtered per-app-UID by Android's Scoped Storage
 * FUSE layer on API 29+ -- a real device only ever sees 1 entry under
 * `DCIM/Camera` via a raw walk, even with `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`
 * granted (root-caused during SYNC-011). Opening a *known* path directly is
 * NOT filtered the same way (confirmed on-device), so [AndroidMediaAccess]
 * enumerates via `MediaStore` instead of a raw walk on API 29+ and stages the
 * result as this list; `null` (pre-29, or any future non-media source type)
 * falls back to oc-rsync's own recursive walk, unchanged from SYNC-008 v1.
 *
 * SYNC-012: [skippedOversizeCount] is how many entries the resolver's
 * [xyz.tetron.sync.scope.BackupScope] dropped for exceeding the size cap
 * (in-scope by type, only excluded by size). It rides here so the pipeline
 * can put it in [RunRecord.skippedOversize] without a second query -- the
 * count is a by-product of staging the `--files-from` list. `0` on the
 * pre-29 walk path (no scope applied there).
 */
data class SourceSpec(
    val rootPath: String,
    val filesFromPath: String? = null,
    val skippedOversizeCount: Int = 0,
)

/**
 * Resolves the backup source for one run (SYNC-008 owns the real
 * DCIM/permission-aware implementation). `null` means the source is not
 * available (permission missing, path does not exist) -- surfaced as a
 * gate-style failure, never a crash (spec/sync.py SYNC-008).
 */
fun interface SourcePathProvider {
    fun resolve(): SourceSpec?
}

/**
 * Local, no-network device-state reads that feed [xyz.tetron.sync.gates.GateInputs].
 * The real implementation wraps `ConnectivityManager`/`BatteryManager`
 * (see [xyz.tetron.sync.gates] doc); tests use a fake.
 */
interface DeviceStateProvider {
    fun isWifiConnected(): Boolean
    fun isCharging(): Boolean
    fun batteryPercent(): Int
}

/**
 * Abstracts the SYNC-002 UniFFI engine call so the pipeline (and its JVM
 * unit tests) never depend on the native `.so` being loaded -- same
 * fake-the-boundary pattern as [xyz.tetron.sync.bridge.StatusCaller].
 * The real implementation is [xyz.tetron.sync.pipeline.EngineTransferRunner].
 */
fun interface TransferRunner {
    fun run(
        source: String,
        destination: String,
        options: SyncRunOptions,
        progress: SyncProgressListener?,
    ): SyncTransferOutcome
}

/**
 * One completed transfer attempt. `interrupted` (rsync exit 23/24 embedded
 * in an otherwise-successful [SyncTransferOutcome]) is explicitly NOT a
 * failure (spec/sync.py SYNC-005: "a partial/interrupted run is NOT an
 * error state"); `failed` covers a hard engine exception, where no
 * per-file counts are available. `cancelled` (TODO #8) is the third case:
 * also a thrown engine exception, but specifically exit code 20 (`RERR_
 * SIGNAL`) from [uniffi.tetron_mobile_sync.SyncEngineInterface.cancel] --
 * the user asked to stop, not a fault, so it is reported distinctly from
 * `failed` even though both arrive via the same catch branch in
 * [SyncPipeline].
 */
data class RunRecord(
    val timestampMillis: Long,
    val added: Int,
    val skipped: Int,
    val failed: Int,
    val interrupted: Boolean,
    val failureReason: String?,
    val cancelled: Boolean = false,
    /** SYNC-012 (decision A5): files the backup scope's size cap held back
     *  this run -- distinct from [skipped] (which is dominated by rsync's
     *  "already on the server" same-size-same-mtime skips). History surfaces
     *  this as its own "N too large" line; there is no notification for it
     *  (decision B5). `0` when no cap is set or on the pre-29 walk path. */
    val skippedOversize: Int = 0,
)

/** Persists the most recent run (SYNC-009's History screen: last run time +
 *  counts + last failure reason). SYNC-009 owns the real persisted store;
 *  [InMemoryRunHistoryStore] is a usable default until then. */
interface RunHistoryStore {
    fun recordRun(record: RunRecord)
    fun lastRun(): RunRecord?
}

class InMemoryRunHistoryStore : RunHistoryStore {
    @Volatile private var last: RunRecord? = null
    override fun recordRun(record: RunRecord) {
        last = record
    }
    override fun lastRun(): RunRecord? = last
}

/** What one [SyncPipeline.run] call produced. */
sealed class PipelineResult {
    /** A trigger arrived while a run was already in progress; a no-op
     *  (spec/sync.py SYNC-005 reentrancy). */
    data object AlreadyRunning : PipelineResult()

    /** Gated: the AND-matrix (SYNC-004) blocked this cycle before any
     *  network activity; [reason] has already been offered to the
     *  notification coalescer. */
    data class Gated(val reason: GateReason) : PipelineResult()

    /** A transfer was actually attempted; [record] is also what was written
     *  to the history store. */
    data class Ran(val record: RunRecord) : PipelineResult()
}
