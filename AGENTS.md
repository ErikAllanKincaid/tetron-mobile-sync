# tetron-mobile-sync Agent Guide

> Canonical guidance for any AI coding agent in this repo. `AGENTS.md` is the source; `CLAUDE.md` is a symlink to it.
>
> Reference-guide style on purpose: what you need before touching code, plus commands. Implementation status, per-requirement detail, the decision register, and bug history live in `spec/sync.py` docstrings. Do not restate them here; stale narrative makes this file worse.

## What this is

GPL-3.0 Android app: one-way phone-to-home camera-roll backup over the tetron mesh. Embeds a vendored, patched oc-rsync fork in-process (UniFFI); talks to a stock `rsync --daemon` at home. Design record: `DO-NOT-COMMIT/PLAN_tetron-mobile-sync_2026-08-13.md` (consensus 2026-08-18). `spec/sync.py` governs the work; the build is scoped SYNC-001..SYNC-012.

## Before you touch code

- **Boundaries.** No tetron core code and no tetron-mobile code belongs here. This app consumes tetron-mobile only through its MOBILE-024 ContentProvider (`xyz.tetron.mobile.status`, `call("get_status")`); it consumes tetron core only transitively, via the embedded oc-rsync fork. The home side is a stock `rsync --daemon` with zero receiver code (SYNC-010 is config + docs + an optional install script).
- **License.** This repo is GPL-3.0 because it embeds oc-rsync (GPL-3.0). tetron-mobile stays proprietary: the bridge is an IPC contract between separately-licensed programs, so code must never be copied either way. Write this repo's Kotlin fresh; do not copy rayfish (MPL) or tetron-mobile (proprietary) source, reference only.
- **Bridge contract.** Any change to the MOBILE-024 authority or `get_status` shape must keep tetron-mobile's `MeshStatusProviderContractTest` green. The Kotlin parcel-layout mirrors under `xyz/tetron/mobile/` are that wire contract; edit them only in lockstep with a provider-side change.
- **Android API 29 Scoped Storage is load-bearing.** `AndroidMediaAccess` enumerates the camera roll via `MediaStore` (staged as an oc-rsync `--files-from` list), never a raw directory walk, because Scoped Storage filters `readdir` per app UID. `android:requestLegacyExternalStorage="true"` on `<application>` is also required for raw file *reads* on API 29 (no-op on API 30+). `minSdk 26`, `targetSdk`/`compileSdk 35`.
- **Generated artifacts are not tracked.** `android/app/src/main/java/uniffi/tetron_mobile_sync/tetron_mobile_sync.kt` and `android/app/src/main/jniLibs/**` are git-ignored build outputs. Regenerate them (see Build) whenever `src/lib.rs`'s UniFFI surface changes, before trusting any Gradle build or `:app:testDebugUnitTest`.
- **`reconcile.py` allow-set.** `check_cargo_audit` filters `ACCEPTED_ADVISORIES` (currently RUSTSEC-2023-0071, rsa via russh). Adding one means updating that set and its rationale in `spec/sync.py` together.

## Workflow

1. Edit the spec: requirements/constraints in `spec/sync.py` (or a new domain module registered in `spec/main_spec.py`'s `modules()`).
2. `uv run libspec diff` (mandatory before writing code).
3. TDD, implement one requirement at a time.
4. `python3 reconcile.py` green.
5. Commit. All work on a branch; merge to `main` via a GitHub PR. Conventional commit subjects. **No authorship trailers of any kind.** Keep `README.md` a plain user guide, never a status tracker.

## Build

Rust crate is at the repo root (not nested).

```
cargo -q check                # host build
cargo clippy --all-targets    # reconcile.py runs -D warnings
uv run libspec diff           # spec tree vs HEAD

# Android native libs (regen on any src/lib.rs UniFFI change):
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build
#   add --release for a device-representative build

# UniFFI Kotlin bindings (regen on any src/lib.rs UniFFI change):
cargo build --features uniffi/cli
cargo run --bin uniffi-bindgen --features uniffi/cli -- generate \
  --library target/debug/libtetron_mobile_sync.so --language kotlin \
  --out-dir android/app/src/main/java

# Android app:
android/gradlew -p android :app:assembleDebug
```

Rust edition 2024, `rust-version` 1.91.

## Test

```
cargo test                                # Rust unit + tests/engine_local.rs
cargo test --test engine_rsyncd -- --ignored --test-threads 1
                                          # wire-compat vs a real rsyncd; needs `rsync` on PATH; ~80s
android/gradlew -p android :app:testDebugUnitTest            # JVM unit (no device)
android/gradlew -p android :app:compileDebugAndroidTestKotlin
android/gradlew -p android :app:connectedDebugAndroidTest    # instrumented; needs a device/emulator
python3 reconcile.py                       # the gate: libspec diff + cargo build/clippy/test/audit
```

- `engine_rsyncd` cases are `#[ignore]`-gated so machines without `rsync` are not broken by `cargo test`.
- Kotlin seams (`StatusCaller`, `TransferRunner`, `TargetProvider`, `SourcePathProvider`, `DeviceStateProvider`, `RunHistoryStore`, `DeletionRequester`) are fakeable, so JVM unit tests never load the native `.so`. Thin `SharedPreferences` adapters carry a pure codec instead of a Robolectric test.
- Full-pipeline byte-identical / resume / idempotent behavior is covered by `tests/engine_rsyncd.rs`, not re-tested per Kotlin layer.
- On-device verification is SYNC-011, manual, recorded in an untracked `DO-NOT-COMMIT/` folder.

## Code style

- Match core: `cargo fmt`, clippy clean at `-D warnings`, minimal dependencies, Unix philosophy.
- Every source file carries its `SPDX-License-Identifier: GPL-3.0-only` header.
- Kotlin: `xyz.tetron.sync.*` for app code; the `xyz.tetron.mobile.*` mirrors are the only exception and exist purely as the MOBILE-024 wire contract.
- Reference code as `path:line`.

## Repo layout

```
Cargo.toml, src/lib.rs, uniffi-bindgen.rs   sync-app Rust crate (repo root)
vendor/oc-rsync/                            patched oc-rsync fork, applied + tracked
android/                                    Gradle / Kotlin / Compose app
spec/, reconcile.py, pyproject.toml         spec-first workflow (own tree, not core's)
DO-NOT-COMMIT/                              working docs, git-ignored
LICENSE                                     GPL-3.0
```

## Test devices

One real Android phone (API 29 reference; a real camera roll) plus a headless Linux box running a stock `rsync --daemon` receiver, on a shared tetron mesh. A second Android phone (API level unconfirmed) has been used for release smoke tests. Exact roster and connection details: tetron-mobile's `DO-NOT-COMMIT/TEST_PROCEDURE.md`. The delete-after-backup consent flow (`MediaStoreDeletionRequester`, API 33+) and the cellular Direct-or-deferred gate need an API-33+ device with an active SIM.
