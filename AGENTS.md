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

SYNC-002 is now fully closed out. **SYNC-003 (mesh bridge client) is
IMPLEMENTED as of 2026-08-19** (`feat/sync-003-mesh-bridge`): the Kotlin
client consumes tetron-mobile's MOBILE-024 ContentProvider
(`xyz.tetron.mobile.status`, `call("get_status")`). Cross-process key
insight: the snapshot is a Parcelable class that exists only inside
tetron-mobile's APK, so a consumer's `Bundle.getParcelable` would throw
`BadParcelableException`; the fix is the standard cross-app technique --
hand-written parcel-layout wire mirrors under the SAME FQCN
(`xyz/tetron/mobile/BridgeStatusWire.kt`: StatusSnapshot, BridgePeer,
BridgeTunnelState) with public static `CREATOR` fields, verified against
the provider's compiled `CREATOR` bytecode (state=enum-name string,
network/ownMeshIp/subnet strings, peer count + N peers (hostname, ip,
connKind int), updatedAtMillis long). Fresh GPL mirrors, zero logic, never
copied from tetron-mobile. App-facing surface is
`xyz.tetron.sync.bridge`: sealed `BridgeResponse` (Snapshot /
ConsentRequired / Unavailable -- never throws to UI), typed models
(BridgeSnapshot/BridgePeer/BridgeTunnelState/ConnKind), `MeshBridge` with
a 5s TTL cache (injected clock, thread-safe), `ProviderStatusCaller`
(Bundle glue, catches everything to Unavailable), defensive parsing
(unknown state name -> Unknown, unknown ConnKind int -> Unknown,
negative peer count -> empty). The parcel layout is MOBILE-024's contract:
a provider-side field change requires the Mirror updates in lockstep with
`MeshStatusProviderContractTest` staying green. Automated gate: 11 JVM
unit tests pass (`:app:testDebugUnitTest`; mapping + response algebra +
cache, zero Android deps). The instrumented cross-process test
(`MeshBridgeDeviceTest`) compiles but awaits the LG V40: consent branch +
mirror CREATOR resolution; the grant-then-snapshot branch is manual
(tap consent notification -> GrantActivity Allow) and @Ignore'd. Manifest
gained a `<queries><provider authorities=.../></queries>` so
`resolveContentProvider` in the device test is deterministic on Android 11+
(direct `ContentResolver.call` needs no visibility). Still-open device
verification is a TODO, not a blocker for SYNC-004.

**SYNC-004 (gate evaluation) is IMPLEMENTED as of 2026-08-19**
(`xyz.tetron.sync.gates`, no branch cut yet). Pure-Kotlin, no Android deps:
`GateEvaluator.evaluate(GateInputs, GateConfig): GateDecision` is a single
side-effect-free function -- callers gather `GateInputs` (bridge tunnel
state + per-target `ConnKind` from SYNC-003's cached snapshot,
`isWifiConnected`/`isCharging`/`batteryPercent` from local
`ConnectivityManager`/`BatteryManager` reads) before calling it, so a
`Blocked` result is produced with zero network activity by construction --
satisfies the ACCEPTANCE bullet about short-circuiting before any rsync
invocation without needing a mock rsync caller in this requirement (SYNC-005
owns wiring the real pipeline to it). Evaluation order is
`TunnelNotActive` -> `NotOnWifi` -> `ChargingRequired` -> `LowBattery` ->
`RelayOnlyPath`: the implicit tunnel gate first (nothing else is meaningful
without it), then the two local device-state gates, then the per-target
direct-only gate last since the spec calls it out as "second-stage" (only
meaningful once a target exists). `GateReason` carries a sixth value,
`TargetUnreachable`, deliberately never produced by this evaluator --
reserved for SYNC-005's pipeline, the only place an actual connection
attempt happens; it exists so `GateNotificationCoalescer`'s window covers
all six coalescing reasons from one enum ahead of SYNC-005 landing.
`GateConfig` defaults match the decision register exactly (wifiOnly=true,
directOnly=true, chargingRequired=false, lowBatteryPauseEnabled=true,
lowBatteryThresholdPercent=20). `GateNotificationCoalescer.shouldNotify
(GateReason): Boolean` implements decision #3's coalescing (default 6h
window, per-reason not global, injected clock for tests). 24 JVM unit
tests (`GateEvaluatorTest` + `GateNotificationCoalescerTest`) cover the
full AND matrix (every gate individually false with the right reason,
all-true passes, tunnel-not-active priority over simultaneous failures,
the cellular+Direct-allowed case from USER's 2026-08-18 correction) and
coalescing (drop within window, per-reason independence, re-allow at/after
the window, a dropped notification does not reset the window);
`:app:testDebugUnitTest` and `python3 reconcile.py` both green.

**Bug found + fixed 2026-08-19, ahead of SYNC-005:** the committed
`android/app/src/main/java/uniffi/tetron_mobile_sync/tetron_mobile_sync.kt`
was stale -- generated at SYNC-001 (`version()` only) and never regenerated
after SYNC-002 added `run_client`/`SyncRunOptions`/`SyncProgressListener`/
`SyncTransferOutcome`/`SyncError`, so `:app:testDebugUnitTest` had been
passing this whole time without the app ever actually compiling against
the real engine surface. Regenerating it (`cargo build --features
uniffi/cli`, then `cargo run --bin uniffi-bindgen --features uniffi/cli --
generate --library target/debug/libtetron_mobile_sync.so --language kotlin
--out-dir <dir>`) surfaced a real bug: `SyncError::Engine`'s `message`
field collided with UniFFI's Kotlin codegen, which adds its own `override
val message` (from `kotlin.Exception`) to every error variant -- Kotlin
compilation failed with "Conflicting declarations: val message: String".
Fixed in `src/lib.rs` by renaming the field to `detail` (now
`SyncException.Engine(exitCode, detail)` on the Kotlin side); regenerated
bindings compile clean. Neither the generated bindings file nor the
`jniLibs/*.so` outputs are tracked by git (`.gitignore` lines 10/13) --
both are local build artifacts from the explicit build step SYNC-001
scoped ("Gradle task automation is a follow-up"), regenerate them with the
two commands above (`cargo ndk -t arm64-v8a -t x86_64 -o
android/app/src/main/jniLibs build` for the `.so`s) any time `src/lib.rs`'s
UniFFI surface changes, before trusting `:app:assembleDebug`/
`:app:testDebugUnitTest` to mean the Kotlin side actually still matches it.

**SYNC-005 (transfer pipeline) is IMPLEMENTED as of 2026-08-19**
(`xyz.tetron.sync.pipeline`, no branch cut yet). `SyncPipeline.run
(SyncProgressListener?): PipelineResult` is the single path every trigger
(SYNC-006) will call: query the SYNC-003 bridge -> build `GateInputs` (the
configured target's `ConnKind` is looked up by matching its `meshIp`
against the snapshot's peer roster; a non-`Snapshot` bridge response maps
to `BridgeTunnelState.Unknown`, which the tunnel-active gate blocks the
same as a real down tunnel) -> `GateEvaluator.evaluate` (SYNC-004) ->
resolve target + source path -> invoke the engine through a
[`TransferRunner`] seam -> record a `RunRecord` -> return. A missing
target, a target absent from the roster (`directOnly` gate) or with no
resolvable source path (SYNC-008 not landed yet) all surface through
`GateReason.TargetUnreachable` rather than a special-cased error, per
SYNC-008's "target-unreachable-style failure, not a crash" framing --
deliberately reusing the existing six-reason vocabulary instead of growing
a parallel one. `TransferRunner`/`TargetProvider`/`SourcePathProvider`/
`DeviceStateProvider`/`RunHistoryStore` are all fakeable seams (same
pattern as SYNC-003's `StatusCaller`) so `SyncPipelineTest`'s 9 JVM unit
tests never touch the native `.so`; the engine's own byte-identical/
resume/idempotent behavior is what SYNC-002's `tests/engine_rsyncd.rs`
already covers (ACCEPTANCE's "host-side integration test... drives the
full pipeline: seeded tree -> run -> present -> idempotent re-run ->
interrupt -> resume" is exactly those three already-passing cases; SYNC-005
adds no new Rust test because it adds no new Rust engine behavior).
Reentrancy is an `AtomicBoolean` guard around the whole synchronous,
blocking `run()` call -- a concurrent trigger gets `PipelineResult
.AlreadyRunning` with the transfer runner invoked exactly once, verified
with a two-thread `CountDownLatch` test. History is a single last-run
record (`RunRecord`: added/skipped/failed counts, `interrupted` flag,
`failureReason`), matching SYNC-009's History screen wording ("last run
time" singular, not a log); `interrupted` (engine returns a summary with
`ioErrorExitCode` set, e.g. rsync exit 23/24) is explicitly NOT counted as
`failed` (spec/sync.py SYNC-005: "a partial/interrupted run is NOT an
error state"), only a thrown `SyncException` is, and the pipeline always
catches it into a `Ran` result rather than propagating -- callers (UI,
WorkManager) never need a try/catch. A gated cycle is never written to
history (only actual attempts are), matching the spec's "skip cycle and
notify" framing as distinct from a run. `onNotify: (GateReason) -> Unit`
is called at most once per gated cycle, gated itself by SYNC-004's
`GateNotificationCoalescer` -- the pipeline decides *when* to notify, not
*how* (channels/copy are SYNC-009). `EngineTransferRunner` (wraps
`SyncEngineInterface.runClient`) and `AndroidDeviceStateProvider` (wraps
`ConnectivityManager`/`BatteryManager`, matching SYNC-004's exact "Wi-Fi
only -- `TRANSPORT_WIFI` check" language) are the real, untested-by-design
adapters (same bar as `ProviderStatusCaller`); `TargetProvider` and
`SourcePathProvider` are contracts only -- SYNC-009 (settings-backed
target) and SYNC-008 (DCIM/permission-aware source) own the real
implementations, "wiring is minimal" as this file already anticipated.
`:app:assembleDebug` + `:app:testDebugUnitTest` + `python3 reconcile.py`
all green.

**SYNC-006 (trigger model v1) is IMPLEMENTED as of 2026-08-19**
(`xyz.tetron.sync.trigger`, no branch cut yet). All three triggers funnel
through one seam, `fun interface PipelineRunner { fun run(): PipelineResult }`
(production wiring is `PipelineRunner { pipeline.run() }`, SAM-converted;
kept separate from `SyncPipeline` itself so trigger-layer tests never need
the bridge/target/source dependencies a real pipeline requires):
- **Manual**: `ManualTrigger.triggerNow()` -- a direct, blocking pass-through
  (SYNC-009 wires the actual button and the background dispatch, same
  pattern `MainActivity`'s scaffold screen already uses for
  `SyncEngine.version()`).
- **Periodic**: `SyncWorker` (a real `androidx.work.Worker`, constructed
  with an injected `PipelineRunner` by `SyncWorkerFactory` rather than
  WorkManager's reflective default factory, since that only supports a
  bare `(Context, WorkerParameters)` constructor) delegates to
  `performSyncWork(PipelineRunner): ListenableWorker.Result` -- pulled out
  as a standalone function specifically so it is JVM-unit-testable without
  constructing a `Worker` (whose `WorkerParameters` has no public
  constructor outside WorkManager's own test harness). `SyncWorkScheduler
  .schedule` enqueues it via `enqueueUniquePeriodicWork` with
  `ExistingPeriodicWorkPolicy.KEEP` (relaunching the app must not reset an
  already-scheduled timer) at a ~daily default interval, `NetworkType
  .CONNECTED` as a coarse WorkManager-level pre-filter only -- the real
  Wi-Fi/direct/battery/charging decision still runs inside the pipeline
  every firing, since WorkManager constraints cannot express "Wi-Fi only,
  never a network-type heuristic" (SYNC-004 decision #4/#11).
- **Network-change**: `AndroidNetworkChangeTrigger` registers a
  `ConnectivityManager.NetworkCallback` (host-lifecycle-scoped
  `register()`/`unregister()`, deliberately no manifest
  `BroadcastReceiver` -- "not a wake-up mechanism" per spec) that calls
  `NetworkChangeDispatcher.onNetworkAvailable()`, split out the same way as
  `performSyncWork` so the dispatch behavior (submit to an executor, never
  run inline on the Binder callback thread since the runner blocks on
  network I/O) is JVM-testable without `android.net.Network` (which has no
  usable public constructor outside Robolectric).

11 new JVM unit tests cover: each trigger invokes `PipelineRunner.run()`
exactly once per firing and passes the result through unchanged (including
`AlreadyRunning`, proving the trigger layer adds no logic on top of
SYNC-005's own reentrancy guard rather than re-testing that guard itself);
`performSyncWork`'s full result mapping (hard failure -> `retry()`,
everything else including gated/already-running -> `success()`, since
neither is an error at this layer); and `SyncWorkScheduler
.buildPeriodicRequest`'s interval/constraint, built and asserted on
directly -- `PeriodicWorkRequestBuilder`/`Constraints` are plain
`work-runtime` classes, not `android.jar` stubs, so this needed no
Robolectric. A new instrumented test, `SyncWorkSchedulerDeviceTest`, uses
WorkManager's own `work-testing` harness (`WorkManagerTestInitHelper`,
`SynchronousExecutor`) to assert `schedule()` actually enqueues the job
under its unique name -- satisfies ACCEPTANCE's "instrumented test (or
manual adb check) confirms the WorkManager job is scheduled" without
needing to wait on real ~daily OS scheduling (unverifiable in a test run
regardless; SYNC-011 covers real-device behavior for the app as a whole).
Explicitly not built here, matching the spec's "must not foreclose it": the
v2 foreground-service + MediaStore `ContentObserver` instant-upload mode --
a v2 trigger would just be one more `PipelineRunner` caller.

Also fixes a second gap this surfaced, alongside the SYNC-002/005 stale-
bindings bug above: `AndroidManifest.xml` had never actually gained
`INTERNET`/`ACCESS_NETWORK_STATE`, despite the SYNC-001 scaffold comment
claiming they'd "arrive with SYNC-002"/SYNC-004 -- neither requirement
touched the manifest. Harmless while nothing in the manifest's own
component graph exercised `ConnectivityManager`/rsync sockets, but
SYNC-006 is the first requirement to actually register a manifest-adjacent
`NetworkCallback` and a `WorkManager` job with a network constraint, so it
is fixed here (with the corrected history noted inline in the manifest
comment) rather than left for whichever requirement happened to notice it
missing on a real device.

Production DI wiring -- constructing a real `SyncPipeline` (needs
`TargetProvider`/`SourcePathProvider`, still SYNC-009/SYNC-008-owned
contracts only) and registering `SyncWorkerFactory` via `WorkManager
.initialize`/`Configuration.Provider` -- is intentionally not done yet,
same "wiring is minimal until its dependency lands" note as SYNC-005's own
adapters. `:app:assembleDebug` + `:app:testDebugUnitTest` +
`:app:compileDebugAndroidTestKotlin` + `python3 reconcile.py` all green.
SYNC-007 (delete-after-backup), SYNC-008 (media access), and SYNC-009
(Compose UI) are all implemented and merged to `main` as of 2026-08-20;
see `spec/sync.py` for the full per-requirement writeups (this file has
not been kept in lockstep with every one of their implementation details --
a known gap, not a claim those requirements are undocumented, just that
`spec/sync.py`'s docstrings are the more current source for them right
now). SYNC-011 (final device verification, LG V40 against an AORUS
receiver) is in progress; see the SYNC-008 bug-fix note below for the one
real bug it has found so far. The build is scoped as SYNC-001..SYNC-011 in
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

**Bug found + fixed 2026-08-20, during SYNC-011 device verification:** a
real run on the LG V40 (API 29) against the real `DCIM/Camera` (705 real
files) transferred exactly 1 file. Root cause: Android Scoped Storage
filters raw directory *enumeration* (`readdir`, which oc-rsync's
`jwalk`-based recursive walk uses) per-app-UID via a FUSE daemon on API
29+ -- the app's own UID sees almost nothing via a raw walk of
`DCIM/Camera` even with `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` granted
(that grant covers MediaStore `content://` access, not raw path
enumeration). Confirmed with `tracing` instrumentation temporarily added
to the vendored oc-rsync fork, a packet capture, and
`android:requestLegacyExternalStorage="true"` (API 29-only, not a real
fix, used purely to confirm the hypothesis) -- with it, the same run saw
and transferred hundreds of files. Further confirmed that opening a
*known* filename directly (no enumeration) already works fine even with
Scoped Storage strictly enforced, by staging a hand-written oc-rsync
`--files-from` list of a few real filenames -- all transferred
successfully. This meant the fix only needed to replace *enumeration*,
not file *reads* -- avoiding a much larger MediaStore-URI/FD-based
rewrite of oc-rsync's core read path.

**Fix:** `AndroidMediaAccess.resolve()` (the real `SourcePathProvider`)
now returns `SourceSpec(rootPath, filesFromPath)`. On API 29+,
`filesFromPath` is populated by querying `MediaStore.Files`
(`RELATIVE_PATH = 'DCIM/Camera/'`, image/video `MEDIA_TYPE`) -- never
subject to the enumeration filter, since enumerating is MediaStore's
entire purpose -- and staging the filenames as a newline-delimited list
in app-private storage. Pre-29 devices keep `filesFromPath = null`
(unchanged recursive-walk behavior; no Scoped Storage restriction to work
around there). `SyncRunOptions` gained `files_from_path: Option<String>`,
wired in `SyncEngine::run_client` to `ClientConfig::builder().files_from
(FilesFromSource::LocalFile(path))` -- oc-rsync-core's own `--files-from`
support, unmodified. Verified on-device end to end with all diagnostics
removed: 701 real files/2.7GB transferred cleanly (the gap versus a raw
`find` count of 705 is MediaStore correctly excluding non-image/video
entries a blind walk would have counted). The `tracing`/`android_log-sys`
diagnostic dependencies and instrumentation added during the
investigation (including one temporary `tracing::error!` in the vendored
fork) were all removed once root-caused -- USER's call, to keep the
crate's dependency surface minimal rather than carry permanent logcat
plumbing as a side effect of this investigation.

**Bug found + fixed 2026-08-21, during the SYNC-011 interrupt/resume
device pass:** every real transfer against the real camera roll -- even
ones that fully succeeded with a clean daemon-side finish -- was recorded
as "Interrupted -- will resume next run". Root cause: `MediaStore`'s index
on a real, lived-in gallery can go stale (rows for files deleted through
some path that never triggered a rescan). oc-rsync correctly skips each
stale `--files-from` entry and transfers everything else (matching real
`rsync`'s own behavior for a named-but-missing entry), but still sets the
run's exit code to 23, which the app treated identically to a genuine
interruption. Confirmed via a throwaway diagnostic (a raw file write at
the point `oc-rsync`'s `transfer` crate sets `got_xfer_error`, reverted
immediately after use): `link_stat "<path>" failed: No such file or
directory`, for filenames independently confirmed absent from disk via
`adb shell ls`. **Fix:** `AndroidMediaAccess.writeFilesFromList` now
checks `File(rootDir, name).exists()` per `MediaStore` row before adding
it to the `--files-from` list -- a single stat on a *known* path, not a
directory listing, so it is not subject to the Scoped Storage enumeration
filter above. No Rust changes needed. Verified with a full clean-slate run
against the entire real camera roll (701 files): zero errors, clean
completion, History correctly shows "701 added, 0 skipped, 0 failed" with
no "Interrupted" label.

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