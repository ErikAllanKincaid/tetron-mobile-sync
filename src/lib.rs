//! tetron-mobile-sync: GPL-3.0 Android photo-backup addon for the tetron
//! mesh. UniFFI cdylib; the Kotlin/Compose app in `android/` is the UI.
//!
//! SYNC-001 scaffolded the crate + FFI chain (see `version()`). SYNC-002
//! embeds the oc-rsync engine: `SyncEngine::run_client` runs a real rsync
//! client transfer built on the vendored fork's core client
//! (`vendor/oc-rsync/crates/core`, `run_client_with_observer`), with
//! `--partial` resume semantics always on and per-file progress pushed to a
//! Kotlin-implemented `SyncProgressListener` callback. The surface is typed
//! (source URL string, destination URL string, a small options record)
//! rather than a raw argv passthrough, because oc-rsync's core client does
//! not parse argv itself (its `cli` crate does, and does not expose a
//! progress observer); see spec/sync.py SYNC-002 for the rationale.
//!
//! Threading contract: `run_client` is synchronous and blocking (network I/O
//! on the calling thread). The Kotlin side must call it off the main thread
//! (SYNC-005 pipeline), matching how tetron-mobile calls its daemon methods.

use std::num::NonZeroU64;
use std::sync::Arc;

use oc_rsync_core::client::{
    BandwidthLimit, ClientConfig, ClientProgressObserver, ClientProgressUpdate, ClientSummary,
    FilesFromSource, TransferTimeout, run_client_with_observer,
};

uniffi::setup_scaffolding!();

/// The embedded engine object. `version()` dates from SYNC-001;
/// `run_client` is the SYNC-002 transfer surface.
#[derive(uniffi::Object)]
pub struct SyncEngine {
    app_version: String,
}

/// Run options for [`SyncEngine::run_client`] (SYNC-002).
///
/// Maps onto the oc-rsync core client's typed builder. `--partial` is NOT an
/// option here: it is always on (SYNC-002 semantics).
#[derive(Debug, Default, Clone, uniffi::Record)]
pub struct SyncRunOptions {
    /// `-r` -- recurse into subdirectories.
    pub recursive: bool,
    /// `-t` -- preserve modification times.
    pub times: bool,
    /// `-l` -- preserve symlinks as symlinks.
    pub links: bool,
    /// `--modify-window` -- timestamp comparison slack in seconds
    /// (Android storage may stamp filesystem times with coarse granularity).
    pub modify_window_secs: Option<i64>,
    /// `--bwlimit` -- transfer ceiling in KiB/s.
    pub bwlimit_kib: Option<u64>,
    /// `--files-from` -- absolute path to a local, newline-delimited list of
    /// filenames relative to `source`. When set, oc-rsync opens exactly
    /// those entries instead of recursively walking `source` itself.
    ///
    /// Exists for Android API 29+: raw directory *enumeration* is filtered
    /// per-app-UID by Scoped Storage's FUSE layer (root-caused SYNC-011 --
    /// a real device's own recursive walk of `DCIM/Camera` sees only 1
    /// entry even with `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` granted), but
    /// opening a *known* path directly is not filtered the same way. The
    /// Kotlin side enumerates via `MediaStore` and stages the result here;
    /// `None` (desktop, pre-29, or any future non-media source) falls back
    /// to oc-rsync's own recursive walk, unchanged from SYNC-002 v1.
    pub files_from_path: Option<String>,
    /// `--mkpath` -- create missing destination path components. SYNC-010:
    /// the push destination is `rsync://<ip>:<port>/<module>/<device-label>/`,
    /// so the per-device `<device-label>/` directory under the module root
    /// is created by the client on first use; the receiver never pre-seeds
    /// it and needs no per-device config. The oc-rsync core honors this for
    /// daemon sends (`client/remote/invocation/builder.rs`). Default `false`
    /// keeps every pre-SYNC-010 caller unchanged.
    pub mkpath: bool,
}

/// Per-file progress event pushed across the FFI while a transfer runs.
///
/// Mirrors oc-rsync's `ClientProgressUpdate` (see
/// vendor/oc-rsync/crates/core/src/client/progress.rs). `path` is the
/// transfer-relative path of the entry (None on the synthetic end-of-run
/// summary line). `files_done` is the 1-based count of regular files
/// transferred so far, `files_total` the full count for the run.
///
/// `is_transfer` and `is_final` exist for SYNC-007 (delete-after-backup):
/// together they identify the exact set of events that represent a
/// byte-verified regular-file data transfer completing, as opposed to a
/// mid-transfer tick, a skip, or a non-data action (directory/symlink/
/// hardlink) that the file-list walk still emits progress for. A caller
/// building a "files this run actually transferred" set must filter on
/// `is_transfer && is_final`, not merely on `path.is_some()`.
#[derive(Debug, uniffi::Record)]
pub struct SyncProgressEvent {
    pub path: Option<String>,
    pub total_bytes: Option<u64>,
    pub overall_transferred: u64,
    pub overall_total_bytes: Option<u64>,
    pub files_done: u64,
    pub files_total: u64,
    pub flist_eof: bool,
    pub transfer_complete: bool,
    /// Whether the underlying action is regular-file data transfer
    /// (`ClientEventKind::DataCopied`/`ReferenceCopied`) rather than a
    /// directory/symlink/hardlink/device/skip -- mirrors
    /// `ClientEventKind::is_transfer()`.
    pub is_transfer: bool,
    /// Whether this update is the completion tick for its entry (mirrors
    /// `ClientProgressUpdate::is_final()`), as opposed to an in-flight
    /// progress tick for a still-copying file.
    pub is_final: bool,
}

/// Callback interface the Kotlin app implements (UniFFI callback interface),
/// invoked synchronously on the calling thread for every progress update.
#[uniffi::export(callback_interface)]
pub trait SyncProgressListener: Send + Sync {
    fn on_progress(&self, event: SyncProgressEvent);
}

/// Outcome of a completed `run_client` transfer.
#[derive(Debug, uniffi::Record)]
pub struct SyncTransferOutcome {
    pub files_copied: u64,
    pub files_total: u64,
    pub bytes_copied: u64,
    pub bytes_sent: u64,
    pub matched_bytes: u64,
    /// Set when the transfer completed with receiver-side I/O errors (rsync
    /// exit codes such as 23 = partial transfer, 24 = vanished files).
    pub io_error_exit_code: Option<i32>,
}

/// FFI error type. The engine's own `ClientError` carries an rsync exit code
/// plus a formatted message, both of which are surfaced here. The field is
/// named `detail`, not `message`: UniFFI's Kotlin codegen adds its own
/// `override val message` (from `kotlin.Exception`) to every error variant,
/// and a same-named Rust field collides with it (`compileDebugKotlin`
/// fails with "Conflicting declarations: val message: String" -- caught
/// 2026-08-19 regenerating bindings for SYNC-005, since SYNC-002 never
/// actually rebuilt the Kotlin bindings after adding this error).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SyncError {
    #[error("oc-rsync engine error (exit {exit_code}): {detail}")]
    Engine { exit_code: i32, detail: String },
}

#[uniffi::export]
impl SyncEngine {
    /// Constructs the engine. No arguments (no config dir: this app embeds
    /// no daemon, unlike tetron-mobile's `Node::new`).
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            app_version: env!("CARGO_PKG_VERSION").to_string(),
        })
    }

    /// The crate's own version, mechanically derived at compile time.
    pub fn version(&self) -> String {
        self.app_version.clone()
    }

    /// Runs an oc-rsync client transfer (SYNC-002).
    ///
    /// `source` and `destination` are rsync operands: either local paths or
    /// daemon URLs (`rsync://host:port/module/dir/`); a push is
    /// local-source + daemon-URL-destination. `--partial` is forced on
    /// regardless of `options` (SYNC-002 semantics: an interrupted run must
    /// leave a resumable partial at the receiver). `progress`, when given,
    /// receives a `SyncProgressEvent` for every transferred file and the
    /// end-of-run summary. Blocks until the transfer completes.
    pub fn run_client(
        &self,
        source: String,
        destination: String,
        options: SyncRunOptions,
        progress: Option<Box<dyn SyncProgressListener>>,
    ) -> Result<SyncTransferOutcome, SyncError> {
        // Cancel button (TODO #8): both flags are process-global in the
        // vendored fork (they back real Ctrl+C handling for the CLI binary,
        // see vendor/oc-rsync/PATCHES.md), so a prior run's cancellation (or
        // a stray call to `cancel()` with nothing in flight) must not leak
        // into this one. `SyncPipeline`'s own reentrancy guard on the
        // Kotlin side already ensures only one `run_client` call is ever in
        // flight at a time, so resetting here -- rather than per-instance
        // state -- is correct for this app's single-transfer-at-a-time
        // contract. Both reset fns are named "for_testing" upstream (no
        // non-test reset previously existed, since the CLI binary just
        // exits after a signal instead of reusing the process); reusing
        // them here is deliberate, not a workaround.
        fast_io::signal::reset_shutdown_for_testing();
        oc_rsync_core::signal::reset_for_testing();

        let config = build_client_config(source, destination, options);
        let mut bridge = progress.as_ref().map(|b| ProgressBridge::new(b.as_ref()));
        let summary = run_client_with_observer(
            config,
            bridge.as_mut().map(|b| b as &mut dyn ClientProgressObserver),
        )
        .map_err(|err| SyncError::Engine {
            exit_code: err.exit_code(),
            detail: err.to_string(),
        })?;

        Ok(SyncTransferOutcome::from_summary(&summary))
    }

    /// Requests that an in-progress [`run_client`](Self::run_client) stop at
    /// the next file boundary (TODO #8, the Cancel button) -- exactly the
    /// same request a real Ctrl+C makes, reused rather than reinvented: sets
    /// the same two process-global flags the vendored fork's own SIGINT/
    /// SIGTERM/SIGHUP handlers set (`vendor/oc-rsync/crates/core/src/signal
    /// /unix.rs`'s `handle_sigint` etc.), which the generator/receiver
    /// transfer loops and the local-copy loop already poll on every call to
    /// `run_client_with_observer` regardless of transfer direction. The
    /// current file finishes (matching upstream rsync's own graceful-stop
    /// behavior) before the call returns `Err(SyncError::Engine{exit_code:
    /// 20, ..})`; a no-op call (nothing running) is harmless -- the next
    /// `run_client` call resets both flags before starting.
    pub fn cancel(&self) {
        fast_io::signal::mark_shutdown();
        oc_rsync_core::signal::request_shutdown(
            oc_rsync_core::signal::ShutdownReason::UserRequested,
        );
    }
}

/// Builds the per-run oc-rsync `ClientConfig` for a
/// [`SyncEngine::run_client`] call. Split out of `run_client` so the fixed
/// engine policy (SYNC-012 item 7) is unit-testable without a live transfer.
///
/// Fixed, not user-facing (SYNC-012 -- decision A1: no `SyncRunOptions`
/// field for any of these):
/// - `--partial` always on (SYNC-002 semantics).
/// - `--partial-dir .tetron-partial` -- an in-progress file never surfaces
///   to the gallery/MediaStore as a truncated thumbnail mid-transfer.
/// - `--timeout 120` / `--contimeout 30` -- a dead mesh path fails a
///   background (WorkManager) run instead of hanging it indefinitely.
///
/// `compress` and `checksum` stay the oc-rsync builder defaults (both
/// already `false`); photos/video are precompressed and `-c` would re-read
/// every byte on both ends.
fn build_client_config(
    source: String,
    destination: String,
    options: SyncRunOptions,
) -> ClientConfig {
    const IO_TIMEOUT_SECS: u64 = 120;
    const CONNECT_TIMEOUT_SECS: u64 = 30;

    let mut builder = ClientConfig::builder()
        .transfer_args([source, destination])
        .partial(true)
        .partial_directory(Some(".tetron-partial"))
        .timeout(TransferTimeout::Seconds(
            NonZeroU64::new(IO_TIMEOUT_SECS).expect("IO_TIMEOUT_SECS is nonzero"),
        ))
        .connect_timeout(TransferTimeout::Seconds(
            NonZeroU64::new(CONNECT_TIMEOUT_SECS).expect("CONNECT_TIMEOUT_SECS is nonzero"),
        ))
        .recursive(options.recursive)
        .times(options.times)
        .links(options.links)
        .mkpath(options.mkpath);

    if let Some(path) = options.files_from_path {
        builder = builder.files_from(FilesFromSource::LocalFile(std::path::PathBuf::from(path)));
    }
    if let Some(secs) = options.modify_window_secs {
        builder = builder.modify_window(Some(secs));
    }
    if let Some(kib) = options.bwlimit_kib {
        let bytes_per_second = NonZeroU64::new(kib.saturating_mul(1024))
            .expect("bwlimit*1024 cannot be zero when bwlimit is Some");
        builder = builder.bandwidth_limit(Some(BandwidthLimit::from_bytes_per_second(
            bytes_per_second,
        )));
    }

    builder.build()
}

/// Adapts the oc-rsync `ClientProgressObserver` (trait-object, mutable) to
/// the FFI callback interface (object-safe, shared). Both are synchronous.
struct ProgressBridge<'a> {
    listener: &'a dyn SyncProgressListener,
}

impl<'a> ProgressBridge<'a> {
    fn new(listener: &'a dyn SyncProgressListener) -> Self {
        Self { listener }
    }
}

impl ClientProgressObserver for ProgressBridge<'_> {
    fn on_progress(&mut self, update: &ClientProgressUpdate) {
        self.listener.on_progress(SyncProgressEvent::from_update(update));
    }
}

impl SyncProgressEvent {
    fn from_update(update: &ClientProgressUpdate) -> Self {
        let rel = update.event().relative_path().to_string_lossy();
        Self {
            path: (!rel.is_empty()).then(|| rel.into_owned()),
            total_bytes: update.total_bytes(),
            overall_transferred: update.overall_transferred(),
            overall_total_bytes: update.overall_total_bytes(),
            files_done: update.index() as u64,
            files_total: update.total() as u64,
            flist_eof: update.flist_eof(),
            transfer_complete: update.is_transfer_complete(),
            is_transfer: update.event().kind().is_transfer(),
            is_final: update.is_final(),
        }
    }
}

impl SyncTransferOutcome {
    fn from_summary(summary: &ClientSummary) -> Self {
        Self {
            files_copied: summary.files_copied(),
            files_total: summary.regular_files_total(),
            bytes_copied: summary.bytes_copied(),
            bytes_sent: summary.bytes_sent(),
            matched_bytes: summary.matched_bytes(),
            io_error_exit_code: summary.io_error_exit_code(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_version_is_crate_version() {
        assert_eq!(SyncEngine::new().version(), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn client_config_carries_fixed_engine_policy() {
        let cfg = build_client_config(
            "/src".to_string(),
            "rsync://host/module".to_string(),
            SyncRunOptions::default(),
        );
        assert_eq!(
            cfg.timeout().as_seconds().map(NonZeroU64::get),
            Some(120),
            "SYNC-012 item 7: fixed 120s I/O timeout"
        );
        assert_eq!(
            cfg.connect_timeout().as_seconds().map(NonZeroU64::get),
            Some(30),
            "SYNC-012 item 7: fixed 30s connect timeout"
        );
        assert_eq!(
            cfg.partial_directory(),
            Some(std::path::Path::new(".tetron-partial")),
            "SYNC-012 item 7: partial files land in .tetron-partial"
        );
    }

    #[test]
    fn client_config_maps_bwlimit_kib_to_bandwidth_limit() {
        let opts = SyncRunOptions {
            bwlimit_kib: Some(500),
            ..SyncRunOptions::default()
        };
        let cfg = build_client_config("/src".to_string(), "/dst".to_string(), opts);
        assert!(cfg.bandwidth_limit().is_some());
    }

    #[test]
    fn client_config_no_bwlimit_by_default() {
        let cfg = build_client_config(
            "/src".to_string(),
            "/dst".to_string(),
            SyncRunOptions::default(),
        );
        assert!(cfg.bandwidth_limit().is_none());
    }
}