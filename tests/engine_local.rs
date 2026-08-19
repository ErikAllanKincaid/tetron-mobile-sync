//! SYNC-002 engine tests that need no network: local copies through the same
//! `SyncEngine::run_client` API the Android app calls over UniFFI.
//!
//! The wire-level tests (against a real stock `rsync --daemon`, including the
//! SIGKILL interrupt/resume path) live in `engine_rsyncd.rs` and are
//! `#[ignore]`-gated on rsync being installed.

use std::fs;
use std::path::Path;
use std::sync::Mutex;

use tetron_mobile_sync::{SyncEngine, SyncProgressEvent, SyncProgressListener, SyncRunOptions};

fn src_dir() -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join("alpha.txt"), b"hello photo backup alpha\n").unwrap();
    fs::write(dir.path().join("beta.bin"), vec![0x42u8; 64 * 1024]).unwrap();
    fs::create_dir(dir.path().join("nested")).unwrap();
    fs::write(dir.path().join("nested/gamma.jpg"), b"fake jpeg payload").unwrap();
    dir
}

fn read_all(path: &Path) -> Vec<u8> {
    fs::read(path).unwrap()
}

/// Recursive listing: relative path -> bytes for every file.
fn snapshot(root: &Path) -> Vec<(String, Vec<u8>)> {
    let mut out = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                stack.push(path);
            } else {
                let rel = path
                    .strip_prefix(root)
                    .unwrap()
                    .to_string_lossy()
                    .into_owned();
                out.push((rel, read_all(&path)));
            }
        }
    }
    out.sort();
    out
}

fn src_arg(dir: &Path) -> String {
    format!("{}/", dir.display())
}

fn run(engine: &SyncEngine, src: &Path, dst: &Path) -> tetron_mobile_sync::SyncTransferOutcome {
    engine
        .run_client(
            src_arg(src),
            src_arg(dst),
            SyncRunOptions {
                recursive: true,
                times: true,
                ..Default::default()
            },
            None,
        )
        .expect("transfer should succeed")
}

#[test]
fn local_copy_roundtrip_copies_files_byte_identical() {
    let src = src_dir();
    let dst = tempfile::tempdir().unwrap();
    let engine = SyncEngine::new();

    let outcome = run(&engine, src.path(), dst.path());

    assert_eq!(outcome.files_copied, 3, "three regular files copied");
    assert_eq!(outcome.files_total, 3);
    assert!(outcome.bytes_copied > 0);
    assert_eq!(outcome.io_error_exit_code, None);
    assert_eq!(
        snapshot(src.path()),
        snapshot(dst.path()),
        "destination must be byte-identical to source"
    );
}

#[test]
fn second_run_is_a_noop() {
    let src = src_dir();
    let dst = tempfile::tempdir().unwrap();
    let engine = SyncEngine::new();

    let first = run(&engine, src.path(), dst.path());
    assert_eq!(first.files_copied, 3);

    let second = run(&engine, src.path(), dst.path());
    assert_eq!(second.files_copied, 0, "no changes -> nothing transferred");
    assert_eq!(second.bytes_copied, 0);
}

#[test]
fn existing_destination_content_is_reused() {
    // A destination file that already holds the source's first chunk (the
    // "interrupted earlier" shape): the engine must still end byte-identical.
    // (Basis-file delta matching itself is a receiver-side behavior, covered
    // against the real rsyncd in engine_rsyncd.rs; the local executor copies
    // whole files by design.)
    let src = tempfile::tempdir().unwrap();
    let full = vec![0xABu8; 1024 * 1024];
    fs::write(src.path().join("photo.jpg"), &full).unwrap();

    let dst = tempfile::tempdir().unwrap();
    fs::write(dst.path().join("photo.jpg"), &full[..256 * 1024]).unwrap();

    let engine = SyncEngine::new();
    let outcome = run(&engine, src.path(), dst.path());

    assert_eq!(outcome.files_copied, 1);
    assert_eq!(read_all(&dst.path().join("photo.jpg")), full);
}

/// Kotlin implements `SyncProgressListener` over UniFFI; the test crate
/// implements the same Rust trait the FFI layer wraps, exercising the exact
/// bridge (observer -> event record -> callback) end to end. The event log
/// lives behind an Arc<Mutex> so the test can inspect it after the Box is
/// consumed by the (synchronous) run.
struct RecordingListener {
    events: std::sync::Arc<Mutex<Vec<SyncProgressEvent>>>,
}

impl SyncProgressListener for RecordingListener {
    fn on_progress(&self, event: SyncProgressEvent) {
        self.events.lock().unwrap().push(event);
    }
}

#[test]
fn progress_listener_observes_transfers() {
    let src = src_dir();
    let dst = tempfile::tempdir().unwrap();
    let engine = SyncEngine::new();

    let events = std::sync::Arc::new(Mutex::new(Vec::new()));
    let listener = Box::new(RecordingListener {
        events: events.clone(),
    });
    let outcome = engine
        .run_client(
            src_arg(src.path()),
            src_arg(dst.path()),
            SyncRunOptions {
                recursive: true,
                times: true,
                ..Default::default()
            },
            Some(listener),
        )
        .expect("transfer with listener should succeed");

    let events_log = events.lock().unwrap();
    assert!(!events_log.is_empty(), "listener must have been called");
    let last = events_log.last().unwrap();
    // The progress denominator counts every checked flist entry (directories
    // and symlinks included, mirroring upstream to-chk=<n>/<total>), so it is
    // at least the number of regular files.
    assert!(last.files_total >= 3);
    assert_eq!(last.files_done, outcome.files_copied);
    assert!(
        events_log.iter().all(|e| e.flist_eof),
        "local copies emit a complete flist"
    );
    assert!(
        events_log
            .iter()
            .any(|e| e.path.as_deref() == Some("alpha.txt")),
        "per-file events carry the relative path"
    );
}
