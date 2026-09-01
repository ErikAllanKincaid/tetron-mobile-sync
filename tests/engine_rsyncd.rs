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
//!
//! Plus, per SYNC-010's cross-repo wire contract:
//! - `--mkpath` creates the `<module>/<device-label>/DCIM/Camera/` path on
//!   first push with no pre-seeding; without it the same push is refused;
//! - a `write only = true` module refuses a readback;
//! - a push from an address outside `hosts allow` is refused before any
//!   file is written;
//! - `max connections = 1` refuses a second connection while one is live.

use std::fs;
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use tetron_mobile_sync::{SyncEngine, SyncRunOptions};

struct Daemon {
    child: Child,
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

/// Knobs the SYNC-010 wire-contract tests need on the generated
/// `rsyncd.conf`. `Default` reproduces the historical single-module config
/// (`start_daemon`), so the SYNC-002 tests are unaffected.
struct DaemonConfig {
    hosts_allow: &'static str,
    read_only: bool,
    write_only: bool,
    max_connections: Option<u32>,
}

impl Default for DaemonConfig {
    fn default() -> Self {
        Self {
            hosts_allow: "127.0.0.1",
            read_only: false,
            write_only: false,
            max_connections: None,
        }
    }
}

fn start_daemon() -> Daemon {
    start_daemon_with(DaemonConfig::default())
}

fn start_daemon_with(cfg: DaemonConfig) -> Daemon {
    let root = tempfile::tempdir().unwrap().keep();
    let module = root.join("backup");
    fs::create_dir_all(&module).unwrap();
    let conf = root.join("rsyncd.conf");
    let mut module_block = format!(
        "[backup]\n\
         path = {}\n\
         read only = {}\n\
         write only = {}\n",
        module.display(),
        if cfg.read_only { "yes" } else { "no" },
        if cfg.write_only { "yes" } else { "no" },
    );
    if let Some(n) = cfg.max_connections {
        module_block.push_str(&format!("max connections = {n}\n"));
        // `max connections` needs a writable lock file; the rsyncd default
        // (/var/run/rsyncd.lock) is unwritable to a non-root daemon, which
        // would fail the connection outright rather than just cap it.
        module_block.push_str(&format!("lock file = {}\n", root.join("rsyncd.lock").display()));
    }
    fs::write(
        &conf,
        format!(
            "use chroot = no\n\
             hosts allow = {}\n\
             {}",
            cfg.hosts_allow, module_block,
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

/// Deterministic pseudo-random fill (splitmix64) for large fixture files.
///
/// A uniform-byte fixture (`vec![0xCDu8; N]`) makes every rolling checksum
/// window in the file identical, so the matcher's weak-checksum prefilter
/// never rejects a candidate and falls through to a strong-checksum compute
/// at nearly every byte offset -- fine in a release build but catastrophically
/// slow in the debug build `cargo test` runs, reading as an indefinite hang.
/// Real photos/video are never a single repeated byte either, so varied
/// content is the more representative fixture regardless.
fn pseudo_random_bytes(len: usize, seed: u64) -> Vec<u8> {
    let mut state = seed;
    let mut out = Vec::with_capacity(len);
    while out.len() < len {
        state = state.wrapping_add(0x9E3779B97F4A7C15);
        let mut z = state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
        z ^= z >> 31;
        out.extend_from_slice(&z.to_le_bytes());
    }
    out.truncate(len);
    out
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
    let full = pseudo_random_bytes(10 * 1024 * 1024, 0xC0FFEE);
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
    // 40 MiB at a 5 MiB/s ceiling leaves an ~6s kill window (threshold at
    // 8 MiB / 1.6s in). Kept well below the 400 MiB the test originally used:
    // the resume below re-matches this whole file against the receiver's
    // partial with the embedded matcher's debug-build (unoptimized) codegen,
    // and that cost scales with file size -- 400 MiB pushed it well past a
    // minute even on non-degenerate content, which read as a hang.
    let full = pseudo_random_bytes(40 * 1024 * 1024, 0xB16B00B5);
    fs::write(src.path().join("burst.mp4"), &full).unwrap();

    // NOTE: no --partial anywhere -- the engine must force it.
    let src_arg = format!("{}/", src.path().display());
    let url = daemon.url();

    let mut child = run_helper_in_child(&src_arg, &url, Some(5_000));

    // Wait until the receiver holds a growing partial, then kill mid-transfer.
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let sizes: Vec<u64> = fs::read_dir(&daemon.module)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.metadata().map(|m| m.len()).unwrap_or(0))
            .filter(|s| *s > 0)
            .collect();
        if sizes.iter().any(|s| *s > 8 * 1024 * 1024) {
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

// ---- SYNC-010 wire-contract tests --------------------------------------

fn mkpath_options() -> SyncRunOptions {
    SyncRunOptions {
        recursive: true,
        times: true,
        mkpath: true,
        ..Default::default()
    }
}

/// The push destination is `rsync://<ip>:<port>/<module>/<device-label>/...`;
/// `--mkpath` must create `<device-label>/DCIM/Camera/` under the module
/// root on the first run, with the receiver holding no pre-seeded per-device
/// directory. This is what lets one receiver module serve every device.
#[test]
#[ignore]
fn push_with_mkpath_creates_device_label_path() {
    let daemon = start_daemon();
    let src = tempfile::tempdir().unwrap();
    let body = vec![0x33u8; 200 * 1024];
    fs::write(src.path().join("IMG_9001.jpg"), &body).unwrap();
    let src_arg = format!("{}/", src.path().display());
    let dest = format!(
        "rsync://127.0.0.1:{}/backup/dev-abc123/DCIM/Camera/",
        daemon.port
    );

    let engine = SyncEngine::new();
    engine
        .run_client(src_arg, dest, mkpath_options(), None)
        .expect("push with --mkpath should succeed into a non-existent dest path");

    assert_eq!(
        bytes_at(&daemon.module.join("dev-abc123/DCIM/Camera/IMG_9001.jpg")),
        body,
        "the whole <device-label>/DCIM/Camera/ path must have been created by the client"
    );
}

/// The real app path: a `--files-from` list whose entries carry a
/// `DCIM/Camera/` prefix must land nested at the receiver
/// (`<module>/<device-label>/DCIM/Camera/<name>`), not collapsed to the
/// basename. rsync implies `--relative` for `--files-from`; the embedding
/// wrapper sets it explicitly since the fork's builder does not.
#[test]
#[ignore]
fn files_from_with_dir_prefix_lands_nested_at_receiver() {
    let daemon = start_daemon();
    let src = tempfile::tempdir().unwrap();
    let camera = src.path().join("DCIM").join("Camera");
    fs::create_dir_all(&camera).unwrap();
    fs::write(camera.join("IMG_7001.jpg"), vec![0x21u8; 128 * 1024]).unwrap();
    fs::write(camera.join("IMG_7002.jpg"), vec![0x22u8; 96 * 1024]).unwrap();

    let list = src.path().join("files_from.txt");
    fs::write(&list, "DCIM/Camera/IMG_7001.jpg\nDCIM/Camera/IMG_7002.jpg\n").unwrap();

    let engine = SyncEngine::new();
    engine
        .run_client(
            format!("{}/", src.path().display()),
            format!("rsync://127.0.0.1:{}/backup/phone-xyz/", daemon.port),
            SyncRunOptions {
                recursive: true,
                times: true,
                mkpath: true,
                files_from_path: Some(list.to_string_lossy().into_owned()),
                ..Default::default()
            },
            None,
        )
        .expect("files-from push should succeed");

    assert_eq!(
        bytes_at(&daemon.module.join("phone-xyz/DCIM/Camera/IMG_7001.jpg")),
        vec![0x21u8; 128 * 1024],
        "the DCIM/Camera/ prefix from the file list must be recreated at the receiver"
    );
    assert!(
        daemon.module.join("phone-xyz/DCIM/Camera/IMG_7002.jpg").exists(),
        "every listed entry lands under its prefix, not flattened"
    );
    assert!(
        !daemon.module.join("phone-xyz/IMG_7001.jpg").exists(),
        "entries must not collapse to the basename"
    );
}

/// Same push without `--mkpath` is refused by the daemon (the multi-level
/// destination path does not exist) -- proving `--mkpath` is load-bearing,
/// not incidental.
#[test]
#[ignore]
fn push_without_mkpath_into_missing_path_is_refused() {
    let daemon = start_daemon();
    let src = tempfile::tempdir().unwrap();
    fs::write(src.path().join("IMG_9002.jpg"), vec![0x44u8; 64 * 1024]).unwrap();
    let src_arg = format!("{}/", src.path().display());
    let dest = format!(
        "rsync://127.0.0.1:{}/backup/dev-nope/DCIM/Camera/",
        daemon.port
    );

    let engine = SyncEngine::new();
    let result = engine.run_client(
        src_arg,
        dest,
        SyncRunOptions {
            recursive: true,
            times: true,
            ..Default::default()
        },
        None,
    );
    assert!(
        result.is_err(),
        "a push into a missing multi-level dest path without --mkpath must fail"
    );
    assert!(
        !daemon.module.join("dev-nope").exists(),
        "nothing should have been created under the module root"
    );
}

/// The receiver module is `write only = true` (SYNC-010 / plan §1.3): the
/// phone pushes but must not be able to read anything already there. A pull
/// from the module is refused and writes no local file.
#[test]
#[ignore]
fn readback_from_write_only_module_is_refused() {
    let daemon = start_daemon_with(DaemonConfig {
        write_only: true,
        ..DaemonConfig::default()
    });
    fs::write(daemon.module.join("secret.jpg"), vec![0x55u8; 128 * 1024]).unwrap();

    let dst = tempfile::tempdir().unwrap();
    let engine = SyncEngine::new();
    let result = engine.run_client(
        daemon.url(),
        format!("{}/", dst.path().display()),
        default_options(),
        None,
    );
    assert!(
        result.is_err(),
        "a readback from a write-only module must be refused"
    );
    assert!(
        !dst.path().join("secret.jpg").exists(),
        "no file may be pulled from a write-only module"
    );
}

/// A push from an address outside `hosts allow` is refused before any file
/// is written (SYNC-010 decision #1: explicit per-identity allow-list,
/// deny-by-default).
#[test]
#[ignore]
fn push_from_unlisted_address_is_refused() {
    let daemon = start_daemon_with(DaemonConfig {
        hosts_allow: "10.255.255.1",
        ..DaemonConfig::default()
    });
    let src = tempfile::tempdir().unwrap();
    fs::write(src.path().join("IMG_9003.jpg"), vec![0x66u8; 96 * 1024]).unwrap();

    let engine = SyncEngine::new();
    let result = engine.run_client(
        format!("{}/", src.path().display()),
        daemon.url(),
        default_options(),
        None,
    );
    assert!(
        result.is_err(),
        "a push from a non-allow-listed address must be refused"
    );
    let entries: Vec<_> = fs::read_dir(&daemon.module).unwrap().collect();
    assert!(entries.is_empty(), "no file may be written for a refused peer");
}

/// `max connections = 1` refuses a second connection while one transfer is
/// still live (SYNC-010 / plan §1.3: stops overlapping runs from the same
/// phone).
#[test]
#[ignore]
fn second_connection_is_refused_while_one_is_live() {
    let daemon = start_daemon_with(DaemonConfig {
        max_connections: Some(1),
        ..DaemonConfig::default()
    });
    let src = tempfile::tempdir().unwrap();
    // Big + bandwidth-limited so the first connection stays open long enough
    // to overlap a second attempt.
    let full = pseudo_random_bytes(40 * 1024 * 1024, 0x5EC02D);
    fs::write(src.path().join("big.mp4"), &full).unwrap();
    let src_arg = format!("{}/", src.path().display());
    let url = daemon.url();

    let mut child = run_helper_in_child(&src_arg, &url, Some(3_000));

    // Wait until the receiver holds a growing partial (connection 1 is live).
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let bytes: u64 = fs::read_dir(&daemon.module)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.metadata().map(|m| m.len()).unwrap_or(0))
            .sum();
        if bytes > 1024 * 1024 {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "connection 1 never established before timeout"
        );
        std::thread::sleep(Duration::from_millis(20));
    }

    let other = tempfile::tempdir().unwrap();
    fs::write(other.path().join("other.jpg"), vec![0x77u8; 64 * 1024]).unwrap();
    let engine = SyncEngine::new();
    let result = engine.run_client(
        format!("{}/", other.path().display()),
        url,
        default_options(),
        None,
    );
    assert!(
        result.is_err(),
        "a second connection must be refused while one is live (max connections = 1)"
    );

    let _ = child.kill();
    let _ = child.wait();
}
