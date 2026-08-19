//! SYNC-002 dev-only CLI: runs the embedded engine with the exact same
//! `SyncEngine::run_client` path the Android app uses over UniFFI, so the
//! `#[ignore]`-gated rsyncd integration test can SIGKILL a real transfer and
//! verify `--partial` interrupt/resume against a stock daemon.
//!
//! Gated behind the `test-helper` feature (required-features on the bin), so
//! it is never part of the APK build.
//!
//! Usage: sync-test-helper <source> <destination> [--bwlimit=KiB/s]
//!   e.g. sync-test-helper src/ rsync://127.0.0.1:873/backup/
//! Exit codes: 0 success, 2 bad usage, 3 engine error, or the engine's
//! io_error_exit_code (rsync 23 = partial transfer) when the summary reports
//! one.

use tetron_mobile_sync::{SyncEngine, SyncRunOptions};

fn main() {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    if argv.len() < 2 {
        eprintln!("usage: sync-test-helper <source> <destination> [--bwlimit=KiB/s]");
        std::process::exit(2);
    }

    let source = argv[0].clone();
    let destination = argv[1].clone();
    let mut bwlimit_kib = None;
    for arg in &argv[2..] {
        if let Some(v) = arg.strip_prefix("--bwlimit=") {
            bwlimit_kib = v.parse::<u64>().ok();
        } else {
            eprintln!("unknown helper arg: {arg}");
            std::process::exit(2);
        }
    }

    let engine = SyncEngine::new();
    let outcome = engine
        .run_client(
            source,
            destination,
            SyncRunOptions {
                recursive: true,
                times: true,
                bwlimit_kib,
                ..Default::default()
            },
            None,
        )
        .unwrap_or_else(|err| {
            eprintln!("engine error: {err}");
            std::process::exit(3);
        });

    if let Some(code) = outcome.io_error_exit_code {
        std::process::exit(code);
    }
}
