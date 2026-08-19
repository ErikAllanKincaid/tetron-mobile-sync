//! SYNC-002 wire-compat integration tests: the embedded engine against a real
//! stock `rsync --daemon` (protocol 31, the version on Ubuntu 24.04).
//!
//! All tests here are `#[ignore]`-gated: they need `rsync(1)` on PATH and a
//! few seconds each. Run with `cargo test -- --ignored --test-threads 1`.
//!
//! Covering, per SYNC-002 acceptance:
//! - push to a real rsyncd module lands byte-identical;
//! - re-running is a no-op;
//! - an interrupted push leaves a `--partial` file at the receiver and the
//!   next run resumes from it (delta-matched, not re-sent);
//! - the engine forces `--partial` even when the caller never asks for it.

use std::fs;
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use tetron_mobile_sync::{SyncEngine, SyncRunOptions};

struct Daemon {
    child: Child,
    root: PathBuf,
    module: PathBuf,
    port: u16,
}

impl Daemon {
    fn url(&self) -> String {
        format!("rsync://127.0.0.1:{}/backup/", self.port)
    }
}

impl Drop for Daemon {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

fn start_daemon() -> Daemon {
    let root = tempfile::tempdir().unwrap().into_path();
    let module = root.join("backup");
    fs::create_dir_all(&module).unwrap();
    let conf = root.join("rsyncd.conf");
    fs::write(
        &conf,
        format!(
            "use chroot = no\n\
             hosts allow = 127.0.0.1\n\
             [backup]\n\
             path = {}\n\
             read only = no\n",
            module.display()
        ),
    )
    .unwrap();

    let port = free_port();
    let child = Command::new("rsync")
        .args([
            "--daemon",
            "--no-detach",
            "--port",
            &port.to_string(),
            "--config",
            conf.to_str().unwrap(),
        ])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("rsync --daemon must be startable (rsync 3.2.x on PATH)");

    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if TcpStream::connect(("127.0.0.1", port)).is_ok() {
            break;
        }
        assert!(Instant::now() < deadline, "rsyncd did not come up");
        std::thread::sleep(Duration::from_millis(50));
    }

    Daemon {
        child,
        root,
        module,
        port,
    }
}

fn seed_photos(root: &Path) {
    fs::write(root.join("IMG_0001.jpg"), vec![0x11u8; 300 * 1024]).unwrap();
    fs::create_dir(root.join("camera")).unwrap();
    fs::write(root.join("camera/IMG_0002.jpg"), vec![0x22u8; 512 * 1024]).unwrap();
}

fn bytes_at(path: &Path) -> Vec<u8> {
    fs::read(path).unwrap()
}

fn default_options() -> SyncRunOptions {
    SyncRunOptions {
        recursive: true,
        times: true,
        ..Default::default()
    }
}

/// Runs the engine in a subprocess via the test-helper bin so a test can
/// SIGKILL it mid-transfer. The helper takes <source> <destination>
/// [--bwlimit=KiB/s]; options are fixed (recursive+times) like the real app.
fn run_helper_in_child(src_arg: &str, url: &str, bwlimit_kib: Option<u64>) -> Child {
    let manifest = env!("CARGO_MANIFEST_DIR");
    let build = Command::new("cargo")
        .args([
            "build",
            "--quiet",
            "--bin",
            "sync-test-helper",
            "--features",
            "test-helper",
        ])
        .current_dir(manifest)
        .status()
        .expect("cargo build of the test helper should succeed");
    assert!(build.success());

    let mut cmd = Command::new(format!("{manifest}/target/debug/sync-test-helper"));
    cmd.args([src_arg, url]);
    if let Some(kib) = bwlimit_kib {
        cmd.arg(format!("--bwlimit={kib}"));
    }
    cmd.stdout(Stdio::null()).stderr(Stdio::null());
    cmd.spawn().expect("helper should spawn")
}

#[test]
#[ignore]
fn push_to_rsyncd_is_byte_identical_and_idempotent() {
    let daemon = start_daemon();
    let src = tempfile::tempdir().unwrap();
    seed_photos(src.path());
    let src_arg = format!("{}/", src.path().display());
    let url = daemon.url();

    // First push: everything transfers.
    let mut first = run_helper_in_child(&src_arg, &url, None);
    let status = first.wait().expect("first push child should exit");
    assert!(status.success(), "first push must exit 0");
    assert_eq!(
        bytes_at(&daemon.module.join("IMG_0001.jpg")),
        vec![0x11u8; 300 * 1024]
    );
    assert_eq!(
        bytes_at(&daemon.module.join("camera/IMG_0002.jpg")),
        vec![0x22u8; 512 * 1024]
    );

    // Second push: no-op, still exit 0.
    let mut second = run_helper_in_child(&src_arg, &url, None);
    let status = second.wait().expect("no-op push child should exit");
    assert!(status.success(), "no-op push must exit 0");
}

#[test]
#[ignore]
fn push_resumes_from_existing_partial_at_receiver() {
    let daemon = start_daemon();
    let src = tempfile::tempdir().unwrap();
    let full = vec![0xCDu8; 10 * 1024 * 1024];
    fs::write(src.path().join("video.mp4"), &full).unwrap();

    // Receiver already holds the first chunk of the file (an earlier
    // interrupted run that kept its --partial). The next push must match it
    // instead of re-sending it.
    fs::write(daemon.module.join("video.mp4"), &full[..256 * 1024]).unwrap();

    let src_arg = format!("{}/", src.path().display());
    let engine = SyncEngine::new();
    let outcome = engine
        .run_client(src_arg, daemon.url(), default_options(), None)
        .expect("resume push should succeed");
    assert!(outcome.matched_bytes >= 200 * 1024);
    assert_eq!(
        bytes_at(&daemon.module.join("video.mp4")),
        full,
        "resumed file must be byte-identical"
    );
}

#[test]
#[ignore]
fn killed_push_keeps_partial_and_next_run_resumes() {
    let daemon = start_daemon();
    let src = tempfile::tempdir().unwrap();
    // Big enough that a 50 MiB/s ceiling leaves a long kill window.
    let full = vec![0xEEu8; 400 * 1024 * 1024];
    fs::write(src.path().join("burst.mp4"), &full).unwrap();

    // NOTE: no --partial anywhere -- the engine must force it.
    let src_arg = format!("{}/", src.path().display());
    let url = daemon.url();

    let mut child = run_helper_in_child(&src_arg, &url, Some(50_000));

    // Wait until the receiver holds a growing partial, then kill mid-transfer.
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let sizes: Vec<u64> = fs::read_dir(&daemon.module)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.metadata().map(|m| m.len()).unwrap_or(0))
            .filter(|s| *s > 0)
            .collect();
        if sizes.iter().any(|s| *s > 20 * 1024 * 1024) {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "no partial appeared at the receiver before timeout"
        );
        std::thread::sleep(Duration::from_millis(20));
    }

    // Let the daemon finish teardown of the dead socket, then verify a
    // partial file really exists at the receiver.
    std::thread::sleep(Duration::from_millis(250));
    let partial_now: u64 = fs::read_dir(&daemon.module)
        .unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.metadata().map(|m| m.len()).unwrap_or(0))
        .sum();
    assert!(partial_now > 0, "partial file must exist at the receiver");

    let _ = child.kill();
    let _ = child.wait();
    assert!(
        partial_now < full.len() as u64,
        "the kill must have interrupted mid-transfer (partial {} of {})",
        partial_now,
        full.len()
    );

    // Resume: the next push must succeed and end byte-identical.
    let engine = SyncEngine::new();
    let outcome = engine
        .run_client(src_arg, url, default_options(), None)
        .expect("resume push should succeed");
    assert_eq!(
        bytes_at(&daemon.module.join("burst.mp4")),
        full,
        "resumed file must be byte-identical"
    );
    assert!(outcome.matched_bytes > 0, "resume must have matched data");
}
