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


# SYNC-002 spec/sync.py's own ACCEPTANCE text: "cargo audit shows only the
# known russh advisory" -- rsa's Marvin-attack timing side channel, pulled in
# transitively via russh (SSH transport, never enabled in this app's feature
# set). No fixed rsa release exists yet (cargo audit's own advisory record:
# "No fixed upgrade is available!"), so this is accepted indefinitely, not a
# TODO to clear. Update this set only alongside a spec.py change recording
# the new exception's rationale, same bar as adding one here did.
ACCEPTED_ADVISORIES = {"RUSTSEC-2023-0071"}


def check_cargo_audit() -> dict:
    """Known-CVE scanning of the dependency tree via `cargo audit`. Mirrors
    core's D-02 check. `cargo-audit` not being installed is reported
    distinctly from an actual finding. Advisory ids in ACCEPTED_ADVISORIES
    do not count against the gate (SYNC-002 added the first one,
    2026-08-19 -- until this fix, ANY appearance of the accepted rsa/russh
    advisory failed reconcile.py outright, contradicting spec/sync.py's own
    stated acceptance criterion for this exact advisory)."""
    try:
        r = run(["cargo", "audit", "--json"])
    except FileNotFoundError:
        return {"installed": False, "count": -1}
    try:
        data = json.loads(r.stdout)
        findings = data["vulnerabilities"]["list"]
    except (json.JSONDecodeError, KeyError):
        return {"installed": True, "count": -1}
    unaccepted = [f for f in findings if f["advisory"]["id"] not in ACCEPTED_ADVISORIES]
    return {"installed": True, "count": len(unaccepted)}


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