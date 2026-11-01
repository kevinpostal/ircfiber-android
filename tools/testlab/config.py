"""Paths, limits and exit codes shared by every TestLab command.

Vendored from vanlife's tools/testlab (Android half only): this repo is the Gradle root, so ANDROID is the repo itself.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ANDROID = REPO
# Per-worktree state: builds, run directories, timings, last failures. Git-ignored through build/.
ANDROID_STATE = ANDROID / "build/testlab"

RETAIN_RUNS = 10
# Free space a run needs on the volume holding its state: until a shard finishes, its results and the build. A full
# volume lost a 44-minute run (vanlife).
MIN_FREE_BYTES = 3 * 1024 ** 3
SUITES = json.loads((Path(__file__).resolve().parent / "suites.json").read_text())

# Device-slot units one device costs: an emulator needs about twice a simulator's memory. These, DEFAULT_CAPACITY and
# lease_home() must equal vanlife's and FiberIRC's (iOS) tools/testlab/config.py: every copy leases the same host-wide
# slots, so emulators here and simulators there never overcommit this Mac together.
EMULATOR_UNITS = 2
DEFAULT_CAPACITY = 6  # Units on this 24 GB Mac: e.g. 4 simulators and 1 emulator at once.

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_INFRA = 3
EXIT_INTERRUPTED = 130


class UsageError(Exception):
    """Bad arguments or selection: exit 2."""


class InfraError(Exception):
    """Build failure, missing tool or no usable device: exit 3."""


def require_free_space(path: Path) -> None:
    """Fails fast, before building, when the volume holding `path` has less than MIN_FREE_BYTES free."""
    path.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(path).free
    if free < MIN_FREE_BYTES:
        raise InfraError(f"only {free / 1024 ** 3:.1f} GB free on the volume of {path.resolve()}; a run needs "
                         f"{MIN_FREE_BYTES // 1024 ** 3} GB for its build and result bundles")


def lease_home() -> Path:
    """Host-wide lease directory, shared by every worktree and session of this user."""
    env = os.environ.get("TESTLAB_HOME")
    if env:
        return Path(env).expanduser()
    if sys.platform == "darwin":
        return Path.home() / "Library/Caches/testlab"
    return Path.home() / ".cache/testlab"


def capacity() -> int:
    """Device-slot units this host may run at once (`TESTLAB_CAPACITY`)."""
    raw = os.environ.get("TESTLAB_CAPACITY", str(DEFAULT_CAPACITY))
    try:
        value = int(raw)
    except ValueError:
        value = 0
    if value < 1:
        raise UsageError(f"TESTLAB_CAPACITY must be an integer ≥ 1, got {raw!r}")
    return value
