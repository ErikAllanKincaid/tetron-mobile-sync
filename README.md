# tetron-mobile-sync

GPL-3.0 Android photo-backup addon for the [tetron](https://github.com/ErikAllanKincaid/tetron) mesh: one-way phone-to-home photo/video backup over the mesh, using an embedded oc-rsync client against a stock `rsync --daemon` on the home machine.

Working name only. The app/product name is not decided; `tetron-mobile-sync` is the repo and crate name until then.

See `AGENTS.md` for the spec-first development workflow (same as core tetron and tetron-mobile). The full product plan and decision history live in `DO-NOT-COMMIT/PLAN_tetron-mobile-sync_2026-08-13.md` (consensus reached 2026-08-18); `spec/sync.py` is the buildable conclusion of it.

## Status

SYNC-001 done: crate + Gradle/Compose app scaffolded (host checks, Android cross-compile via cargo-ndk, UniFFI Kotlin bindings, debug APK with both ABIs, JVM unit + instrumented smoke tests).
SYNC-002 done: the embedded oc-rsync engine (vendored patched fork) runs host-side against a real `rsync --daemon` byte-identically, and all three wire-compat/resume tests in `tests/engine_rsyncd.rs` pass, including `--partial` resume from both a receiver-pre-seeded partial and a kill-mid-transfer partial. The build is scoped as SYNC-001..SYNC-011 in `spec/sync.py`; SYNC-003 is next.

## How it works (the one-paragraph version)

Two apps share a tetron mesh network. This app reads mesh state (own mesh IP, tunnel state, peer roster with per-peer connection kind) from tetron-mobile's MOBILE-024 status bridge, gates runs on Wi-Fi / direct-connection / battery conditions, and rsyncs DCIM to the home machine's `rsync --daemon` (target picked from the roster, `hosts allow` keyed on the phone's mesh IP). v1 triggers are opportunistic: manual button + periodic WorkManager job + network-change callback. Delete-after-backup is an explicit opt-in that only deletes files a run actually transferred.

## Repo layout

```
Cargo.toml, src/lib.rs, uniffi-bindgen.rs   -- the sync-app Rust crate (repo root)
vendor/oc-rsync/                            -- patched oc-rsync fork, applied + tracked (SYNC-002)
android/                                    -- Gradle/Kotlin/Compose app (SYNC-001)
spec/, reconcile.py, pyproject.toml         -- spec-first workflow, own from core's
DO-NOT-COMMIT/                              -- working docs, gitignored
LICENSE                                     -- GPL-3.0 (route (a): oc-rsync embedded in-process)
```