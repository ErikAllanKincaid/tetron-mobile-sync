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
| 1 | Receiver authorization | Explicit per-identity allow-list, deny-by-default. Enforced as the `hosts allow` / `hosts deny = *` pair in the rsyncd.conf that `tetron-sync-receiver` generates: `allow add-peer <hostname>` resolves the mesh IP from the receiver host's own tetron IPC roster, `allow add <ip>` for the raw case. Re-confirmed 2026-08-31 over the PLAN receiver-layout brainstorm, which had floated dropping it. |
| 2 | Transfer mechanism | oc-rsync embedded, stock rsyncd home side; spike-verified 2026-08-19 (`--partial` resume, wire compat, Android build, progress API). Amended 2026-08-31: the home-side stock `rsync --daemon` is provisioned by `tetron-sync-receiver` (MPL-2.0, separate repo) -- a config generator + per-user service supervisor around the system's own rsync, no receiver protocol code, no GPL contact. See SYNC-010. |
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

## Amendments

- 2026-08-31 (PLAN_tetron-mobile-sync_receiver-layout-and-target-selection):
  the receiver-layout brainstorm's PART 1 was resolved. Decisions #1 and #2
  amended in place above. SYNC-010 rewritten: the home side is the separate
  `tetron-sync-receiver` project + optional tetron-webui "Sync Receiver"
  addon, not a sample rsyncd.conf shipped from this repo. Per-device
  isolation is a single module plus a client-chosen `<device-label>/` top
  path component (`--mkpath`), never module-per-device. The own-mesh-IP
  copy button (SYNC-009) is removed -- the receiver allow-lists a phone by
  hostname from its own roster. PART 2 (manual IP entry, decision #9) was
  NOT decided and stays open.

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
- RESOLVED 2026-08-31: the home side is the `tetron-sync-receiver` project
  (own repo, own README) plus the tetron-webui "Sync Receiver" addon; this
  repo's README links to it and does not carry setup steps of its own.
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
      NotJoined / CoreNotRunning), own mesh IP (informational only -- not
      an enrollment step: the receiver allow-lists this phone by hostname
      from its own mesh roster, SYNC-010), peer roster as candidate targets
      with per-peer ConnKind. The roster is
      the only place mesh peers are ever discovered -- the user never types
      a mesh IP into v1's target settings (decision #9, plan §Auth).
    - Handling of a dead/absent main app: provider answers CoreNotRunning or
      ContentProviderNotFound -> bridge reports `unavailable`, UI shows
      "tetron-mobile not running / access not granted".

    Wire compat (settled 2026-08-19): the snapshot crosses the Binder as a
    Parcelable class that lives only inside tetron-mobile's APK, so the
    consumer can never load it; `Bundle.getParcelable` on it throws
    `BadParcelableException`. The standard cross-app technique applies: this
    app ships hand-written parcel-layout mirror classes under the same
    fully-qualified names (`xyz.tetron.mobile.StatusSnapshot`,
    `xyz.tetron.mobile.BridgePeer`, `xyz.tetron.mobile.BridgeTunnelState`) --
    fresh GPL code carrying exactly the layout verified from the provider's
    compiled `CREATOR` bytecode (state=enum-name string, then network,
    ownMeshIp, subnet as strings, peer count int + per-peer
    (hostname, ip, connKind int), updatedAtMillis long), plus a public
    static `CREATOR`/companion so Parcel's `Class.forName` + reflective
    `getField("CREATOR")` path finds them. The mirrors are wire DTOs with no
    logic and are never the type callers see; the app's own typed models
    (`xyz.tetron.sync.bridge`: BridgeSnapshot, BridgePeer,
    BridgeTunnelState, ConnKind) are the only surface consumed by UI and
    gates. Parsing is never-crash defensive: unknown tunnel-state name ->
    app `Unknown`; unknown ConnKind int -> Unknown; malformed/negative peer
    count -> empty list. The response algebra is a sealed `BridgeResponse`:
    `Snapshot(BridgeSnapshot)`, `ConsentRequired(callerPackage)`, or
    `Unavailable` (dead app / missing provider / SecurityException /
    BadParcelableException -- the bridge never throws to UI). `MeshBridge`
    wraps the caller with a short TTL cache (5s default) so UI polling does
    not hammer the provider; every response kind is cached for the TTL. The
    parcel layout is MOBILE-024's contract: any provider-side change must
    keep `MeshStatusProviderContractTest` green and this mirror updated in
    lockstep.

    ACCEPTANCE: JVM unit tests cover parsing of every `BridgeTunnelState`,
    the ConnKind int mapping (including defensive fallbacks), the
    consent-required branch, and the TTL cache (fake caller, no Android
    deps); instrumented test against the real installed tetron-mobile on
    the LG V40 covers the cross-process parcel round-trip (mirror-class
    CREATOR resolution) plus grant-then-snapshot on a real device;
    `./gradlew :app:testDebugUnitTest` + any instrumented test pass.
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
    - Destination path (amended 2026-08-31, SYNC-010): the client writes
      into `rsync://<ip>:<port>/<module>/<device-label>/...`, i.e. the
      configured module root plus a per-device top path component, so one
      receiver module holds every device. `<device-label>` is a SYNC-009
      setting (user-editable, stable first-run-UUID fallback); the run
      passes `--mkpath` so rsync creates that component under the module
      root on first use -- the receiver never pre-seeds it and needs no
      per-device config. `--files-from` entries are staged as
      MediaStore-relative paths (`DCIM/Camera/<name>`), not bare
      filenames, so the receiver tree mirrors the phone's.
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

    **IMPLEMENTED as of 2026-08-20** (`xyz.tetron.sync.delete`, no branch
    cut yet). The pipeline's own per-file completion stream (SYNC-002's
    `SyncProgressEvent`, pushed through the `SyncProgressListener` UniFFI
    callback) turned out not to distinguish "this event is a byte-verified
    regular-file transfer completing" from a mid-transfer tick, a
    directory/symlink/hardlink action, or an already-present skip -- all of
    which also emit progress events (`ClientEventKind::is_progress()`).
    `src/lib.rs` fixes this at the FFI boundary rather than pushing the
    distinction into Kotlin: `SyncProgressEvent` gained `is_transfer`
    (mirrors `ClientEventKind::is_transfer()` -- true only for
    `DataCopied`/`ReferenceCopied`) and `is_final` (mirrors
    `ClientProgressUpdate::is_final()` -- true only on an event's
    completion tick, not an in-flight one); Kotlin bindings + `jniLibs`
    regenerated the same two-command way as SYNC-005/006's stale-bindings
    fix noted above. `TransferredFileCollector` (a `SyncProgressListener`
    that tees any caller-supplied listener) filters on
    `isTransfer && isFinal` to build the transferred-this-run path set;
    `resolveDeleteSet(DeleteAfterBackupConfig, List<String>)` is the pure
    opt-in gate (`enabled=false` -> always empty, matching decision #8's
    default OFF) so it is testable with no transfer or Android permission
    surface at all. `SyncPipeline` wires both in: `deleteConfig`/
    `deletionRequester` constructor params (both default to an inert no-op,
    so every pre-existing SYNC-005/006 caller and test is unaffected),
    collector interposed on the progress listener passed to
    `TransferRunner`, delete requested only from the try-succeeded branch
    -- resolving the spec's "gated on the run's success" as *not* excluding
    an interrupted run (rsync exit 23/24 is explicitly not a failure,
    spec/sync.py SYNC-005, and whatever it did copy is still byte-verified)
    but excluding a hard engine exception (no reliable per-file record at
    that point). `DeletionRequester` is a contract only (`fun interface
    DeletionRequester { fun requestDelete(paths: List<String>) }`), same
    seam pattern as `TargetProvider`/`SourcePathProvider`: both the API 33+
    `MediaStore.createDeleteRequest` confirm dialog and the pre-33
    app-level confirm need an `Activity` to launch, which this pure-Kotlin
    layer does not have, and SYNC-009's own dependency list already names
    "SYNC-007 (delete opt-in UI)" as its real home. 17 new JVM unit tests
    (`DeleteModelsTest` + 6 new cases in `SyncPipelineTest`) cover: the
    opt-in gate, collector filtering (mid-transfer tick / non-transfer
    action / no-path summary line all excluded), delete-set-equals-
    transferred-set end to end through a faked `TransferRunner`, disabled
    opt-in never invoking the requester even when files transferred, a
    hard failure never invoking it, an interrupted run still invoking it,
    and a caller-supplied progress listener still receiving every event
    unchanged (the tee does not swallow anything).
    `:app:assembleDebug` + `:app:testDebugUnitTest` +
    `:app:compileDebugAndroidTestKotlin` + `python3 reconcile.py` (cargo
    build/clippy/test/audit + `libspec diff`) all green. Still open, and
    explicitly deferred to SYNC-009 as scoped above: the real
    `MediaStoreDeletionRequester` (path -> content URI resolution, the API
    33+ confirm dialog, the pre-33 app-level confirm) and the settings UI
    toggle for `DeleteAfterBackupConfig.enabled`.
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

    **IMPLEMENTED as of 2026-08-20** (`xyz.tetron.sync.media`, no branch cut
    yet). `resolveMediaAccessGrant(MediaPermissionState, apiLevel): MediaAccessGrant`
    is the pure classifier (no Android dependency, same bar as
    `GateEvaluator`): API 33+ needs `READ_MEDIA_IMAGES` AND
    `READ_MEDIA_VIDEO` together for `Full` (a device offers both as one OS
    choice, so "images granted, video not" is not modelled as a fourth
    state); API 34+ additionally maps `READ_MEDIA_VISUAL_USER_SELECTED`
    alone to `Partial`; that permission does not exist pre-34, so it is
    never consulted below that level even if a caller's state sets it
    (`api33_visualUserSelected_doesNotExistYet_isNotGranted` pins this);
    below API 33 only `READ_EXTERNAL_STORAGE` matters. `resolveSourcePath
    (MediaAccessGrant, File): String?` resolves the injected camera-roll
    directory's path for `Full` OR `Partial` (a partial grant still backs
    up whatever subset was selected -- it does not block the run outright,
    matching the spec's "only selected photos will back up" framing, not
    "unavailable") and `null` for `NotGranted` or a missing directory (both
    surface through `SyncPipeline` as `GateReason.TargetUnreachable` via
    the existing `SourcePathProvider` contract, exactly the "not a crash"
    bar SYNC-005 already established). `resolveMediaAccessState` bundles
    both into one `MediaAccessState(grant, sourcePath)` with a
    `showPartialAccessWarning` derived property for SYNC-009's banner.
    `AndroidMediaAccess` is the production `SourcePathProvider` (real
    `ContextCompat.checkSelfPermission` reads + `Environment
    .getExternalStoragePublicDirectory(DIRECTORY_DCIM)/Camera`) -- no
    logic of its own, so no unit test, same bar as
    `AndroidDeviceStateProvider`/`EngineTransferRunner`. Requesting the
    runtime grant itself needs an `Activity`
    (`ActivityResultContracts.RequestMultiplePermissions`, "first-run
    setup / Backup-press time" per this requirement's own scope), which
    this class does not have -- same seam split as SYNC-007's
    `DeletionRequester`; SYNC-009 owns the request flow, this class only
    reads whatever grant already exists. `AndroidManifest.xml` gained
    `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`/
    `READ_MEDIA_VISUAL_USER_SELECTED` plus `READ_EXTERNAL_STORAGE`
    `maxSdkVersion="32"` (the last permission caused an XML-comment parse
    failure the first pass -- a `--` inside a `<!-- -->` block is invalid
    XML, not just a style nit; caught by `processDebugMainManifest`
    rejecting the manifest outright). 16 new JVM unit tests
    (`MediaAccessTest`) cover the full grant matrix per API-level band plus
    path resolution/warning-banner derivation. `:app:assembleDebug` +
    `:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin` +
    `python3 reconcile.py` all green. Still open, deferred to SYNC-009 as
    scoped above: the runtime permission request flow itself and the
    partial-access warning banner UI.

    **Bug found + fixed 2026-08-20, during SYNC-011 device verification:**
    on the real LG V40 (API 29), a run against the real `DCIM/Camera`
    (705 real files) transferred exactly 1 file. Root cause: Android
    Scoped Storage filters raw directory *enumeration* (`readdir`, which
    oc-rsync's `jwalk`-based recursive walk uses) per-app-UID via a FUSE
    daemon on API 29+ -- the app's own UID sees almost nothing via a raw
    walk of `DCIM/Camera` even with `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`
    granted (that grant covers MediaStore `content://` access, not raw
    path enumeration). Root-caused via `tracing` instrumentation temporarily
    added to the vendored oc-rsync fork (`files_listed=1` at the exact
    point the daemon-transfer stats were computed) plus a packet capture
    showing the client closing the connection right where bulk data should
    start. Confirmed via `android:requestLegacyExternalStorage="true"`
    (API 29-only diagnostic, not a real fix): with it, the same run saw
    and transferred hundreds of files. Further confirmed opening a
    *known* filename directly (no enumeration) works fine even with the
    flag off, by staging a hand-written oc-rsync `--files-from` list of a
    few real filenames -- all transferred successfully under strict
    Scoped Storage. This decoupled the fix into two well-understood parts
    (enumerate without `readdir`; open-by-known-path already works) instead
    of needing a MediaStore-URI/FD-based read path (a much larger change to
    oc-rsync's core, which assumes direct filesystem access throughout).

    **Fix:** `AndroidMediaAccess.resolve()` (the real `SourcePathProvider`)
    now returns a `SourceSpec(rootPath, filesFromPath)` instead of a bare
    path string. On API 29+, `filesFromPath` is populated by querying
    `MediaStore.Files` (`RELATIVE_PATH = 'DCIM/Camera/'`, `MEDIA_TYPE` in
    image/video) -- which is never subject to the enumeration filter, since
    enumerating is MediaStore's entire purpose -- and staging the resulting
    filenames as a newline-delimited list in app-private storage. Pre-29
    devices (no Scoped Storage restriction) get `filesFromPath = null`,
    unchanged recursive-walk behavior. `SyncRunOptions` (the UniFFI record)
    gained a `files_from_path: Option<String>` field, wired in
    `SyncEngine::run_client` to `ClientConfig::builder().files_from
    (FilesFromSource::LocalFile(path))` -- oc-rsync-core's own `--files-from`
    support (`vendor/oc-rsync/crates/core/.../files_from.rs`), unmodified.
    Verified on-device end to end with the diagnostics fully removed: a
    fresh run against the real camera roll transferred 701 real files/2.7GB
    cleanly (the 4-file gap versus a raw `find` count of 705 is MediaStore
    correctly excluding non-image/video entries a blind walk would have
    counted). All temporary `tracing`/`android_log-sys` diagnostic
    dependencies and instrumentation added during this investigation
    (crate deps, `init_diagnostic_tracing`, `AndroidLogWriter`, both
    `tracing::error!` call sites including the one temporarily added to
    the vendored fork) were removed once root-caused -- USER's call to
    keep the crate's dependency surface minimal rather than carry
    permanent logcat plumbing.

    **Bug found + fixed 2026-08-21, during the SYNC-011 interrupt/resume
    device pass:** every real transfer against the real camera roll --
    including several that completed with zero data loss and a clean
    daemon-side finish -- was recorded as "Interrupted -- will resume next
    run". Root cause: `MediaStore`'s index on a real, lived-in gallery can
    go stale (rows for files deleted through some path that never
    triggered a rescan). oc-rsync correctly `link_stat`s each `--files-from`
    entry, logs an `ErrorXfer` diagnostic for the handful that no longer
    exist, and continues transferring everything else (matching real
    `rsync`'s own behavior for a named-but-missing `--files-from` entry) --
    but that still sets the run's exit code to 23 ("partial transfer due
    to error"), which `SyncPipeline.kt`'s `interrupted = outcome
    .ioErrorExitCode != null` treated identically to a genuine
    interruption. Confirmed via a throwaway diagnostic (a raw file write
    at the exact point `oc-rsync`'s `transfer` crate sets `got_xfer_error`,
    not committed, reverted immediately after use -- routing that crate's
    internal messages through `tracing` would need extra Cargo feature
    wiring the `core` crate's own `tracing` feature does not propagate
    down to `transfer`, so a raw file write was the faster path this time):
    `ErrorXfer: rsync: [sender] link_stat "<path>" failed: No such file or
    directory (2)`, for a handful of filenames independently confirmed
    absent from disk via `adb shell ls` on the literal path.

    **Fix:** `AndroidMediaAccess.writeFilesFromList` now checks
    `File(rootDir, name).exists()` for each `MediaStore` row before writing
    it into the `--files-from` list, so a stale index entry is never handed
    to the engine at all -- `File.exists()` is a single stat on a *known*
    path, not a directory listing, so per this same requirement's Scoped
    Storage finding above it is not subject to the enumeration filter,
    making the check safe on API 29+. No Rust/UniFFI changes needed: this
    closes the gap entirely on the Kotlin side by never asking oc-rsync to
    fetch something already known to be gone, rather than trying to
    reclassify its exit code after the fact (exit 23 is also what a genuine
    mid-run interruption produces, so the two are not reliably
    distinguishable from the exit code alone). Verified on-device with a
    full clean-slate run against the entire real camera roll (701 files)
    with the diagnostic still active: zero `ErrorXfer` events, clean daemon
    completion, History correctly shows "701 added, 0 skipped, 0 failed"
    with no "Interrupted" label. Diagnostic reverted afterward, same as
    Bug #2's.

    **Bug found + fixed 2026-08-21, while device-testing TODO #8's Cancel
    button:** real "Back up now" runs against the real camera roll began
    failing in well under a second (`"Interrupted -- 0 added, 701
    skipped"`), reproducibly. Root-caused via a temporary JVM-level probe
    (`FileInputStream(File(rootPath, name)).read()`, reverted after
    diagnosis): every open failed `EACCES`, even though `File.exists()`
    (a stat, not an open) succeeded on the same path -- true for both
    WhatsApp-saved and genuine camera-shot files alike, and true even
    with the permission granted through the app's own real "Grant photo
    access" button and the system consent dialog (not just `pm grant`).
    This narrows the finding above: a known-filename open bypasses the
    Scoped Storage *enumeration* filter, but evidently does not bypass a
    raw *read* on this device's current OS state. **Fix:**
    `android:requestLegacyExternalStorage="true"` on `<application>`
    (`AndroidManifest.xml`) -- API 29-only, a no-op on API 30+, so it
    changes nothing for any device beyond the one this matters for.
    Verified with a full clean-slate 701-file/2.7GB run (zero errors),
    then a cancelled-mid-flight run (TODO #8) showing correctly in
    History, then a further clean run proving the cancellation state
    resets properly. See AGENTS.md's 2026-08-21 entry for the full
    root-cause chain, including how network/mesh and the Cancel button's
    own new code were both ruled out first.
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
      picker is roster-based; module name; port; device label -- the
      per-device top path component on the receiver, user-editable, with a
      stable first-run-UUID fallback, SYNC-010), delete-after-backup
      opt-in, coercion window N, WorkManager cadence, notification copy.
      (No own-mesh-IP copy button -- removed 2026-08-31; the receiver
      allow-lists this phone by hostname from its own roster, SYNC-010.)
    - Notifications: channels + copy for gate reasons and completion/
      failure (coalesced per SYNC-004); exact copy is implementation-time
      (open item), reachable from Settings preview within Accessibility.

    ACCEPTANCE: `./gradlew :app:assembleDebug` + `testDebugUnitTest` pass;
    on the reference device, the home screen shows bridge state, a run can
    be started manually, progress renders, and every settings toggle
    roundtrips (persisted, reflected in the next run's gate evaluation).
    ENFORCEMENT: UI compile/unit automated; the rest is manual live bar
    (MOBILE-* convention).

    **IMPLEMENTED as of 2026-08-20** (`xyz.tetron.sync.ui` +
    `xyz.tetron.sync.{settings,notifications}`, no branch cut yet, landed
    as twelve incremental commits). This requirement's own open item
    ("Back up now" behavior when gated) was resolved first via USER
    decision: respect the gate, offer a "Transfer anyway?" confirm that
    relaxes only the one gate that blocked. That turned out to need new
    pipeline-layer plumbing, not just UI: `GateReason ->
    GateConfig?` (`relaxedGateConfig`, `xyz.tetron.sync.gates.GateModels
    .kt`) maps each overridable reason to the single config knob it
    relaxes -- `TunnelNotActive`/`TargetUnreachable` map to `null` (no
    knob exists, never overridable) -- and `SyncPipeline.run` gained an
    `overrideReason: GateReason?` parameter that re-evaluates the AND-
    matrix with that one knob relaxed only when it names the exact reason
    that actually blocked this cycle (a stale override across a
    device-state change in between is rejected, not silently honoured).

    Two more `SyncPipeline` changes turned out to be prerequisites, not UI
    work: `gateConfig`/`deleteConfig` moved from fixed constructor values
    to `() -> X` suppliers (matching the `nowMillis` pattern already in the
    class) -- `AppContainer` builds `pipeline` once, long before any
    Settings value exists, so a fixed value would have frozen whatever was
    true at construction and no Settings toggle could ever reach a running
    pipeline. And `onRunCompleted: (RunRecord) -> Unit` is new: SYNC-004's
    `onNotify` gate hook had existed since that requirement with no
    listener, and completion/failure notifications had no hook to fire
    from at all.

    `xyz.tetron.sync.AppContainer` is the hand-rolled composition root (no
    DI framework -- this repo's own minimal-dependency convention) that
    finally gives every contract-only seam SYNC-002..008 built a real
    implementation: `MeshBridge`/`ProviderStatusCaller`, the real
    `SyncPipeline` with settings-backed `TargetProvider`/`gateConfig`/
    `deleteConfig`, `AndroidMediaAccess` as `SourcePathProvider`,
    `SharedPreferencesRunHistoryStore` (the SYNC-005 in-memory store was
    always "a usable default until then"), the real SYNC-006 trigger
    wiring (`SyncWorkerFactory` via `Configuration.Provider`,
    `AndroidNetworkChangeTrigger` registered for the process lifetime in
    `TetronSyncApplication.onCreate`), `SyncNotifier`, and
    `MediaStoreDeletionRequester`. `TetronSyncApplication` also removes
    WorkManager's default `androidx-startup` initializer from the manifest
    (`tools:node="remove"` on its `WorkManagerInitializer` meta-data) --
    its own reflective factory can not carry `PipelineRunner` into
    `SyncWorker`, so leaving it in place would silently mean the real
    factory never won.

    `xyz.tetron.sync.settings.SettingsStore` (`SharedPreferences`-backed)
    persists gate config, target, delete-after-backup, WorkManager cadence,
    and the notification coalescing window (new:
    `GateNotificationCoalescer`'s ~6h default from SYNC-004, now a
    setting). Cadence and the coalescing window both take effect next app
    launch, not live like `gateConfig`/`deleteConfig` -- they size a
    WorkManager job and construct a stateful per-reason timer respectively,
    both read once at `AppContainer` construction.

    Four screens, `androidx.navigation-compose` bottom nav, `HomeViewModel`
    requested Activity-scoped (`viewModel(activity, factory = ...)`) so
    Home and Progress observe the exact same in-flight `RunPhase` -- a run
    started from Home must be visible on Progress without either screen
    owning the other.

    **Cancel button IMPLEMENTED as of 2026-08-21** (TODO #8, follow-up to
    this requirement's own "left as a follow-up" note above -- the
    original text is kept above unedited for history). `SyncEngine
    .run_client` is still fully synchronous/blocking, but a new
    `SyncEngine.cancel(&self)` (src/lib.rs) needs no per-call cancellation
    token: it reuses the vendored fork's OWN Ctrl+C plumbing verbatim
    (`fast_io::signal::mark_shutdown` + `oc_rsync_core::signal
    ::request_shutdown(ShutdownReason::UserRequested)`, the exact two
    calls `vendor/oc-rsync/crates/core/src/signal/unix.rs`'s real
    SIGINT/SIGTERM/SIGHUP handlers make), both process-global flags the
    generator/receiver transfer loops and the local-copy loop already poll
    at file boundaries on every `run_client_with_observer` call regardless
    of transfer direction -- zero changes to the vendored fork itself,
    since the checkpoints already existed and cover the transfer
    unconditionally. `run_client` resets both flags before it starts
    (`fast_io::signal::reset_shutdown_for_testing` / `oc_rsync_core::signal
    ::reset_for_testing` -- named for testing upstream since the CLI
    binary just exits after a real signal instead of reusing the process,
    but both are plain `pub fn`, and reuse here is deliberate, documented
    at the call site, not a workaround); this is correct because
    `SyncPipeline`'s own `AtomicBoolean` reentrancy guard already
    guarantees only one `run_client` is ever in flight per process. A
    cancelled run surfaces exactly like a real Ctrl+C: `SyncError::Engine
    {exit_code: 20, ..}` (`RERR_SIGNAL`, already mapped by the fork's own
    `ErrorKind::Interrupted -> Signal` in `exit_code/codes.rs`, unchanged).
    `SyncPipeline.kt`'s catch branch reads exit code 20 as a new
    `RunRecord.cancelled` flag distinct from `failed` (both arrive via the
    same thrown `SyncException`, but a user-requested stop is not a
    fault); Home shows a "Cancel" button only while `RunPhase.Running`,
    wired through `AppContainer.syncEngine` (now held directly, not just
    inside `EngineTransferRunner`, so `HomeViewModel.cancel()` can reach
    the same engine instance a run is in flight on -- though since
    cancellation is process-global, any instance would do). New JVM test:
    `SyncPipelineTest.cancelledTransfer_isNotRecordedAsFailure`.
    `:app:testDebugUnitTest` + `:app:assembleDebug` + `reconcile.py` all
    green; UniFFI bindings + jniLibs regenerated for the new `cancel()`
    surface.

    Settings covers every item in this
    requirement's own scope list: gate toggles, the roster-based target
    picker (`ExposedDropdownMenuBox` -- Material3 1.3.1's real API needed
    two fixes over the first draft: `ExposedDropdownMenu` is a member of
    `ExposedDropdownMenuBoxScope`, not a top-level composable, and
    `Modifier.weight()` is `RowScope`'s extension, not the internal
    `androidx.compose.foundation.layout.weight` val an IDE-style import
    guess pulled in by mistake), delete-after-backup opt-in, WorkManager
    cadence, the coalescing window, and (as first built) an own-mesh-IP
    copy button (`LocalClipboardManager`) for `rsyncd.conf`'s `hosts allow`
    line.

    **Copy button removed 2026-08-31** (PLAN receiver-layout, Conflict 4):
    the home side is `tetron-sync-receiver`, which allow-lists a phone by
    hostname resolved from its own tetron IPC roster (`allow add-peer`), so
    the phone never needs to surface or copy its mesh IP. `LocalClipboard
    Manager` and the button drop out; the device-label field (SYNC-010)
    takes its place in target config.

    `TetronSyncTheme` (`xyz.tetron.sync.ui.Theme.kt`) matches
    tetron-mobile's own green brand palette (same hex values as
    `xyz.tetron.mobile.ui.Theme.kt`'s `DarkColorScheme`/`LightColorScheme`)
    rather than Material3's purple default, per USER feedback on the first
    screenshots -- fresh code, not shared/copied (tetron-mobile is
    proprietary, this repo is GPL-3.0, AGENTS.md: "use them as design
    reference only"), since color values are not original expression.
    Material3's scheme builders default every *unspecified* slot to the
    library's own baseline palette rather than deriving it from `primary`,
    so the first pass (only `primary`/`background`/`surface` set) still
    left the bottom nav bar's selected-item pill and container purple;
    the fix sets `secondary`/`secondaryContainer`/`surfaceContainer`/etc.
    explicitly too.

    SYNC-008's deferred runtime permission request and SYNC-007's deferred
    real `DeletionRequester` both close out here, both via the same
    Activity-owned-launcher seam split already established by this app's
    architecture: `MainActivity.onCreate` (before `ComponentActivity`
    reaches `STARTED`, which `registerForActivityResult` requires) registers
    `ActivityResultContracts.RequestMultiplePermissions` for media access
    (trigger threaded down to Home's "Grant photo access" banner button,
    shown for `MediaAccessGrant.NotGranted`; `Partial` gets a warning
    banner with no action, since re-requesting an already-partial grant is
    not reliably a "show me full access" affordance across OEMs) and
    `ActivityResultContracts.StartIntentSenderForResult` for
    `MediaStore.createDeleteRequest`'s system confirm dialog.
    `MediaStoreDeletionRequester` resolves each transfer-relative path to
    its MediaStore content `Uri` by querying `MediaStore.Files` on `DATA =
    ?`, gated at API 33+ to match this app's other media API boundary
    (spec/sync.py SYNC-007's own "Android 13+" framing) even though the
    underlying platform call has existed since API 30; below 33 it is a
    deliberate no-op rather than a half-correct direct-file-delete path
    (needs `WRITE_EXTERNAL_STORAGE`, never requested, and behaves
    inconsistently under API 29-32 scoped storage in ways unverifiable
    without a real low-API device). `AppContainer.deleteIntentSenderLauncher`
    is a mutable `var` `MainActivity` sets once its launcher exists -- the
    requester itself is built once, process-scoped, before any Activity
    exists, so this indirection is what lets it forward through without
    making `SyncPipeline.deletionRequester` itself mutable.

    `SyncNotifier` posts to two channels ("Backup paused" / "Backup
    results"); `POST_NOTIFICATIONS` (API 33+) is requested unconditionally
    at first launch, unlike media access's button-triggered flow, since
    there is no single natural in-app action to hang it on and a
    background-triggered run has no UI moment to request it from at all --
    a deliberate exception to this app's own "not eagerly" precedent
    (SYNC-008), not an oversight. `NotificationManagerCompat.notify` throws
    `SecurityException` without that grant rather than silently no-op-ing,
    so `SyncNotifier` catches it -- a missing notification permission must
    never take down the pipeline thread that triggered it.

    Verification: `:app:assembleDebug` + `:app:testDebugUnitTest` +
    `:app:compileDebugAndroidTestKotlin` + `:app:connectedDebugAndroidTest`
    (all three instrumented suites: `SyncSmokeTest`, `MeshBridgeDeviceTest`
    -- 2 of 5 cases skipped, both need a real tetron-mobile provider,
    documented since SYNC-003 -- and `SyncWorkSchedulerDeviceTest`) +
    `python3 reconcile.py` all green on a headless `tetron_api29` (API 29)
    emulator, plus live manual verification of every major flow via adb
    screenshots at each commit: the gate/override banner+dialog, all four
    screens rendering and navigating, a settings toggle round-tripping on
    screen, the real system media-permission dialog firing and the banner
    clearing on grant, and `dumpsys notification` confirming both channels
    exist and a real gated-cycle notification posts. Still open, and
    requiring the API 33+ LG V40 reference device rather than this
    environment: the real `MediaStore.createDeleteRequest` confirm-and-
    delete flow end to end (SYNC-007's own ENFORCEMENT bar already called
    this manual), and the partial-access-grant banner on a real API 34+
    device. SYNC-011 (final device verification) is the next requirement
    with a hard dependency on this one; SYNC-010 (home-side deliverable)
    remains independently parallelizable and still not started.
    """
    req_id = "SYNC-009"


class SyncHomeSideDeliverable(Requirement):
    """REQUIREMENT-ID: SYNC-010

    The home-side deliverable. **Rewritten 2026-08-31** (PLAN receiver-
    layout, PART 1): the original plan -- a sample `rsyncd.conf` + README
    steps + optional `contrib/install.sh` shipped from THIS repo -- was
    superseded by a real product that already exists:

    - `tetron-sync-receiver` (`~/code/tetron-sync-receiver`, MPL-2.0, own
      repo): a small CLI-first binary that keeps a generated `rsyncd.conf`
      in lockstep with a JSON state file and supervises the system's own
      stock `rsync --daemon` as a per-user service (`systemd --user` /
      launchd LaunchAgent). It embeds no GPL and no phone-app code -- it
      only writes a plain daemon config and runs the OS's rsync -- so
      decision #2's "zero receiver code" still holds: the wire peer is
      stock rsyncd, and the manager is a separately-licensed sibling
      program, not code in this repo.
    - tetron-webui "Sync Receiver" addon (`tetron-webui/src/sync_receiver
      .rs`): a thin point-and-click shell over that binary's `--json` CLI.
      Optional; the CLI is fully usable alone.

    So this repo's SYNC-010 deliverable shrinks to: a README section that
    points at `tetron-sync-receiver` (install one command, or via the
    tetron suite installer) and states the contract below. No config file,
    no script, no per-repo receiver docs.

    Dependencies: none for the pointer docs. The client-side path change
    (below) sits on SYNC-002 (adds `--mkpath` to `SyncRunOptions`) and
    SYNC-005 (destination construction, `--files-from` staging).

    Operator model (the entire home side):
    1. `tetron-sync-receiver allow add-peer <phone-hostname>` (or
       `allow add <ip>`) -- deny-by-default; nothing connects until this.
    2. `tetron-sync-receiver module add <name> <backup-root-dir>` -- a
       module is just name -> path; runs as the operator, files owned by
       the operator, `use chroot = false`.
    That is it. Per-device isolation is NOT a receiver concern.

    Cross-repo wire contract (must stay true in all three repos):
    - The app writes to `rsync://<ip>:<port>/<module>/<device-label>/...`.
      The receiver knows only `<module> -> <path>`. `<device-label>/` and
      everything below it are created by the client (`--mkpath`, implied
      dirs). One module serves every device; nobody adds module-per-device.
    - Port default 28873 in all three (app `DEFAULT_TARGET_PORT`, receiver
      `config::DEFAULT_PORT`, webui passes it through). A change is a
      coordinated change.
    - `tetron-sync-receiver`'s generated module block MUST carry
      `write only = true` and `max connections = 1` (tracked change in
      that repo's `config::write_rsyncd_conf` -- see the PLAN's cross-repo
      task list; this app's acceptance below depends on it).

    Client-side changes in THIS repo (see PLAN for the step list):
    - `SyncRunOptions` gains `mkpath: bool`; `build_client_config` wires it
      via `ClientConfig::builder().mkpath(true)` (fork already supports it
      and honors it for daemon sends -- `vendor/oc-rsync/crates/core/src/
      client/remote/invocation/builder.rs`; no fork patch). UniFFI regen.
    - `SyncTarget` gains `deviceLabel`; SYNC-009 Settings field + validator
      (one safe path component: no `/`, `..`, leading `.`, empty) + stable
      first-run-UUID fallback; a label edit warns (starts a new receiver
      dir, orphans the old).
    - `SyncPipeline` appends `/<deviceLabel>` to the destination and sets
      `mkpath = true`.
    - `AndroidMediaAccess` stages `--files-from` entries as
      `DCIM/Camera/<name>` and sets the rsync source root to the external-
      storage root, so the receiver tree mirrors the phone's MediaStore
      layout instead of landing flat in the module root.

    ACCEPTANCE:
    - Docs: this repo's README links to `tetron-sync-receiver` and states
      the wire contract; no setup steps duplicated here.
    - Wire behavior, `tests/engine_rsyncd.rs` against a real rsyncd: a push
      with `--mkpath` into `<module>/<new-label>/DCIM/Camera/` creates the
      full path with no pre-seeding; a client readback of a `write only`
      module is refused; a second connection during a transfer is refused
      (`max connections = 1`); a push from an un-allow-listed address is
      refused before any file is written.
    - End to end, manual (a VM is enough), recorded in DO-NOT-COMMIT:
      install `tetron-sync-receiver` on a fresh Ubuntu-24.04-style host,
      `module add` + `allow add-peer`, `receiver status` shows it serving
      on 28873, the phone runs a backup, files land under
      `<root>/<device-label>/DCIM/Camera/...` mirroring the phone.
    ENFORCEMENT: the `engine_rsyncd` cases are the automated gate for the
    wire contract; the install-and-transfer walk is manual, same bar as
    every SYNC-011-style milestone. The `write only` / `max connections`
    lines landing in `tetron-sync-receiver` is a cross-repo dependency,
    tracked in the PLAN, not something a green build here can substitute
    for.
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

    **Verification pass 2026-08-28** (the API-29 reference device against
    a `tetron-sync-receiver` over the tetron mesh; `main` at `cd0378c` =
    SYNC-012 merged, plus the History-persistence fix in the same
    changeset as this note). Full writeup + screenshots live in an
    untracked `DO-NOT-COMMIT/` working folder.
    - PASS: consent flow (ungranted -> `mesh-bridge-consent` notification
      -> MOBILE-024 `GrantActivity` Allow -> "Mesh connected" + roster);
      media permission via the real system dialog; full backup (17 real
      files, byte-identical, clean daemon completion, History "17 added,
      0 skipped, 0 failed" -- 5 genuinely-stale `MediaStore` rows dropped
      by the SYNC-008 `File.exists()` guard, no false "Interrupted");
      idempotent re-run (0 added / 17 skipped, file-list walk only);
      interrupt/resume (`am force-stop` mid-transfer -> partial moved to
      `.tetron-partial/` per SYNC-012's `--partial-dir` -> resume
      transfers only the remainder, not a restart -> byte-identical ->
      partial-dir cleaned up); the SYNC-012 scoped-run + Preview pass
      (see the SYNC-012 docstring).
    - NOT TESTABLE on the API-29 reference device, deferred (same as the
      2026-08-20 handoff): delete-after-backup consent (the
      `MediaStoreDeletionRequester` is API-33+-gated); the cellular
      Direct-or-deferred test (the device has no SIM, so Wi-Fi-off is
      fully offline, not "on cellular"). Both need an API-33+ device with
      an active SIM.
    """
    req_id = "SYNC-011"


class SyncFilterControls(Requirement):
    """REQUIREMENT-ID: SYNC-012

    User-facing backup-scope controls: per-filetype include toggles, a
    max-file-size cap, a bandwidth ceiling, one-tap presets, and a local
    backlog estimate with a Preview breakdown. Design record:
    `DO-NOT-COMMIT/IDEAS_tetron-mobile-sync_user_filter_controls_and_presets_2026-08-28.md`
    (shape settled 2026-08-28; decisions confirmed and this requirement cut
    2026-08-28).

    **IMPLEMENTED as of 2026-08-28** (`feat/sync-012-filter-controls`,
    no PR yet). Rust: `build_client_config` split out of
    `SyncEngine::run_client` with the fixed `--timeout 120` /
    `--contimeout 30` / `--partial-dir .tetron-partial` policy (item 7,
    no `SyncRunOptions` change -- decision A1). Kotlin scope model
    (`xyz.tetron.sync.scope`): `BackupScope`, `ScopeFilter`
    (MIME-first classify, `MediaTypeSets` a parameter for the v1.1
    editor), `ScopeDecision`, `Preset` + `scopeForPreset`/`presetOf`,
    and the pure `selectInScope` / `estimateBacklog` functions the
    `--files-from` builder and the Settings estimate share -- 53 JVM
    unit tests. `SettingsStore.backupScope()`/`setBackupScope()`
    (thin-adapter convention, no unit test). `AndroidMediaAccess`
    projection gains `MIME_TYPE`+`SIZE`; `resolve()` runs `selectInScope`
    and returns `SourceSpec.skippedOversizeCount`; new `backlogEstimate()`.
    `SyncPipeline` gains a `() -> BackupScope` supplier (only the
    bandwidth ceiling reaches the engine); `RunRecord.skippedOversize`
    (decision A5). `SettingsScreen` "What gets backed up" + "Advanced"
    sections (five toggles, size cap, preset dropdown, estimate card,
    Preview `ModalBottomSheet`) -- Compose, verified on-device per the
    SYNC-009 convention. `cargo test`, `:app:testDebugUnitTest`,
    `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin`,
    `python3 reconcile.py` all green.

    **On-device scoped-run + Preview pass DONE 2026-08-28** as part of
    SYNC-011: a scope of Raw OFF + 50 MB cap staged exactly the in-scope
    files (type + size both enforced in the `--files-from` builder), the
    excluded `.dng`/oversize videos never reached the receiver, and the
    Settings estimate line + Preview `ModalBottomSheet` (per-type "Will
    upload", "Skipped: Raw photos (off)" / "Over the size limit",
    "Largest included") rendered with real numbers. The bandwidth ceiling
    measurably throttled a transfer. **Bug found + fixed in the same
    changeset as this note:**
    `SharedPreferencesRunHistoryStore` (SYNC-009) predated
    `RunRecord.skippedOversize` (this requirement) and `cancelled`
    (TODO #8) and persisted neither, so a size-capped or cancelled run
    read back after process death as a plain clean run and the History
    screen showed no "N too large" line -- the exact silent-loss case
    decisions A5/B5 exist to prevent (`InMemoryRunHistoryStore`, used by
    `SyncPipelineTest`, carries every field, which is why the unit tests
    missed it). Fixed by moving the record <-> key/value mapping into a
    pure `RunRecordCodec` with a `RunRecordCodecTest` round-trip guard,
    and `HistoryScreen` now renders the oversize line; verified on-device
    through a force-stop + relaunch (see the SYNC-011 note).

    Still open: the v1.1 overflow-menu set editors (decision B1); the
    deferred real server dry-run.

    Dependencies: SYNC-008 (the `--files-from` builder in
    `AndroidMediaAccess` is the single enforcement chokepoint), SYNC-005
    (the pipeline threads the scope through as a supplier and records the
    oversize-skip count), SYNC-009 (the Settings UI hosts every control).
    No dependency on any new engine surface -- see decision A1.

    ## The architectural fact that shapes it

    On Android API 29+ the app does NOT let oc-rsync recursively walk
    `DCIM/Camera` -- Scoped Storage's FUSE layer filters raw directory
    enumeration per app UID (root-caused across three SYNC-008 device bugs).
    `AndroidMediaAccess` instead queries `MediaStore.Files` and stages a
    `--files-from` list. Consequence: on every modern device the selection
    lever is a `WHERE`-clause / row filter on that query, NOT rsync
    `--include`/`--exclude` rules. rsync filter rules would matter only on
    the pre-29 (API 26-28) recursive-walk fallback path -- which no
    reference device exercises (decision A2).

    ## Scope is persistent config, not a per-run filter

    There is ONE saved `BackupScope` (persisted in `SettingsStore`), and
    every trigger path -- manual "Back up now", the WorkManager job, the
    network-change trigger -- builds its `--files-from` list from that same
    scope through the one existing `AndroidMediaAccess` chokepoint. No code
    path runs oc-rsync against the raw `DCIM` tree. This closes the
    fail-open hole a per-run filter has: a run scoped to just `.jpg` would
    leave `.dng`/video absent at the destination, and a later wider run
    would then upload all of them at once. Files "come back" only when the
    user deliberately widens the scope, which correctly means "back these up
    from now on too".

    ## Controls (v1)

    1. Filetype include toggles, all default ON: JPEG, HEIC, Raw, Videos,
       plus an "Other files" catch-all (default ON). The named types are
       app-maintained extension/MIME sets, not raw Android `MEDIA_TYPE`
       values (the OS types `.jpg`, `.heic`, `.dng` all as `image`).
       Turning "Other files" OFF is the ONLY way an unrecognised type stops
       uploading -- so a new capture format (`.webp`, a motion-photo
       container, a burst format) is never dropped silently; it stays
       covered by the catch-all until the user makes a deliberate choice.
       Classification is by `MediaStore` `MIME_TYPE` first, filename
       extension as fallback for the "Other files" boundary (decision A3).
       Default sets (decision B6): JPEG `jpg jpeg`; HEIC `heic heif`; Raw
       `dng raw arw nef cr2 cr3 rw2 orf raf srw`; Videos `mp4 mov 3gp m4v
       mkv webm`.
    2. Max file size: "skip files larger than N". Default OFF, no cap
       (decision B3). Enforced as a `SIZE <= ?` row filter plus a
       `File.length()` stat per surviving candidate (a known-path read, not
       enumeration -- Scoped-Storage-safe, same basis as SYNC-008's
       existing `File.exists()` stale-row check) as a backstop against a
       stale `MediaStore.SIZE`.
    3. Bandwidth limit: a single KiB/s ceiling, unconditional (NOT split
       Wi-Fi vs cellular -- decision B4/A4). Default OFF. Lives in an
       Advanced section. Wired through the EXISTING
       `SyncRunOptions.bwlimit_kib` FFI field -- no engine change.
    4. Presets: Everything / Photos only / Lean / Custom. A thin layer over
       the individual fields: selecting one populates them; editing any
       field flips the selector to Custom. Only "which preset is selected"
       plus the field values are persisted -- no independent preset store.
       Mixes (decision B2): Everything = all ON, no caps. Photos only =
       Videos OFF, everything else ON (Raw stays ON). Lean = Raw OFF,
       everything else ON, ~1 GB size cap, bandwidth limit on (a single
       unconditional value). Custom = user-set.
    5. Backlog estimate: a live read-only line under the toggles in
       Settings ("On this phone 12,400 photos, 340 videos, 88 GB. This
       scope will upload about 61 GB."), recomputed as toggles / the size
       cap change. Computed by iterating a `MediaStore.Files` cursor
       (`DISPLAY_NAME`, `SIZE`, `MIME_TYPE`) over the same `RELATIVE_PATH`
       scope and summing client-side -- NOT a SQL `GROUP BY`/`SUM`
       aggregate (unreliable across API 29-30+). No tunnel, no target
       needed. Off the main thread, cached with a timestamp, refreshed on
       screen open or a `ContentObserver` tick.
    6. Preview action: a button opening a Material 3 modal bottom sheet over
       Settings with the per-type breakdown, the skipped buckets
       (toggled-off types, over-the-cap files), and the largest included
       files -- all from the current scope, no tunnel. NOT the Progress tab
       (that is about a run happening now). The real dry-run against the
       server is deferred past v1 (decision "Dry-run in v1: No"); when it
       lands it augments this same bottom sheet with "already on server /
       will send" columns.
    7. Fixed engine defaults (not user-visible): `timeout` 120s,
       `connect_timeout` 30s in `SyncEngine::run_client` so a dead mesh
       path fails a WorkManager run instead of hanging it; `partial_dir`
       (`.tetron-partial`) so in-progress files do not surface as broken
       thumbnails mid-transfer; `compress` and `checksum` stay the
       oc-rsync builder defaults (both already false). These are hardcoded
       builder calls -- they do NOT change the `SyncRunOptions` record
       shape, so no UniFFI binding / jniLib regeneration.

    ## Deliberately deferred (must not be foreclosed)

    - Overflow ("hamburger dots") menu editors -- "Edit raw formats...",
      "Add a custom type...", "Reset to defaults" -- that let a power user
      adjust the extension sets `SettingsStore` holds. v1.1 (decision B1).
      v1 ships the built-in sets as constants; the set values must already
      live in a shape the editor can later mutate, not be inlined into the
      query builder.
    - Real `--dry-run` against the target (needs tunnel Active + reachable
      target); augments the Preview bottom sheet when it lands.
    - User-defined named presets; per-run/per-day data budget ("stop after
      N GB", app-side byte accounting, no rsync support); folder scope
      beyond `DCIM/Camera` (SAF picker -- plan decision #10 already defers
      it); age filter ("only last N days", a `DATE_ADDED` predicate --
      invites "why was that old photo never backed up").

    ## Consequence for delete-after-backup (SYNC-007)

    An excluded file (toggled-off type, over the cap) is never transferred,
    so it is never deleted -- correct by construction (SYNC-007 already
    deletes only byte-verified files transferred this run). A permanent Raw
    exclusion plus delete-after-backup on means `.dng` files pile up on the
    phone indefinitely while their `.jpg` siblings are backed up and
    removed. The Settings copy MUST state this: "Delete after backup
    removes only files that were backed up. Types you turn off stay on your
    phone." The app never runs `--delete`, so copies already on the home
    server from a run made under a wider scope also stay there.

    ## Where it lands

    - `SettingsStore` (`xyz.tetron.sync.settings`): persist the
      `BackupScope` fields + the selected preset name. Read fresh per run
      (same live-read contract as `gateConfig`).
    - `xyz.tetron.sync` scope model + classifier: a `BackupScope` data
      class, a pure `ScopeFilter` mapping `(displayName, mimeType,
      sizeBytes) -> Included | ExcludedType | ExcludedOversize`, a `Preset`
      enum with preset<->scope mapping and Custom detection. Pure Kotlin,
      fully JVM-unit-tested (no Android deps) -- same bar as SYNC-004's
      `GateEvaluator`.
    - `AndroidMediaAccess` (SYNC-008): the `--files-from` query projection
      gains `SIZE` + `MIME_TYPE`; rows run through `ScopeFilter`; the
      surviving names are staged as today. A new aggregate mode produces
      the backlog estimate and the Preview breakdown. `SourceSpec` gains a
      `skippedOversizeCount: Int`.
    - `SyncPipeline` (SYNC-005): threads a `() -> BackupScope` supplier
      through (existing `gateConfig` pattern); carries
      `skippedOversizeCount` into a new `RunRecord` field distinct from
      `skipped` (decision A5/B5 -- oversize skips are surfaced in History
      only, not a notification, but must be countable separately from the
      "already on server" skips that dominate `skipped`).
    - `SyncEngine::run_client` (`src/lib.rs`): add the fixed
      `timeout`/`connect_timeout`/`partial_directory` builder calls (item 7
      -- no FFI record change).
    - `xyz.tetron.sync.ui.settings` (SYNC-009): the five toggles, the size
      cap, the Advanced section (bandwidth limit), the preset selector, the
      live estimate line, the "Preview" button + its modal bottom sheet,
      and the updated delete-after-backup copy.

    ## Decision register (confirmed 2026-08-28)

    A1  v1 is spec + Kotlin (+ the item-7 hardcoded Rust defaults) only; NO
        new `SyncRunOptions` field, NO UniFFI regen. The whole type/size
        scope is enforced in the Kotlin `--files-from` builder.
    A2  Pre-29 (API 26-28) keeps the unfiltered recursive walk; the scope
        does not apply there. Documented gap; no reference device is pre-29.
    A3  Classify by `MediaStore` `MIME_TYPE` first, filename extension as
        fallback.
    A4  "Lean" preset and the bandwidth control use one unconditional
        KiB/s value; no Wi-Fi-vs-cellular split (also B4).
    A5  `RunRecord` gets a distinct oversize-skipped count, separate from
        `skipped`.
    B1  Overflow-menu extension-set editors: v1.1, not v1.
    B2  "Photos only" preset keeps Raw ON (drops Videos only).
    B3  Max file size default: OFF (no cap out of the box).
    B5  Toggle / size-cap skips: surfaced in History only, no one-time
        notification (paired with A5's separate count).
    B6  Default extension sets as listed under control 1 -- accepted for v1.

    ACCEPTANCE: JVM unit tests cover the scope model end to end -- the
    `ScopeFilter` decision for every toggle state (each type individually
    excluded with the right reason, "Other files" OFF restricting to the
    known sets, the oversize path, MIME-vs-extension precedence), the
    preset<->scope round trip and Custom-on-edit detection, and the pure
    scope-selection / backlog-aggregation functions the `--files-from`
    builder and the estimate share. `SettingsStore`'s scope round trip
    follows the existing thin-`SharedPreferences`-adapter convention (no
    unit test, like `gateConfig` and `SharedPreferencesRunHistoryStore` --
    verified on-device with the other Settings toggles, SYNC-009).
    `AndroidMediaAccess`'s cursor-to-`MediaEntry` glue is the same
    untested-by-design adapter as `resolveMediaAccessState`'s callers; the
    aggregation it feeds is unit-tested directly. `SyncPipelineTest` gains
    a case that a scoped run stages only the in-scope names and records
    `skippedOversizeCount`. `:app:testDebugUnitTest`,
    `:app:assembleDebug`, `cargo test`, and `python3 reconcile.py` all
    green. On-device verification (a real scoped run + Preview against the
    real camera roll) folds into SYNC-011's ongoing device pass.
    ENFORCEMENT: the scope-model + pipeline unit tests are the automated
    gate; the Compose UI and the on-device scoped run are manual, same bar
    as SYNC-009.
    """
    req_id = "SYNC-012"
