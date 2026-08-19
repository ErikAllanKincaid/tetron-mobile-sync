# tetron-mobile-sync — Agent Guide

> **This file is the canonical guidance for any AI coding agent working in this repository.** `AGENTS.md` is the source of truth; `CLAUDE.md` is a symlink to it.

> **THIS REPOSITORY IS `tetron-mobile-sync`**, the GPL-3.0 Android photo-backup addon for the tetron mesh. Design record: `DO-NOT-COMMIT/PLAN_tetron-mobile-sync_2026-08-13.md` (consensus reached 2026-08-18). `spec/sync.py` governs the actual work, same spec-first workflow as core tetron and tetron-mobile.

All work must be in a branch. Merge to `main` via a GitHub PR when a remote exists (same convention as core and tetron-mobile); until then, keep feature branches locally and merge review-first the same way. Conventional commit subjects, no authorship trailers of any kind. Any changes to tetron core require testing in the testsuite to verify no regression (this repo makes no core changes by design -- see Roadmap). Any change to the tetron-mobile bridge contract (authority/`get_status` shape) requires the `MeshStatusProviderContractTest` on the tetron-mobile side to still pass.

## Why this is a separate repo

tetron adds one thing: a mesh VPN. This repo is a product built on top of it: a phone-to-home photo backup. Core "does one thing well" and carries nothing product-specific; the desktop addons (`tetron-webui`, `tetron-systray`) already follow that convention over IPC; tetron-mobile consumes core via embedding (UniFFI); this app consumes *tetron-mobile* via a ContentProvider contract (MOBILE-024) and a stock `rsync --daemon` at home. No tetron core code, and no tetron-mobile code, belongs here.

**License mechanics:** this repo ships GPL-3.0 (see `LICENSE`), because it embeds oc-rsync (GPL-3.0) in-process via a vendored patched fork -- USER's route (a) decision (plan §Transfer mechanism, decision #16); the subprocess route (b) and mechanism switch (c) are closed. tetron-mobile stays proprietary: the bridge is an IPC contract between two separately-licensed programs, and this repo's code must never be copied into it (and vice versa -- MOBILE-024's `BridgeGrants`/`GrantActivity`/provider stay theirs). The sync app does NOT embed tetron core; it has no daemon of its own. The home side is a stock `rsync --daemon` -- no receiver code, ever (SYNC-010 is config + docs + an optional install script only).

## Roadmap / spec

Status: SYNC-001 done (2026-08-18, `feat/sync-001-repo-scaffold`): crate + Gradle/Compose app scaffolded, host + Android cross-compile + UniFFI Kotlin bindings + debug APK all building, reconcile.py carries the cargo checks. **Correction 2026-08-19: NOT merged to `main`** -- `main` is missing SYNC-001's own scaffold commit (`79c1918`) entirely and sits one commit behind `feat/sync-001-repo-scaffold` (only `ebee5a3`, the pre-SYNC-001 spec-only scaffold commit). No remote is configured for this repo (`git remote -v` empty) -- everything so far is local-only.

SYNC-002 (embedded oc-rsync engine) is functionally working but **still
uncommitted** on `feat/sync-002-oc-rsync-embedding` as of 2026-08-19 -- a
future agent picking up this branch should find real, uncommitted work in
the tree, not a clean starting point. Current state: `vendor/oc-rsync/`
(patched fork) is vendored and tracked; `src/lib.rs` implements
`SyncEngine::run_client` against `oc-rsync-core`'s
`run_client_with_observer`, with `SyncProgressListener` wired as a UniFFI
callback interface; `src/bin/sync-test-helper.rs` (dev-only,
`test-helper`-feature-gated) and `tests/engine_local.rs` +
`tests/engine_rsyncd.rs` (the latter `#[ignore]`-gated on `rsync` being on
PATH) exist. Verified 2026-08-19: `cargo -q check` clean, `cargo test`
(5 unconditional: 1 lib unit test + 4 in `engine_local.rs`) passes.

**Known bug, unresolved 2026-08-19: `tests/engine_rsyncd.rs`'s
`push_resumes_from_existing_partial_at_receiver` hangs indefinitely**
against a real local `rsync --daemon` -- reproduced twice, isolated by
running each `#[ignore]`-gated test alone (`cargo test --test engine_rsyncd
-- --ignored --test-threads 1 <name>`). `push_to_rsyncd_is_byte_identical_
and_idempotent` passes cleanly (1.8s). The hung process sits single-threaded
in `sigsuspend` (`cat /proc/<pid>/status`/`wchan`), not blocked on socket
I/O, with an open connected socket fd -- consistent with a deadlock in
`run_client`'s handling of the "receiver already holds a same-named file
with matching leading bytes" case specifically (this test pre-seeds the
daemon module directory with the first 256KiB of the file *before* the
transfer starts, unlike the SIGKILL test which produces the partial by
interrupting a real in-flight run). Never got to run
`killed_push_keeps_partial_and_next_run_resumes` (third test) because the
suite hangs on the second one first -- its own pass/fail status is unknown,
not verified passing. This directly contradicts the fork's SYNC-002 spike
claim ("`--partial` resume... spike-verified 2026-08-19") for at least this
one code path; do not trust that claim without re-verifying it against
*this* embedding, not just the spike. Needs a debugging session (`gdb -p
<pid>`, or instrument `run_client_with_observer`/the basis-file matching
path in `vendor/oc-rsync/crates/core`) before SYNC-002 can be called
accepted -- kill any stuck test process and its child `rsync --daemon`
before retrying (`pkill -9 -f engine_rsyncd; pkill -9 -f 'rsync --daemon'`).

**Bug found + fixed 2026-08-19 (uncommitted, same branch):**
`reconcile.py`'s `check_cargo_audit` required `count == 0` unconditionally,
so the moment SYNC-002 landed real code, `python3 reconcile.py` would fail
outright on the one advisory (RUSTSEC-2023-0071, rsa via russh) that
spec/sync.py's own SYNC-002 ACCEPTANCE text explicitly says is expected and
accepted -- a gate permanently red for a state the spec calls passing. Fixed
by adding an `ACCEPTED_ADVISORIES` allow-set the check filters against
before counting; re-run confirms `cargo_audit: {"installed": true, "count":
0}` now. If you add a new accepted advisory, update spec/sync.py's own
rationale for it AND this set together, same bar as the first one.

Not yet done: no commit on this branch (code + the fixes/corrections here
all still sit uncommitted in the working tree -- `git status` before
assuming a clean start), and SYNC-002's own spec corrections below
(feature-string, RUSTSEC comment note) need a `libspec diff`-clean commit
alongside the code. Nothing past SYNC-002 has started. The build is scoped
as SYNC-001..SYNC-011 in `spec/sync.py`, which also records the decision
register (consensus 2026-08-18) and the still-open items. Dependency
ordering (also stated per-class in `spec/sync.py`):

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