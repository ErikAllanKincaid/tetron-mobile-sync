# tetron-mobile-sync — Agent Guide

> **This file is the canonical guidance for any AI coding agent working in this repository.** `AGENTS.md` is the source of truth; `CLAUDE.md` is a symlink to it.

> **THIS REPOSITORY IS `tetron-mobile-sync`**, the GPL-3.0 Android photo-backup addon for the tetron mesh. Design record: `DO-NOT-COMMIT/PLAN_tetron-mobile-sync_2026-08-13.md` (consensus reached 2026-08-18). `spec/sync.py` governs the actual work, same spec-first workflow as core tetron and tetron-mobile.

All work must be in a branch. Merge to `main` via a GitHub PR when a remote exists (same convention as core and tetron-mobile); until then, keep feature branches locally and merge review-first the same way. Conventional commit subjects, no authorship trailers of any kind. Any changes to tetron core require testing in the testsuite to verify no regression (this repo makes no core changes by design -- see Roadmap). Any change to the tetron-mobile bridge contract (authority/`get_status` shape) requires the `MeshStatusProviderContractTest` on the tetron-mobile side to still pass.

## Why this is a separate repo

tetron adds one thing: a mesh VPN. This repo is a product built on top of it: a phone-to-home photo backup. Core "does one thing well" and carries nothing product-specific; the desktop addons (`tetron-webui`, `tetron-systray`) already follow that convention over IPC; tetron-mobile consumes core via embedding (UniFFI); this app consumes *tetron-mobile* via a ContentProvider contract (MOBILE-024) and a stock `rsync --daemon` at home. No tetron core code, and no tetron-mobile code, belongs here.

**License mechanics:** this repo ships GPL-3.0 (see `LICENSE`), because it embeds oc-rsync (GPL-3.0) in-process via a vendored patched fork -- USER's route (a) decision (plan §Transfer mechanism, decision #16); the subprocess route (b) and mechanism switch (c) are closed. tetron-mobile stays proprietary: the bridge is an IPC contract between two separately-licensed programs, and this repo's code must never be copied into it (and vice versa -- MOBILE-024's `BridgeGrants`/`GrantActivity`/provider stay theirs). The sync app does NOT embed tetron core; it has no daemon of its own. The home side is a stock `rsync --daemon` -- no receiver code, ever (SYNC-010 is config + docs + an optional install script only).

## Roadmap / spec

Status: SYNC-001 done (2026-08-18, `feat/sync-001-repo-scaffold`): crate + Gradle/Compose app scaffolded, host + Android cross-compile + UniFFI Kotlin bindings + debug APK all building, reconcile.py carries the cargo checks. Merged to `main` via merge commit `106790c` (2026-08-19); remote `origin` is configured on GitHub (ErikAllanKincaid/tetron-mobile-sync), so future merges go through PRs per the convention above.

SYNC-002 (embedded oc-rsync engine) is **ACCEPTED as of 2026-08-19**.
`vendor/oc-rsync/` (patched fork) is vendored and tracked; `src/lib.rs`
implements `SyncEngine::run_client` against `oc-rsync-core`'s
`run_client_with_observer`, with `SyncProgressListener` wired as a UniFFI
callback interface; `src/bin/sync-test-helper.rs` (dev-only,
`test-helper`-feature-gated) and `tests/engine_local.rs` +
`tests/engine_rsyncd.rs` (the latter `#[ignore]`-gated on `rsync` being on
PATH) exist. `cargo -q check` clean, `cargo test` (5 unconditional: 1 lib
unit test + 4 in `engine_local.rs`) passes, and all three
`#[ignore]`-gated `tests/engine_rsyncd.rs` cases now pass:
`cargo test --test engine_rsyncd -- --ignored --test-threads 1` is
ok in ~82s.

**Resolved 2026-08-19: the "hang" in
`push_resumes_from_existing_partial_at_receiver` was misdiagnosed as a
deadlock in a prior session -- it was a pathological test fixture, not a
bug in `run_client`.** The 10 MiB fixture was `vec![0xCDu8; N]`, every
byte identical, which makes every rolling-checksum window in the file
collide. The matcher's cheap weak-checksum prefilter
(`tag_table`/`bithash` in `vendor/oc-rsync/crates/matching/src/index/mod.rs`)
never gets to reject a candidate, so `find_match_slices_filtered` falls
through to an expensive strong-checksum compute (`xxhash_rust::xxh3`) at
nearly every byte offset. That is a few seconds of real work in
`--release`, but the unoptimized `cargo test` debug profile stretched it
past what a human waits out before calling it a hang. Confirmed with
`sudo gdb -p <pid> -batch -ex "thread apply all bt"` (this host has
`yama/ptrace_scope=1`, so attaching needs `sudo`; passwordless sudo is
configured here): the test thread was actively running inside
`DeltaGenerator::generate_with_prune`, not asleep on any lock, and a
`--release` build of the same test completed in 7.6s. **Fix:** replaced
the uniform-byte fixtures in `tests/engine_rsyncd.rs` with a small
deterministic splitmix64 PRNG helper (`pseudo_random_bytes`) so
rolling-checksum windows actually vary -- also a more representative
fixture, since real photos/video are never a single repeated byte.
`killed_push_keeps_partial_and_next_run_resumes` had genuinely never run
before (the suite hung on the test before it in every prior session); with
the fixture fixed it does run, but its original 400 MiB size still pushed
the resume-side match past a minute in an unoptimized build, so it is now
40 MiB with the bwlimit and kill-detection threshold scaled down to match
(still leaves a multi-second kill window, not flaky). Processes from a
killed run must be swept with **name-based** `pkill -9 -x rsync` /
`pkill -9 -x sync-test-helper` only -- `pkill -f` self-matches the invoking
shell's own command line (it contains these same names as plain text) and
kills the session instead of the target; this cost real time in this
session before being caught. See `spec/sync.py`'s SYNC-002 docstring for
the full writeup.

**Bug found + fixed 2026-08-19 (committed in `301b0a3`):**
`reconcile.py`'s `check_cargo_audit` required `count == 0` unconditionally,
so the moment SYNC-002 landed real code, `python3 reconcile.py` would fail
outright on the one advisory (RUSTSEC-2023-0071, rsa via russh) that
spec/sync.py's own SYNC-002 ACCEPTANCE text explicitly says is expected and
accepted -- a gate permanently red for a state the spec calls passing. Fixed
by adding an `ACCEPTED_ADVISORIES` allow-set the check filters against
before counting; re-run confirms `cargo_audit: {"installed": true, "count":
0}` now. If you add a new accepted advisory, update spec/sync.py's own
rationale for it AND this set together, same bar as the first one.

SYNC-002 is now fully closed out. Nothing past SYNC-002 has started -- the
next requirement to pick up is SYNC-003 (or SYNC-008/SYNC-010, which have
no hard dependency on it). The build is scoped as SYNC-001..SYNC-011 in
`spec/sync.py`, which also records the decision register (consensus
2026-08-18) and the still-open items. Dependency ordering (also stated
per-class in `spec/sync.py`):

- SYNC-001 repo scaffold (crate + Gradle/Compose app, GPL-3.0, mirror of tetron-mobile's proven pipeline) — no deps, first.
- SYNC-002 embedded oc-rsync engine (vendored patched fork `vendor/oc-rsync/`, embed `crates/core` with `default-features = false, features = ["zstd", "lz4", "xattr"]` -- not the spike's root-bin string, see `vendor/oc-rsync/PATCHES.md` "Embedded-build note", `--partial` resume, `TransferProgressCallback` through UniFFI) — after SYNC-001.
- SYNC-003 mesh bridge client (MOBILE-024 ContentProvider consumer; roster, tunnel state, ConnKind; consent-banner handling) — after SYNC-001; parallel with SYNC-002.
- SYNC-004 gate evaluation (Wi-Fi default ON, direct-only second-stage default ON via per-target ConnKind — never a network-type heuristic, cellular+Direct exists, low-battery pause default ON ~20%, charging default OFF; gated = skip + coalesced notify) — after SYNC-003.
- SYNC-005 transfer pipeline (single run path: gates → target → engine → progress → history → notification; `--partial` resume; reentrancy) — after SYNC-002 + SYNC-004.
- SYNC-006 trigger model v1 (manual button + WorkManager + network-change callback; FGS v2 explicitly deferred, don't foreclose it) — after SYNC-005.
- SYNC-007 delete-after-backup (opt-in default OFF; only transferred-this-run files; MediaStore.createDeleteRequest API 33+) — after SYNC-005.
- SYNC-008 media access (READ_MEDIA_IMAGES/VIDEO runtime, partial-access warning, DCIM/Camera path) — parallel; consumed by SYNC-005/009.
- SYNC-009 Compose UI (home/progress/history/settings, roster target picker, consent banner, own-IP copy button) — after SYNC-003/005/007/008.
- SYNC-010 home-side deliverable (sample rsyncd.conf, dedicated user, `hosts allow` on mesh IP, README setup, optional contrib/install script) — parallel, anytime.
- SYNC-011 final device verification (LG V40: real Wi-Fi + camera roll, delete consent flow, cellular Direct-or-deferred-never-relay, interrupt/resume) — after the app works.

## Spec-first workflow (libspec + reconcile.py)

Same loop as core and tetron-mobile:

- **Edit Spec:** requirements/constraints in `spec/sync.py` (or a new domain module, added to `spec/main_spec.py`'s `modules()`).
- **Diff Spec (mandatory before coding):** `uv run libspec diff`.
- **Test Driven Development / Implement / reconcile.py green / commit.** Conventional commit subjects, no authorship trailers, same as core.

`reconcile.py` carries the spec-tree check (`libspec diff` clean against HEAD) plus the standard cargo checks (build/clippy/test/cargo-audit, added with SYNC-001).

## Conventions

Same as core where applicable: `cargo -q check`/`clippy`/`test`, no authorship trailers in commits, spec-driven one-requirement-at-a-time changes, keep `AGENTS.md` current after any significant change. Keep `README.md`'s status line honest. Wire-compat tests against a real rsyncd are `#[ignore]`-gated so machines without rsync are not broken by `cargo test`. Do not copy upstream rayfish/tetron-mobile Kotlin source (MPL / proprietary respectively); write this repo's Kotlin fresh, use them as design reference only -- this repo is GPL-3.0 and must carry only its own code plus the vendored GPL-3.0 oc-rsync fork.

## Test devices

LG V40 (real device, real camera roll) and the AORUS machine (headless receiver for a stock `rsyncd`). See tetron-mobile's `DO-NOT-COMMIT/TEST_PROCEDURE.md` for device roster/connection details.

**Correction 2026-08-19:** the xps-17-9720 remote emulator AVD was logged
here as broken ("qemu/gfxstream crash", 2026-08-18) -- this is almost
certainly the same misdiagnosis tetron-mobile already hit and fixed
2026-08-03: launching the emulator over a bare non-interactive SSH session
with no `DISPLAY` set makes it silently exit, which reads like a GPU/
gfxstream crash but is not one. See tetron-mobile's
`DO-NOT-COMMIT/TEST_PROCEDURE.md` §3.5 for the real start command
(`DISPLAY=:0 $REMOTE_SDK/emulator/emulator -avd $AVD_NAME`) and
`TODO_DETAILS.md#emulator-display-misdiagnosis` for the postmortem. Before
trusting "the emulator is broken" again, retry with `DISPLAY=:0` explicitly
set; only re-file it as a real crash if that still fails. Use the LG V40 in
the meantime regardless -- it is the actual SYNC-011 reference device, not
just a fallback.

## Repo layout

```
Cargo.toml, src/lib.rs, uniffi-bindgen.rs   -- the sync-app Rust crate (repo root, not nested)
vendor/oc-rsync/                            -- patched oc-rsync fork, applied + tracked (SYNC-002)
android/                                    -- Gradle/Kotlin/Compose app
spec/, reconcile.py, pyproject.toml         -- spec-first workflow, own from core's
DO-NOT-COMMIT/                              -- working docs, gitignored
LICENSE                                     -- GPL-3.0
```