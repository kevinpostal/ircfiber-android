"""Host-wide device slots shared by every worktree and agent session of this user, plus per-worktree file locks.

Slot n (1…capacity) is `<home>/slots/<n>.lock`, held with `flock` on an fd this process keeps open: the kernel frees it
when the process exits or crashes, so a lease never goes stale. `<n>.json` names the holder; it is written after the lock
is taken, deleted before it is dropped, and trusted only while the slot's lock is held.
"""
from __future__ import annotations

import atexit
import fcntl
import json
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

import config
from config import UsageError

POLL_SECONDS = 5.0
REPORT_SECONDS = 60.0
# Test runs that bypass TestLab: they use devices no lease accounts for.
TEST_PROCESS = re.compile(r"xcodebuild\s+(test|test-without-building)\b|GradleWrapperMain.*AndroidTest|\bam instrument\b")


class Lease:
    """Device slots this process holds. Released explicitly, at interpreter exit, or by the kernel if it dies."""

    def __init__(self, home: Path, fds: dict[int, int]):
        self.home = home
        self._fds = dict(fds)
        atexit.register(self.release)

    @property
    def slots(self) -> list[int]:
        return sorted(self._fds)

    def release_slot(self, slot: int) -> None:
        fd = self._fds.pop(slot, None)
        if fd is None:
            return
        try:
            (self.home / "slots" / f"{slot}.json").unlink()
        except FileNotFoundError:
            pass
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)

    def release(self) -> None:
        for slot in list(self._fds):
            self.release_slot(slot)


def run_info(platform: str) -> dict:
    """What a slot's JSON says about this run."""
    return {"platform": platform, "worktree": str(config.REPO), "command": shlex.join(["testlab", *sys.argv[1:]])}


def try_slot(home: Path, slot: int, info: dict) -> Lease | None:
    """Slot `slot` alone, if it is free."""
    fd = _try_lock(_slots_dir(home) / f"{slot}.lock")
    if fd is None:
        return None
    _write_json(home / "slots" / f"{slot}.json", {"pid": os.getpid(), **info, "started": time.time()})
    return Lease(home, {slot: fd})


def try_acquire(home: Path, capacity: int, want: int, minimum: int, info: dict) -> Lease | None:
    """Locks free slots in ascending order until `want` are held; fewer than `minimum` → releases them all, returns None.

    Never holds slots while waiting for more, so two runs cannot deadlock on each other.
    """
    slots = _slots_dir(home)
    fds: dict[int, int] = {}
    try:
        for n in range(1, capacity + 1):
            if len(fds) >= want:
                break
            fd = _try_lock(slots / f"{n}.lock")
            if fd is not None:
                fds[n] = fd
        if len(fds) < max(minimum, 1):
            _close(fds)
            return None
        record = {"pid": os.getpid(), "platform": info.get("platform"), "worktree": info.get("worktree"),
                  "command": info.get("command"), "started": time.time()}
        for n in fds:
            _write_json(slots / f"{n}.json", record)
    except BaseException:
        _close(fds)
        raise
    return Lease(home, fds)


def acquire(home: Path, capacity: int, want: int, minimum: int, info: dict,
            poll: float = POLL_SECONDS, report_every: float = REPORT_SECONDS) -> Lease:
    """`try_acquire` every `poll` seconds until it succeeds, saying who holds the slots on the first wait and every
    `report_every` seconds after. Ctrl-C aborts the wait."""
    if minimum > capacity:
        raise UsageError(f"this run needs {minimum} device slot(s) but TESTLAB_CAPACITY is {capacity}")
    last_report = None
    while True:
        lease = try_acquire(home, capacity, want, minimum, info)
        if lease is not None:
            return lease
        now = time.monotonic()
        if last_report is None or now - last_report >= report_every:
            held = holders(home, capacity)
            in_use = sum(1 for h in held if h["slot"] <= capacity)
            print(f"waiting for {minimum} free device slot(s): {in_use}/{capacity} in use — {describe(held)}", flush=True)
            last_report = now
        time.sleep(poll)


def holders(home: Path, capacity: int) -> list[dict]:
    """`{"slot", "pid", "platform", "worktree", "command", "started"}` for every held slot, slots above `capacity`
    included (another run may have a larger TESTLAB_CAPACITY)."""
    slots = _slots_dir(home)
    numbers = set(range(1, capacity + 1))
    numbers.update(int(p.stem) for p in slots.glob("*.lock") if p.stem.isdigit())
    held = []
    for n in sorted(numbers):
        path = slots / f"{n}.lock"
        if not path.exists():
            continue
        fd = _try_lock(path)
        if fd is not None:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)
            continue
        try:
            info = json.loads((slots / f"{n}.json").read_text())
        except (OSError, ValueError):
            info = {}
        held.append({**info, "slot": n})
    return held


def describe(held: list[dict]) -> str:
    """`slots 1-4 ios pid 812 (/path/worktree, 12 min); slot 5 android pid 900 (…)`, one entry per holding process."""
    if not held:
        return "no holders"
    by_pid: dict = {}
    for h in held:
        by_pid.setdefault(h.get("pid"), []).append(h)
    parts = []
    for pid, group in by_pid.items():
        numbers = _ranges([h["slot"] for h in group])
        noun = "slots" if len(group) > 1 else "slot"
        if pid is None:
            parts.append(f"{noun} {numbers} (holder unknown)")
            continue
        first = group[0]
        parts.append(f"{noun} {numbers} {first.get('platform')} pid {pid} "
                     f"({first.get('worktree')}, {age(first.get('started'))})")
    return "; ".join(parts)


def age(started) -> str:
    if not isinstance(started, (int, float)):
        return "?"
    minutes = max(0, int((time.time() - started) // 60))
    return f"{minutes // 60} h {minutes % 60} min" if minutes >= 60 else f"{minutes} min"


class FileLock:
    """Blocking exclusive `flock` on `path`, saying once what it waits for."""

    def __init__(self, path: Path, what: str):
        self.path = Path(path)
        self.what = what
        self._fd: int | None = None

    def __enter__(self) -> FileLock:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print(f"waiting for {self.what}", flush=True)
                fcntl.flock(fd, fcntl.LOCK_EX)
        except BaseException:
            os.close(fd)
            raise
        self._fd = fd
        return self

    def __exit__(self, *exc) -> None:
        fd, self._fd = self._fd, None
        if fd is not None:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)


def foreign_processes(owner_pids) -> list[tuple[int, str]]:
    """Test runs (xcodebuild test, Gradle *AndroidTest, am instrument) that no lease holder or this process started."""
    out = subprocess.run(["ps", "-axo", "pid=,ppid=,command="], capture_output=True, text=True, check=False).stdout
    table: dict[int, tuple[int, str]] = {}
    for line in out.splitlines():
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
            table[int(parts[0])] = (int(parts[1]), parts[2])
    owners = {int(p) for p in owner_pids if p} | {os.getpid()}

    def owned(pid: int) -> bool:
        seen = set()
        while pid > 1 and pid not in seen:
            if pid in owners:
                return True
            seen.add(pid)
            pid = table.get(pid, (0, ""))[0]
        return False

    return [(pid, cmd) for pid, (_, cmd) in sorted(table.items()) if TEST_PROCESS.search(cmd) and not owned(pid)]


def _slots_dir(home: Path) -> Path:
    path = home / "slots"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _try_lock(path: Path) -> int | None:
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)  # Not inherited by children (PEP 446).
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        return None
    except BaseException:
        os.close(fd)
        raise
    return fd


def _close(fds: dict[int, int]) -> None:
    for fd in fds.values():
        os.close(fd)
    fds.clear()


def _write_json(path: Path, data) -> None:
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    tmp.write_text(json.dumps(data))
    os.replace(tmp, path)


def _ranges(numbers: list[int]) -> str:
    """[1, 2, 3, 5] → "1-3,5"."""
    out = []
    numbers = sorted(numbers)
    start = prev = numbers[0]
    for n in numbers[1:] + [None]:
        if n is not None and n == prev + 1:
            prev = n
            continue
        out.append(f"{start}-{prev}" if prev > start else f"{start}")
        if n is not None:
            start = prev = n
    return ",".join(out)
