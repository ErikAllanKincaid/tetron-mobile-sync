# tetron-mobile-sync — Agent Guide

> **This file is the canonical guidance for any AI coding agent working in this repository.** `AGENTS.md` is the source of truth; `CLAUDE.md` is a symlink to it.

> **THIS REPOSITORY IS `tetron-mobile-sync`**, the GPL-3.0 Android photo-backup addon for the tetron mesh. Design record: `DO-NOT-COMMIT/PLAN_tetron-mobile-sync_2026-08-13.md` (consensus reached 2026-08-18). `spec/sync.py` governs the actual work, same spec-first workflow as core tetron and tetron-mobile.

All work must be in a branch. Merge to `main` via a GitHub PR when a remote exists (same convention as core and tetron-mobile); until then, keep feature branches locally and merge review-first the same way. Conventional commit subjects, no authorship trailers of any kind. Any changes to tetron core require testing in the testsuite to verify no regression (this repo makes no core changes by design -- see Roadmap). Any change to the tetron-mobile bridge contract (authority/`get_status` shape) requires the `MeshStatusProviderContractTest` on the tetron-mobile side to still pass.

## Why this is a separate repo

tetron adds one thing: a mesh VPN. This repo is a product built on top of it: a phone-to-home photo backup. Core "does one thing well" and carries nothing product-specific; the desktop addons (`tetron-webui`, `tetron-systray`) already follow that convention over IPC; tetron-mobile consumes core via embedding (UniFFI); this app consumes *tetron-mobile* via a ContentProvider contract (MOBILE-024) and a stock `rsync --daemon` at home. No tetron core code, and no tetron-mobile code, belongs here.

**License mechanics:** this repo ships GPL-3.0 (see `LICENSE`), because it embeds oc-rsync (GPL-3.0) in-process via a vendored patched fork -- USER's route (a) decision (plan §Transfer mechanism, decision #16); the subprocess route (b) and mechanism switch (c) are closed. tetron-mobile stays proprietary: the bridge is an IPC contract between two separately-licensed programs, and this repo's code must never be copied into it (and vice versa -- MOBILE-024's `BridgeGrants`/`GrantActivity`/provider stay theirs). The sync app does NOT embed tetron core; it has no daemon of its own. The home side is a stock `rsync --daemon` -- no receiver code, ever (SYNC-010 is config + docs + an optional install script only).

## Roadmap / spec

Status: spec-only scaffold. The build is scoped as SYNC-001..SYNC-011 in `spec/sync.py`, which also records the decision register (consensus 2026-08-18) and the still-open items. Dependency ordering (also stated per-class in `spec/sync.py`):

- SYNC-001 repo scaffold (crate + Gradle/Compose app, GPL-3.0, mirror of tetron-mobile's proven pipeline) — no deps, first.
- SYNC-002 embedded oc-rsync engine (vendored patched fork `vendor/oc-rsync/`, `--no-default-features --features "openssl-vendored,zstd,lz4,parallel,xattr"`, `--partial` resume, `TransferProgressCallback` through UniFFI) — after SYNC-001.
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

`reconcile.py` currently checks only the spec tree (`libspec diff` clean against HEAD); the cargo build/clippy/test/audit checks arrive with SYNC-001.

## Conventions

Same as core where applicable: `cargo -q check`/`clippy`/`test`, no authorship trailers in commits, spec-driven one-requirement-at-a-time changes, keep `AGENTS.md` current after any significant change. Keep `README.md`'s status line honest. Wire-compat tests against a real rsyncd are `#[ignore]`-gated so machines without rsync are not broken by `cargo test`. Do not copy upstream rayfish/tetron-mobile Kotlin source (MPL / proprietary respectively); write this repo's Kotlin fresh, use them as design reference only -- this repo is GPL-3.0 and must carry only its own code plus the vendored GPL-3.0 oc-rsync fork.

## Test devices

LG V40 (real device, real camera roll) and the AORUS machine (headless receiver for a stock `rsyncd`). See tetron-mobile's `DO-NOT-COMMIT/TEST_PROCEDURE.md` for device roster/connection details. The xps-17-9720 emulator AVD has been broken (qemu/gfxstream crash 2026-08-18) -- use the LG V40 until it is fixed.

## Repo layout

```
Cargo.toml, src/lib.rs, uniffi-bindgen.rs   -- the sync-app Rust crate (repo root, not nested)
vendor/oc-rsync/                            -- patched oc-rsync fork, applied + tracked (SYNC-002)
android/                                    -- Gradle/Kotlin/Compose app
spec/, reconcile.py, pyproject.toml         -- spec-first workflow, own from core's
DO-NOT-COMMIT/                              -- working docs, gitignored
LICENSE                                     -- GPL-3.0
```