#!/usr/bin/env python3
# reconcile.py -- run from ~/code/tetron-mobile-sync
# Usage: python3 reconcile.py
#
# Checks the automatable constraints from spec/. SYNC-001 adds the standard
# cargo checks (mirroring tetron-mobile's proven set); the spec-tree check
# from the initial scaffold stays. Grows further checks as real regression
# surface shows up.
import json
import subprocess
import sys


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def check_spec_diff() -> dict:
    """The spec tree must compile and match the accepted baseline: `uv run
    libspec diff` against HEAD reports no changes. A spec tree that fails to
    compile (or drifted from the committed baseline) shows up here as a
    non-zero exit and/or non-empty output."""
    r = run(["uv", "run", "libspec", "diff"])
    stderr = r.stderr[-2000:] if r.stderr else ""
    stdout = r.stdout[-2000:] if r.stdout else ""
    return {
        "success": r.returncode == 0 and "No changes" in stdout,
        "stdout": stdout,
        "stderr": stderr,
    }


def check_build() -> dict:
    r = run(["cargo", "build", "--quiet"])
    return {"success": r.returncode == 0, "stderr": r.stderr[-2000:] if r.returncode else ""}


def check_clippy() -> dict:
    r = run(["cargo", "clippy", "--all-targets", "--quiet", "--", "-D", "warnings"])
    return {"warnings": 0 if r.returncode == 0 else r.stderr.count("warning:")}


def check_tests() -> dict:
    r = run(["cargo", "test", "--quiet"])
    return {"pass": r.returncode == 0}


def check_cargo_audit() -> dict:
    """Known-CVE scanning of the dependency tree via `cargo audit`. Mirrors
    core's D-02 check. `cargo-audit` not being installed is reported
    distinctly from an actual finding. SYNC-002 adds the one known, accepted
    advisory (RUSTSEC-2023-0071 via russh); until then this gate is already
    in place."""
    try:
        r = run(["cargo", "audit", "--json"])
    except FileNotFoundError:
        return {"installed": False, "count": -1}
    try:
        data = json.loads(r.stdout)
        count = data["vulnerabilities"]["count"]
    except (json.JSONDecodeError, KeyError):
        return {"installed": True, "count": -1}
    return {"installed": True, "count": count}


if __name__ == "__main__":
    ctx = {
        "spec_diff": check_spec_diff(),
        "build": check_build(),
        "clippy": check_clippy(),
        "test": check_tests(),
        "cargo_audit": check_cargo_audit(),
    }
    print(json.dumps(ctx, indent=2))
    ok = (
        ctx["spec_diff"]["success"]
        and ctx["build"]["success"]
        and ctx["clippy"]["warnings"] == 0
        and ctx["test"]["pass"]
        and ctx["cargo_audit"]["installed"]
        and ctx["cargo_audit"]["count"] == 0
    )
    sys.exit(0 if ok else 1)