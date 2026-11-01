"""Per-test timing estimates (Gradle splits the managed-device shards itself)."""
from __future__ import annotations

import json
import os
import statistics
from pathlib import Path

from lease import FileLock

DEFAULT_SECONDS = 60.0


def estimates(ids, timings: dict) -> dict[str, float]:
    """Each test's recorded seconds; unknown tests get the median of all recorded timings (60 s with none)."""
    known = [float(v) for v in timings.values() if isinstance(v, (int, float))]
    default = statistics.median(known) if known else DEFAULT_SECONDS
    return {i: float(timings.get(i, default)) for i in ids}


def load_timings(path: Path) -> dict:
    try:
        data = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def update_timings(path: Path, results: dict) -> None:
    """Moving average (half old, half new) of passed and flaky tests' seconds; failures don't count."""
    path = Path(path)
    with FileLock(path.parent / "state.lock", "the timings file"):
        timings = load_timings(path)
        for test, result in results.items():
            if result.status not in ("passed", "flaky"):
                continue
            old = timings.get(test)
            seconds = float(result.seconds)
            timings[test] = round(0.5 * old + 0.5 * seconds, 1) if isinstance(old, (int, float)) else round(seconds, 1)
        tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
        tmp.write_text(json.dumps(timings, indent=1, sort_keys=True) + "\n")
        os.replace(tmp, path)
