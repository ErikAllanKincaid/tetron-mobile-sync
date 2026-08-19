#!/usr/bin/env python3
# reconcile.py -- run from ~/code/tetron-mobile-sync
# Usage: python3 reconcile.py
#
# Checks the automatable constraints from spec/. Lighter than tetron-mobile's
# reconcile.py on purpose: this repo's real code is the sync-app crate, which
# arrives with the first implementation requirement (SYNC-001); until then the
# only automatable surface is the spec tree itself. Grows its own checks as
# real regression surface shows up (cargo build/clippy/test/audit arrive with
# SYNC-001, mirroring the proven tetron-mobile set).
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


if __name__ == "__main__":
    ctx = {
        "spec_diff": check_spec_diff(),
    }
    print(f"spec_diff: {ctx['spec_diff']['success']}")
    if not ctx["spec_diff"]["success"]:
        print(ctx["spec_diff"]["stdout"])
        print(ctx["spec_diff"]["stderr"])
    ok = ctx["spec_diff"]["success"]
    sys.exit(0 if ok else 1)