'''
tetron-mobile-sync: the GPL-3.0 Android photo-backup addon for the tetron
mesh (SYNC-*). One-way phone-to-home photo/video backup over the tetron mesh,
PhotoSync-like in UX, rsync-based in mechanics: an embedded oc-rsync client
talking to a stock `rsync --daemon` on the home machine. Deliberately one-
directional (phone is always the source), matching "backup" semantics rather
than mirroring.

Design record: `DO-NOT-COMMIT/PLAN_tetron-mobile-sync_2026-08-13.md` (copied
from tetron-mobile's DO-NOT-COMMIT at repo creation; consensus reached
2026-08-18). Prior-art research and the oc-rsync spike findings are recorded
in tetron-mobile's DO-NOT-COMMIT (`RESEARCH_PhotoBackup_PriorArt_2026-08-13.md`)
and the spike scratch repo (`~/code/oc-rsync-spike/FINDINGS.md`). The
discussion history lives in the plan; this file is the versioned, buildable
conclusion of it.

## Relationship to tetron-mobile

The sync app is a SEPARATE app, in a separate repo. It needs mesh state
(own mesh IP, tunnel state, peer roster with per-peer `ConnKind`) to pick a
target and gate transfers, and gets it from tetron-mobile's read-only mesh
status bridge, shipped as MOBILE-024 (ContentProvider authority
`xyz.tetron.mobile.status`, `call("get_status")`, uniform per-caller-user
grant). The two apps are separately licensed: tetron-mobile stays
proprietary (the bridge is an IPC contract between two separately-licensed
programs), this repo is GPL-3.0 because it embeds oc-rsync (GPL-3.0)
in-process -- USER's route (a) decision, 2026-08-18.

Why it must be a separate app (plan §Why a separate app): the main app
excludes its own package from its VPN so its control sockets bypass the TUN;
a sync client living inside it could never reach mesh IPs. A separate app
routes through tetron's TUN automatically. Consequence, load-bearing: the
sync app depends on the main app's tunnel being Active; tunnel
down/Standby/Suspended = sync defers and notifies.

## Decision register (consensus reached 2026-08-18, plan §Decision register)

| # | Decision | Chosen |
|---|---|---|
| 1 | Receiver authorization | Explicit per-identity allow-list; materializes as rsyncd `hosts allow` keyed on mesh IP (deterministic per identity in core `addressing.rs`) |
| 2 | Transfer mechanism | oc-rsync embedded, stock rsyncd home side; spike-verified 2026-08-19 (`--partial` resume, wire compat, Android build, progress API) |
| 3 | Retry/backoff when gated | Skip cycle + notify user, coalesced one-per-reason-per-N-hours |
| 4 | Direct-only gate default | ON; per-target `ConnKind` via the bridge, never a network-type heuristic |
| 5 | Charging gate default | OFF (configurable) |
| 6 | Low-battery pause | ON (configurable, threshold ~20%) |
| 7 | SSID-specific Wi-Fi | DROPPED from v1 (location permission cost; redundant with direct gate) |
| 8 | Delete-after-backup | Explicit opt-in, default OFF; only files transferred this run |
| 9 | Backup targets | Single reconfigurable; multiple later |
| 10 | Folders | Camera roll (DCIM) only; SAF folders later |
| 11 | Wi-Fi scope | Any Wi-Fi default; cellular configurable |
| 12 | Progress UI | In-app per-file progress + run history; `TransferProgressEvent` (per-file bytes, files_done/total) |
| 13 | Repo split | Separate app, own repo; home side = config + docs only |
| 14 | Background trigger | Opportunistic v1 (manual + WorkManager + network-change); foreground service v2 |
| 15 | IPC bridge | Full read-only mesh status in tetron-mobile; IMPLEMENTED (MOBILE-024) |
| 16 | oc-rsync license | ACCEPT GPL-3.0 for the sync app (route (a)); subprocess (b) and mechanism switch (c) closed |

## Still open (recorded from the plan; resolved during implementation, not
## presumed here)

- Repo/app name: `tetron-mobile-sync` is the working name; app/product name
  and the Android package id (provisional `xyz.tetron.sync`) are not decided.
- Licenses mechanics: whether the six Android portability patches are
  upstreamed first or the patched fork is vendored as-is; how the fork is
  maintained (plan defaults: vendor in-repo, enumerate patches, upstream as
  follow-up work).
- Whether to raise the unconditional `russh`/RUSTSEC-2023-0071 dependency
  question upstream before forking.
- Media-read permission UX details (full "all photos" grant vs Android 14+
  partial-access warning state).
- Concrete values: WorkManager cadence, notification debounce window N
  (~6h suggested), low-battery threshold (~20% suggested), notification
  copy/channels.
- Manual "Back up now" button behavior when gated (respect gates vs
  "Transfer anyway?" confirm; provisional: respect gates with a confirm).
- Whether delete-after-backup runs immediately after a run or defers the
  consent prompt.
- Where the home-side setup docs live (sync repo README vs core tetron
  docs; provisional: sync repo).
- v2 foreground-service scope details (deferred by definition).

## Repo layout

```
Cargo.toml, src/lib.rs, uniffi-bindgen.rs   -- the sync-app Rust crate (repo root)
vendor/oc-rsync/                            -- patched oc-rsync fork, applied + tracked
android/                                    -- Gradle/Kotlin/Compose app
spec/, reconcile.py, pyproject.toml         -- spec-first workflow, own from core's
DO-NOT-COMMIT/                              -- working docs, gitignored
LICENSE                                     -- GPL-3.0 (route (a))
```
'''

from libspec import Requirement, UserStory


class SyncIntent(UserStory):
    """USER-STORY: SYNC-INTENT

    Ship a standalone GPL-3.0 Android app (working name tetron-mobile-sync)
    that backs up the phone's camera roll to a home computer over the tetron
    mesh, one-way, using an embedded oc-rsync client against a stock
    `rsync --daemon` running on the home machine. The app reads mesh state
    (own mesh IP, tunnel state, peer roster with per-peer connection kind)
    from tetron-mobile's MOBILE-024 status bridge, gates transfers on
    Wi-Fi/direct-connection/battery conditions, and offers explicit opt-in
    delete-after-backup. v1 is opportunistic: manual button + periodic
    WorkManager job + network-change callback; a foreground-service
    instant-upload mode is explicitly a v2 follow-up.

    Priority: medium.
    User journey: install both apps -> share a mesh network with the home
    machine -> grant mesh access once from the main app -> pick the home
    machine as target -> press Back up now (or wait for the periodic job) ->
    watch per-file progress -> optionally enable delete-after-backup.
    Acceptance: on the LG V40 reference device against the AORUS receiver,
    a real camera roll backs up over Wi-Fi with per-file progress; the run
    is resumed (not restarted) after an interruption; delete-after-backup
    deletes only files transferred by that run, through a system confirm
    dialog; on cellular, transfers happen only over Direct paths and defer
    on relay-only paths.
    """
    brief_title = "GPL-3.0 Android photo-backup addon for the tetron mesh"
    priority = "medium"


class SyncRepoScaffold(Requirement):
    """REQUIREMENT-ID: SYNC-001

    Repository scaffold for the sync app, mirroring tetron-mobile's proven
    pipeline (MOBILE-001..004): a `cdylib` Rust crate at this repo's root
    (`crate-type = ["cdylib", "lib"]`, `uniffi` dependency, library mode -- no
    `.udl` file), `uniffi-bindgen.rs` wired as a `[[bin]]` target gated
    `required-features = ["uniffi/cli"]`, and a minimal Gradle/Kotlin app at
    `android/`. The crate does NOT embed tetron core -- this app has no
    daemon of its own; it embeds oc-rsync (its own follow-up, SYNC-002) and
    talks to the mesh only through tetron-mobile's ContentProvider bridge
    (SYNC-003). No rustls-platform-verifier Android init is needed here (no
    iroh), unlike MOBILE-004.

    Scope of this requirement is the scaffold only, not a working backup:
    - `Cargo.toml` + `src/lib.rs` with a minimal UniFFI surface that proves
      the whole FFI chain end to end (a `version() -> String`-style
      placeholder, deliberately trivial; the real engine lands in SYNC-002).
    - `cargo -q check`/`clippy` pass for the host target, plus the real
      Android cross-compile (`cargo ndk -t arm64-v8a -t x86_64 -o <out>
      build`, NDK r27, cargo-ndk) producing `libtetron_mobile_sync.so` per
      ABI -- the same gotcha MOBILE-002 existed to catch: host `cargo check`
      proves nothing about `target_os = "android"`.
    - Gradle app at `android/` with `applicationId`/package root from the
      naming decision (provisional `xyz.tetron.sync`), standard two-module
      project, Compose UI (this repo starts on Compose -- no plain-View
      interim; tetron-mobile's View-first detour was a build-legacy, not a
      requirement), versions matching the proven tetron-mobile combination
      (Gradle 8.11.1 / AGP 8.10.0 / Kotlin 2.0.21 / compileSdk 35 /
      targetSdk 35 / minSdk 26 / `abiFilters = [arm64-v8a, x86_64]`),
      UniFFI Kotlin bindings generated into
      `app/src/main/java/uniffi/` and `.so` files into
      `app/src/main/jniLibs/<abi>/` as an explicit build step (Gradle task
      automation is a follow-up, same progression as MOBILE-003/004).
      Unit/`jvm` tests runnable via `./gradlew :app:testDebugUnitTest`; the
      instrumented `androidTest` harness for SYNC-003/005/011 begins here
      with a trivial smoke test.
    - `LICENSE` file (GPL-3.0 full text) and SPDX headers on new files --
      route (a) is settled, the license is not an open question.
    - `reconcile.py` grows the standard cargo checks
      (build/clippy/test/cargo-audit) in this requirement: the crate now
      exists, so the checks exist.

    Explicitly NOT in scope: the vendored patched oc-rsync fork and its
    embedding (SYNC-002); the bridge client (SYNC-003); any gate, transfer,
    or UI logic -- each is its own requirement.

    ACCEPTANCE: `cargo -q check`/`clippy` (host) clean; `cargo ndk`
    produces both `.so` files with no errors; `./gradlew :app:assembleDebug`
    succeeds producing a debug APK bundling both ABIs; `./gradlew
    :app:testDebugUnitTest` runs the smoke test; `python3 reconcile.py`
    exit 0.
    ENFORCEMENT: `reconcile.py` (cargo build/clippy/test/audit once added)
    is the automated gate; cross-compile + APK are verified by running the
    commands, same manual bar as MOBILE-002/004.
    """
    req_id = "SYNC-001"


class SyncOcRsyncEmbedding(Requirement):
    """REQUIREMENT-ID: SYNC-002

    The transfer engine: embed oc-rsync in-process and prove a real backup
    run against a stock `rsync --daemon`. Wire compat with stock rsyncd,
    `--partial` interrupt/resume, Android cross-compile, and the first-class
    progress API were all spike-verified 2026-08-19 (`~/code/oc-rsync-spike/
    FINDINGS.md`); this requirement turns the spike into the product crate.

    Dependencies: SYNC-001 (this crate's scaffold) must land first.

    - **Vendored patched fork.** oc-rsync (oferchen/rsync, GPL-3.0) is a
      git dependency pinned to a fixed rev, using a fork kept in this repo's
      `vendor/oc-rsync/` (spike commit ce6d7f8, v0.6.4) with the six Android
      portability patches applied and tracked: (1) compress default
      `["zstd","lz4","zlib-rs"]`, (2) protocol default `["zlib-rs"]`,
      (3) `fast_io` `confined_open` Android cfg-guard (openat2 is
      glibc-only), (4) `getgrnam_r`/`getgrgid_r` non-unix stubs on Android
      (metadata/cli/daemon), (5) `cli/platform.rs` android stub lookups,
      (6) jemalloc android-excluded + allocator cfg in the bin. A
      `vendor/oc-rsync/PATCHES.md` enumerates each patch with its rationale
      and upstream status; upstreaming them as PRs is tracked follow-up, not
      bundled here.
    - **Feature set, Android build:** `--no-default-features --features
      "openssl-vendored,zstd,lz4,parallel,xattr"` was the spike's feature
      string, measured against the fork's root `bin` package (spike harness
      shape). It does NOT carry over to this crate's actual dependency, which
      embeds only `vendor/oc-rsync/crates/core` -- **correction 2026-08-19,
      already documented in `vendor/oc-rsync/PATCHES.md`'s "Embedded-build
      note (SYNC-002)" but not previously reflected here:** on `core`, the
      real feature string is `default-features = false, features = ["zstd",
      "lz4", "xattr"]`. `openssl-vendored` does not apply -- `core`'s unix
      dependency on `checksums` is feature-less, so no `openssl-sys` enters
      the embedded tree regardless. `parallel` does not exist as a `core`
      feature at all (it is a root-bin-only alias). `zlib-rs` (pure Rust,
      replacing the C `zlib-ng` -- cmake-rs/NDK ABI conflict) is not a
      feature flag to pass here either; it is the *default* backend
      `crates/compress`/`crates/protocol` already select per patch group 2 in
      `PATCHES.md`, so `core`'s default features already carry it. `acl` and
      SSH transport stay excluded: no POSIX ACLs on Android storage, and the
      addon uses the rsync daemon protocol only, never SSH. See
      `vendor/oc-rsync/PATCHES.md` for the full explanation before changing
      this crate's `Cargo.toml` feature list.
    - **Embedding surface:** the crate wraps oc-rsync's embedding API
      (`run_client`/`run_client_with`-style, caller-supplied writers) behind
      UniFFI. The transfer progress callback
      (`TransferProgressCallback`/`TransferProgressEvent`: per-file
      completion with bytes, files_done/total, flist_eof) is exposed through
      a UniFFI callback interface so Kotlin receives real per-file progress
      without stdout parsing (decision #12). The engine always invokes with
      `--partial` semantics: an interrupted run leaves the partial file and
      the next run resumes it instead of restarting (decision #3 + plan
      testing).
    - **Security posture:** `cargo audit` stays clean except the known,
      accepted RUSTSEC-2023-0071 (rsa via russh, SSH transport, unused in
      this app -- documented in FINDINGS.md, mitigation = never enable the
      SSH feature); the single-maintainer risk is accepted per route (a),
      with the vendored fork doubling as our maintenance surface.
      Re-verified 2026-08-19 against the real embedded crate (not just the
      spike): `cargo audit` reports exactly one vulnerability, this same
      RUSTSEC-2023-0071 against `rsa`, plus two unrelated non-blocking
      "unmaintained crate" warnings (bincode/RUSTSEC-2025-0141,
      paste/RUSTSEC-2024-0436, both transitive, neither SSH-related) --
      expected, not a regression, no action needed. **Correction:** the
      root `Cargo.toml`'s `[workspace.dependencies]` comment on `russh`
      claims 0.62.1 "retires" RUSTSEC-2023-0071 by moving to rsa 0.10 --
      `cargo audit`'s own advisory record contradicts this
      (`Solution: No fixed upgrade is available!`, i.e. no rsa release
      resolves the underlying timing side-channel yet, independent of the
      russh version pinned). Do not treat that comment as evidence the
      advisory is handled; the actual mitigation is still "unused in this
      app, SSH feature never enabled," as this bullet already said.

    ACCEPTANCE: `cargo ndk -t arm64-v8a` cross-compile of the crate with
    the engine embedded succeeds; a host-side integration test runs a real
    client transfer against a stock rsyncd (spike harness shape), transfers
    a seeded directory tree byte-identically, and an interrupted
    (kill-mid-transfer) run resumes with `--partial` on the next invocation;
    `cargo audit` shows only the known russh advisory. All three
    `tests/engine_rsyncd.rs` cases pass: `push_to_rsyncd_is_byte_identical_
    and_idempotent`, `push_resumes_from_existing_partial_at_receiver`, and
    `killed_push_keeps_partial_and_next_run_resumes`.

    **MET, corrected 2026-08-19 (was misdiagnosed as an unresolved hang):**
    `push_resumes_from_existing_partial_at_receiver` did not hang, and
    `run_client` has no deadlock. It never returned within manual-testing
    patience because its 10 MiB fixture was `vec![0xCDu8; N]` -- every byte
    identical. That makes every rolling-checksum window in the file collide,
    so the matcher's cheap weak-checksum prefilter (`tag_table`/`bithash` in
    `vendor/oc-rsync/crates/matching/src/index/mod.rs`) never rejects a
    candidate and `find_match_slices_filtered` falls through to an expensive
    strong-checksum compute at nearly every byte offset -- fine at a few
    seconds in `--release`, but the unoptimized `cargo test` debug profile
    (which reconcile.py and this workflow always run) stretched that past
    what anyone waited out, reading as "hangs indefinitely." Confirmed via
    `sudo gdb -p <pid> -batch -ex "thread apply all bt"` (ptrace needs
    `sudo` under this host's `yama/ptrace_scope=1`): the test thread was
    live inside `matching::generator::DeltaGenerator::generate_with_prune`
    -> `find_match_slices_filtered` -> `xxhash_rust::xxh3`, not asleep on
    any lock -- and a `--release` run of the same test completed in 7.6s.
    Fixed by replacing the uniform-byte fixtures with a small deterministic
    splitmix64 PRNG helper (`pseudo_random_bytes` in `tests/engine_rsyncd.rs`)
    so rolling-checksum windows actually vary, which is also the more
    representative fixture -- real photos/video are never a single repeated
    byte. `killed_push_keeps_partial_and_next_run_resumes` had never
    actually run before (the suite hung on the test before it); with the
    fixture fix it does, but its original 400 MiB size still took the
    resume-side match past a minute in debug, so it is now 40 MiB (bwlimit
    and the kill-detection threshold scaled down to match, still leaving a
    multi-second kill window). All three tests now pass deterministically:
    `cargo test --test engine_rsyncd -- --ignored --test-threads 1` is
    ok in ~82s. Processes from a killed run must be swept with name-based
    `pkill -9 -x rsync` / `pkill -9 -x sync-test-helper` only -- `pkill -f`
    self-matches the invoking shell's own command line (it contains these
    same names) and kills the session instead of the target.
    SYNC-002 is now ACCEPTED.
    ENFORCEMENT: reconcile.py cargo build/clippy/test/audit checks carry the
    crate; the wire-compat + resume integration test lives in `tests/`
    (host-side, gated on rsync being installed), an explicit `#[ignore]`
    default so a machine without rsyncd is not broken by `cargo test`.
    `check_cargo_audit` allow-lists RUSTSEC-2023-0071 explicitly (fixed
    2026-08-19 -- it previously required a hard zero-count, which the
    accepted advisory above would always trip the moment this crate's
    Cargo.lock included it); update that allow-list alongside this
    docstring if a new advisory is ever accepted.
    """
    req_id = "SYNC-002"


class SyncMeshBridgeClient(Requirement):
    """REQUIREMENT-ID: SYNC-003

    Kotlin bridge client: consume tetron-mobile's MOBILE-024 mesh status
    bridge from this app. The bridge is a read-only ContentProvider
    (authority `xyz.tetron.mobile.status`, `call("get_status")`) returning a
    cached `StatusSnapshot` (tunnel-state enum incl. CoreNotRunning, network,
    own mesh IP, subnet, peer roster with per-peer ConnKind mapped
    Direct/Relay/Tor/Unknown, `updatedAtMillis`; never any keys/secrets).

    Dependencies: SYNC-001 (app scaffold); no dependency on SYNC-002 -- the
    Kotlin bridge client can be built and tested in parallel with the engine
    work. Requires MOBILE-024 to exist on the tetron-mobile side (it does,
    2026-08-19).

    Scope:
    - A `MeshBridge` Kotlin component wrapping the ContentProvider call:
      resolves the authority, calls `get_status`, parses the Bundle into
      typed Kotlin models (`BridgeSnapshot`, `BridgePeer`,
      `BridgeTunnelState`). Callers can NEVER trigger a poll on the provider
      side (MOBILE-024's contract: it answers from its cached holder only) --
      our side simply re-queries when it needs freshness; a short TTL cache
      here avoids hammering the provider during UI polling.
    - Consent flow handling: an ungranted caller receives
      `consent-required=true`. The sync app surfaces this as a UI banner
      ("tetron-mobile needs to grant access") and can detect post-grant
      state on the next query -- the grant itself happens in the main app's
      GrantActivity (notification-launched), not here. No bypass attempt of
      any kind: uniform per-caller user grant is MOBILE-024's rule.
    - State mapping for gates (SYNC-004) and target selection (SYNC-009):
      tunnel state (Active / Standby / Suspended / Reconnecting /
      NotJoined / CoreNotRunning), own mesh IP (enrollment UX: copy button),
      peer roster as candidate targets with per-peer ConnKind. The roster is
      the only place mesh peers are ever discovered -- the user never types
      a mesh IP into v1's target settings (decision #9, plan §Auth).
    - Handling of a dead/absent main app: provider answers CoreNotRunning or
      ContentProviderNotFound -> bridge reports `unavailable`, UI shows
      "tetron-mobile not running / access not granted".

    ACCEPTANCE: unit tests (mock ContentResolver Bundle) cover parsing of
    every `BridgeTunnelState`, ConnKind mapping, and the consent-required
    branch; instrumented test against the real installed tetron-mobile on
    the LG V40 covers grant-then-snapshot on a real device; `./gradlew
    :app:testDebugUnitTest` + any instrumented test pass.
    ENFORCEMENT: unit tests are the automated gate; the real-device
    cross-app flow is verified manually (same bar as MOBILE-024's own
    verification).
    """
    req_id = "SYNC-003"


class SyncGateEvaluation(Requirement):
    """REQUIREMENT-ID: SYNC-004

    Transfer gating: the AND-logic of conditions that decide whether a run
    proceeds, evaluated cheap-local-first with zero network activity when a
    local check fails (plan §Gating conditions, decisions #3-#7, #11).

    Dependencies: SYNC-003 must land first (per-target ConnKind + tunnel
    state come from the bridge; the gate decision MUST come from the bridge,
    never from a network-type heuristic -- USER correction 2026-08-18:
    cellular + Direct exists, so path type is about endpoints and NATs, not
    transport technology).

    Gates, all configurable, defaults from the decision register:
    1. Wi-Fi only -- default ON: `ConnectivityManager` `TRANSPORT_WIFI`
       check; cellular configurable OFF/ON. (No SSID-specific matching in
       v1, decision #7.)
    2. Direct connection only -- default ON: evaluated per-target from the
       bridge's ConnKind, second-stage (only meaningful once a connection to
       the target exists); relay/Tor paths defer. Direct-or-deferred, never
       relay transfer.
    3. Charging required -- default OFF, configurable.
    4. Low-battery pause -- default ON: skip below a configurable threshold
       (~20% provisional); without it the charging-not-required default lets
       a bulk transfer drain the phone to zero (USER: "very important").
    Plus an implicit gate from the architecture itself: the tunnel must be
    Active ("tunnel not Active" reason -- down/Standby/Suspended means mesh
    IPs are unroutable from this app). Tunnel state comes from the bridge
    (SYNC-003).

    Behavior when gated (decision #3): skip this cycle and notify, no silent
    wait, no retry storm. Coalescing: one notification per reason per N
    hours (N configurable, ~6h provisional), reasons: not on Wi-Fi,
    relay-only path, low battery, charging required, target unreachable,
    tunnel not Active.

    ACCEPTANCE: unit tests cover the full AND matrix -- every gate
    individually false blocks the run with the right reason, all-true
    passes, and the cheap-local checks short-circuit before any network
    activity (assert no rsync invocation mocked in); coalescing logic drops
    a second notification for the same reason inside the window. The reason
    → notification mapping is covered by the pipeline tests (SYNC-005).
    ENFORCEMENT: the gate matrix + coalescing tests are the automated gate.
    """
    req_id = "SYNC-004"


class SyncTransferPipeline(Requirement):
    """REQUIREMENT-ID: SYNC-005

    The run pipeline: the single code path every trigger funnels into
    (SYNC-006) which executes one backup cycle and records its outcome.

    Dependencies: SYNC-002 (engine) and SYNC-004 (gates) must land first;
    SYNC-003 provides bridge state to the gates. SYNC-008 (media access)
    supplies the source path resolution -- wiring is minimal if media access
    lands after this, adjust order as stated in AGENTS.md's dependency
    notes.

    Scope:
    - One run = evaluate gates (SYNC-004) -> resolve the single
      reconfigurable target (decision #9) -> rsync client invocation
      (source = current camera-roll path per SYNC-008, module = target's
      module name, `--partial` always, no delete flags -- delete is app
      logic, SYNC-007) -> stream progress events (SYNC-002's UniFFI
      callback) -> record run history -> emit completion/failure
      notification (coalesced per SYNC-004's policy).
    - Run history: last run time, added/skipped/failed counts -- added and
      failed come from the per-file event stream + exit status; "skipped"
      is transferred-minus-added (rsync's default same-size-same-mtime skip
      is the idempotence mechanism, plan §Trigger model: nothing new =
      file-list walk only, near-zero data).
    - Failure handling: a partial/interrupted run is NOT an error state --
      it records "interrupted, will resume", the partial file survives
      (`--partial`), and the next run resumes instead of restarting
      (decision #3). A hard failure (engine error, target unreachable,
      module rejected) records failed with the reason.
    - Reentrancy: one run at a time; a trigger arriving mid-run is a
      no-op (also covers WorkManager re-entry after process death -- the
      pipeline is stateless between runs, progress lives in the engine).

    ACCEPTANCE: host-side integration test (test-rsyncd harness from
    SYNC-002) drives the full pipeline: seeded tree -> run -> files
    present on the receiver -> idempotent re-run transfers nothing ->
    interrupt mid-run -> next run resumes and completes byte-identically.
    Android-side unit tests (gate short-circuit + history + notification)
    run under `testDebugUnitTest`.
    ENFORCEMENT: the integration test + unit tests are the automated gate.
    """
    req_id = "SYNC-005"


class SyncTriggerModel(Requirement):
    """REQUIREMENT-ID: SYNC-006

    v1 trigger layer (decision #14): opportunistic, no permanent background
    process. All triggers funnel into the single SYNC-005 pipeline.

    Dependencies: SYNC-005 (the pipeline exists to be triggered).

    Triggers:
    1. Manual "Back up now" button, always available on the home screen
       (UI wiring is SYNC-009; the trigger entry point itself is here).
    2. Periodic WorkManager job -- cadence configurable, ~daily default
       (provisional; OS runs it when convenient, typically overnight).
    3. Network-change callback for an immediate check while the app happens
       to be alive (a `ConnectivityManager` NetworkCallback registration;
       not a wake-up mechanism).

    Explicitly NOT in scope: the v2 foreground-service + MediaStore
    ContentObserver instant-upload mode. The plan's v2 note is explicit
    ("a great feature, would prevent photo loss - explore for v2") and the
    v1 trigger layer is where it slots in later -- this requirement must not
    be designed in a way that forecloses it (the pipeline/gates are
    transport-agnostic; a v2 trigger just calls the same entry point).

    ACCEPTANCE: unit tests assert all three triggers invoke the pipeline
    entry exactly once and a mid-run trigger is a no-op; an instrumented
    test (or manual adb check) confirms the WorkManager job is scheduled
    after first launch.
    ENFORCEMENT: trigger unit tests are the automated gate; WorkManager
    scheduling behavior is verified on the reference device.
    """
    req_id = "SYNC-006"


class SyncDeleteAfterBackup(Requirement):
    """REQUIREMENT-ID: SYNC-007

    Delete-after-backup: explicit opt-in, default OFF (decision #8, USER:
    "has to be explicit choice"), implemented as app logic layered on top
    of rsync (rsync has no delete-source concept).

    Dependencies: SYNC-005 (needs the transferred-this-run file set the
    pipeline captured via SYNC-002's per-file completion events).

    Scope:
    - Delete ONLY files THIS run actually transferred (byte-verified by the
      rsync protocol), never files skipped as already-present -- the
      mtime+size skip is not a byte check, so "already present" is not proof
      of a verified copy (plan §Trigger model clarification). The pipeline
      already collects per-file completions; this requirement consumes that
      set.
    - Android 13+ (API 33): deletion goes through
      `MediaStore.createDeleteRequest`, which shows the system confirm
      dialog ("Delete N backed-up photos?") -- the OS enforces explicitness
      even when the setting is on. Below API 33 the same app-level confirm
      is shown (fewer devices; v1 targets minSdk 26).
    - Timing open item (from the plan): whether deletion runs immediately
      after the run completes or defers to a separate confirm view --
      provisional: immediate, gated on the run's success, because the
      transferred-this-run set is only meaningful in run context.

    ACCEPTANCE: unit tests verify the delete set is exactly the transferred
    set (skipped files excluded) and that no delete path exists when the
    opt-in is off; on the reference device, a run with delete enabled shows
    the system confirm dialog listing the run's transferred items and
    deletes only those after confirmation.
    ENFORCEMENT: unit tests automated; the on-device consent flow is a
    manual verification item (also covered by SYNC-011).
    """
    req_id = "SYNC-007"


class SyncMediaAccess(Requirement):
    """REQUIREMENT-ID: SYNC-008

    Camera-roll media access (plan §Folders): v1 sources are the real
    filesystem DCIM path (`/storage/emulated/0/DCIM/Camera`), not a picker;
    `READ_MEDIA_IMAGES` (+ `READ_MEDIA_VIDEO`) make path reads legal on
    modern Android.

    Dependencies: none hard -- parallelizable with SYNC-002..005; consumed
    by SYNC-005 (source path resolution) and SYNC-009 (permission UI).
    Design note (plan): the real "not new" detection is rsync's mtime+size
    skip (SYNC-005); this requirement is only about permission and path,
    not tracking.

    Scope:
    - Runtime permission request for full "all photos" access (API 33+
      `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`; pre-33 the coarse
      `READ_EXTERNAL_STORAGE`). Requested at first-run setup / Backup-press
      time, not eagerly at launch (MOBILE-007 precedent for runtime
      permissions).
    - Partial-access detection and warning state: on Android 14+, the OS
      offers partial "select photos" access
      (`READ_MEDIA_VISUAL_USER_SELECTED`) that would silently exclude most
      of the gallery from a backup run. v1 must request full access
      explicitly and surface a warning banner when the grant is partial
      ("only selected photos will back up"). Open-item mechanics
      (request flow wording/dialogs) resolved during implementation.
    - Path resolution: DCIM/Camera via `Environment`/MediaStore-derived
      canonical path; source directory must exist for a run (else gate
      reason "target unreachable"-style failure surfaced, not a crash).

    ACCEPTANCE: unit tests cover path resolution + partial-access grant
    detection from a mocked permission state; manual/device verification
    confirms the full access prompt appears and a partial grant produces
    the warning banner.
    ENFORCEMENT: unit tests automated; the permission dialog UX is manual.
    """
    req_id = "SYNC-008"


class SyncUi(Requirement):
    """REQUIREMENT-ID: SYNC-009

    The Compose UI pass (decision #12, #13): home screen, progress,
    history, settings, and the enrollment/consent surfaces the bridge
    needs. This is the design pass over already-working logic (tetron-mobile
    convention: prove the pipeline first, design after -- same sequencing).

    Dependencies: SYNC-003 (bridge state), SYNC-005 (pipeline + history),
    SYNC-007 (delete opt-in UI), SYNC-008 (permission UI); SYNC-006's
    manual trigger button is wired here.

    Screens/areas:
    - Home: big Back up now button (behavior when gated: provisional "respect
      gates, offer Transfer anyway? confirm" -- open item), target selector
      (picked from the bridge roster -- mesh peers are the only v1 source,
      plan §Auth), bridge consent banner (ungranted/CoreNotRunning per
      SYNC-003), tunnel-state line (from the bridge snapshot).
    - Progress: per-file phase from SYNC-002's TransferProgressEvent stream
      (files_done/total + per-file bytes), cancel affordance.
    - History: last run time + added/skipped/failed counts (SYNC-005) and
      last failure reason.
    - Settings: gate toggles + values (Wi-Fi-only, cellular, direct-only,
      charging-required, low-battery threshold), target config (mesh-IP
      picker is roster-based; module name; display name), delete-after-
      backup opt-in, coercion window N, WorkManager cadence, notification
      copy. Own-mesh-IP copy button for the rsyncd.conf enrollment edit
      (plan §IPC bridge, enrollment UX).
    - Notifications: channels + copy for gate reasons and completion/
      failure (coalesced per SYNC-004); exact copy is implementation-time
      (open item), reachable from Settings preview within Accessibility.

    ACCEPTANCE: `./gradlew :app:assembleDebug` + `testDebugUnitTest` pass;
    on the reference device, the home screen shows bridge state, a run can
    be started manually, progress renders, and every settings toggle
    roundtrips (persisted, reflected in the next run's gate evaluation).
    ENFORCEMENT: UI compile/unit automated; the rest is manual live bar
    (MOBILE-* convention).
    """
    req_id = "SYNC-009"


class SyncHomeSideDeliverable(Requirement):
    """REQUIREMENT-ID: SYNC-010

    The home-side deliverable: config + setup docs + an optional install
    script -- NO custom receiver code of any kind (plan §Home side setup;
    decision #2: home side is a stock `rsync --daemon`).

    Dependencies: none -- parallelizable from the start.

    Scope:
    - Sample `rsyncd.conf` in this repo (e.g. `home/rsyncd.conf`): module
      pointing ONLY at the backup directory, `read only = no`, `hosts allow`
      keyed on the phone's mesh IP (decision #1 -- the mesh IP is
      deterministic per identity in core's `addressing.rs`; a subnet change
      invalidates entries, acceptable since the target is reconfigurable),
      running as a dedicated non-root user (defense in depth; a
      compromised allow-listed phone can then write only into the backup
      dir).
    - Setup section in this repo's README (provisional home; open item vs
      core tetron docs): install rsync, create the user/dir, drop the
      config, set `hosts allow` to the phone's mesh IP (from the sync
      app's copy button), restart the daemon, firewall note (port 873 /
      how to scope to the mesh subnet).
    - Optional `contrib/install.sh` following the tetron-backup.sh precedent
      (core tetron's contrib script): idempotent install of the config +
      user + module dir with path/env overrides, `--dry-run`, uninstall.
    - Explicit constraint: this requirement adds no receiver binary, no
      daemon, no protocol surface. If the receiver ever needs code, that is
      a new requirement, not an extension of this one.

    ACCEPTANCE: `sh -n` on any scripts; a fresh Ubuntu-24.04-style host
    follows the README (or runs the script) and `rsync --daemon` serves the
    module; an allow-listed mesh IP transfers and a non-allow-listed IP is
    rejected (`rsync` error, no file written).
    ENFORCEMENT: script lint + the manual follow-through above (a VM is
    enough; tetron-testsuite-style is out of scope here -- no core change).
    """
    req_id = "SYNC-010"


class SyncFinalDeviceVerification(Requirement):
    """REQUIREMENT-ID: SYNC-011

    Final device pass on the LG V40 reference device against the AORUS
    receiver (plan §Testing, including the cellular test added by USER's
    live correction 2026-08-18). Not a feature requirement -- the
    verification milestone that closes out the v1 slice.

    Dependencies: SYNC-005, SYNC-007, SYNC-009 (a working app with media
    access, delete opt-in, and UI).

    Scope:
    - Real Wi-Fi + real camera roll (seed via `adb push` test images of
      varied sizes + one large video to exercise `--partial`/resumability,
      then trigger a media scan), real rsyncd on AORUS with `hosts allow`.
    - Delete-after-backup consent flow end to end (SYNC-007's system
      dialog).
    - Cellular test: Wi-Fi OFF, run against the AORUS target -- expect
      Direct-or-deferred, never relay transfer (gate decision from the
      bridge's per-target ConnKind).
    - Consent flow: ungranted caller -> banner -> grant in main app ->
      snapshot flows (SYNC-003).
    - Interruption/resume on device: kill the app mid-run (Doze/OS kill),
      next run resumes via `--partial`.

    ACCEPTANCE: the above flows each pass on the device; results (with
    screenshots per tetron-mobile convention) recorded in this repo's
    DO-NOT-COMMIT.
    ENFORCEMENT: manual, the live bar -- same as every MOBILE-* verification
    milestone; nothing automated that a passing build could substitute for.
    """
    req_id = "SYNC-011"