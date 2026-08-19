# oc-rsync vendored fork — Android portability patches

**Upstream:** `oferchen/rsync` (GitHub), commit `ce6d7f8` = "fix(filters): refuse a
sided rule inside a sided merge file (#7383)", tagged v0.6.4 era.
**License:** GPL-3.0 (this fork stays under it; the sync app's own code is also
GPL-3.0, no license-boundary conflict).
**Baseline checkout:** `git clone` upstream, `git checkout ce6d7f8`.

This directory is the full upstream source tree with the Android portability
patches below applied **and committed as plain tree content** (the fork is
vendored, not submoduled, so the app always builds from a self-contained tree).

## Regenerating

Apply `patches/android-portability.diff` on a pristine `ce6d7f8` checkout:

```
git checkout ce6d7f8
git apply patches/android-portability.diff
```

This reproduces this directory exactly. Verified 2026-08-18: a clean checkout
plus the patch diff-identical to `vendor/oc-rsync/` (ignoring `patches/` itself).

## Patch list

All patches exist to make the tree cross-compile for Android (`aarch64-linux-android`
/ bionic) with `cargo ndk`. None change wire behaviour. Grouped by file:

1. **jemalloc exclusion (build):**
   - `Cargo.toml` — `tikv-jemallocator` dependency narrowed to
     `cfg(all(unix, not(target_os = "android")))` (jemalloc does not build on bionic).
   - `src/bin/oc-rsync.rs` — `#[global_allocator] Jemalloc` guarded the same way.

2. **Pure-Rust zlib backends (build):**
   - `crates/compress/Cargo.toml` — `default = ["zstd","lz4","zlib-rs"]` (was `zlib-ng`).
   - `crates/protocol/Cargo.toml` — `default = ["zlib-rs"]` (was `zlib-ng`).
   - zlib-ng is a C library needing a native build; `zlib-rs` is flate2's pure-Rust
     backend. Wire compression is negotiated per-connection, so this changes nothing
     for compatibility with stock rsync (protocol 31 supports zlib deflate).

3. **openat2 / confined open fallback (runtime, fast_io):**
   - `crates/fast_io/src/confined_open.rs` — Android's `libc` crate lacks
     `open_how`/`RESOLVE_*` (glibc-only), so the openat2 fast path is compiled out and
     the portable walk takes over. `raw_openat2` is `cfg(not(target_os = "android"))`.

4. **NSS / user-group lookup stubs (build, metadata + daemon + cli):**
   - `crates/metadata/src/id_lookup/mod.rs` — `nss` module gated to
     `all(unix, not(target_os = "android"))`; Android uses `nss_stub` (the existing
     non-unix stub). NOTE: vendored here WITHOUT the accidental duplicate
     `mod nss_win;` that was present in the spike working tree; the patch file is
     the cleaned version.
   - `crates/metadata/src/id_lookup/cache.rs` — import split to pick
     nss vs nss_stub by the same cfg.
   - `crates/cli/src/platform.rs` — `uzers` (glibc `getgrnam_r`/`getgid_r` wrappers)
     gated off on Android with `u32` fallback types.
   - `crates/daemon/src/daemon/module_state/auth.rs` — `groups_for_user` stub on
     Android (daemon crate is not part of the embedded build, kept for fork parity).
   - `crates/daemon/src/daemon/sections/privilege.rs` — `resolve_all_user_groups`
     stub on Android (same reason).

5. **makedev import (build, metadata):**
   - `crates/metadata/src/special.rs` — use `nix::libc::makedev` instead of
     `nix::sys::stat::makedev` (the latter is `cfg`'d away on Android in nix).

## Embedded-build note (SYNC-002)

The sync app depends on this fork's `crates/core` crate only
(`vendor/oc-rsync/crates/core`), never the root `bin` package, with
`default-features = false` plus `features = ["zstd","lz4","xattr"]`. Consequences:

- `openssl-vendored` is **not** needed: the root bin's manifest pins
  `checksums` with `features = ["openssl"]`, but `core`'s unix dependency on
  `checksums` is feature-less (its `default = []`), so no `openssl-sys` enters the
  embedded tree at all. The SYNC-002 spec's feature string
  `openssl-vendored,zstd,lz4,parallel,xattr` was written against the root-bin build
  used in the spike; the embedded build translates to `zstd,lz4,xattr` on `core`.
  `parallel` is a root-bin-only alias (`cli/parallel` + empty `checksums/parallel`); it
  does not exist on `core`.
- No io_uring / iocp / landlock / embedded-ssh / acl: none are enabled for `core`
  with the feature set above (they are either root-bin/daemon features or optional).
- Patch groups 1 (jemalloc) and 4's daemon/cli files are outside the embedded build,
  but kept applied so the vendored fork still builds its full binary identically to
  the upstream-plus-patches state.