"""Child processes with logs: streamed output, idle detection, process-group stop."""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import threading
import time
from collections import deque
from pathlib import Path

STOP_GRACE_SECONDS = 20.0

_live: set = set()
_live_lock = threading.Lock()


class Child:
    """A command in its own session (Ctrl-C reaches only TestLab, which stops it in order), stdout and stderr merged
    into `log_path`, each line also handed to `on_line` from a reader thread."""

    def __init__(self, cmd, log_path: Path, cwd=None, env=None, on_line=None):
        self.cmd = [str(c) for c in cmd]
        self._on_line = on_line
        self._last_output = time.monotonic()
        self._finished = False
        log = open(log_path, "ab")
        try:
            self.popen = subprocess.Popen(self.cmd, cwd=cwd, env={**os.environ, **(env or {})},
                                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                          start_new_session=True)
        except BaseException:
            log.close()
            raise
        with _live_lock:
            _live.add(self)
        self._reader = threading.Thread(target=self._pump, args=(log,), daemon=True)
        self._reader.start()

    def _pump(self, log) -> None:
        with log:
            for raw in iter(self.popen.stdout.readline, b""):
                self._last_output = time.monotonic()
                log.write(raw)
                log.flush()
                if self._on_line is None:
                    continue
                try:
                    self._on_line(raw.decode("utf-8", "replace").rstrip("\r\n"))
                except Exception as e:  # Keep draining the pipe, or the child blocks on a full one.
                    print(f"testlab: progress output stopped for {self.cmd[0]}: {e!r}", file=sys.stderr, flush=True)
                    self._on_line = None

    def idle_seconds(self) -> float:
        return time.monotonic() - self._last_output

    def poll(self) -> int | None:
        rc = self.popen.poll()
        if rc is not None:
            self._finish()
        return rc

    def wait(self) -> int:
        rc = self.popen.wait()
        self._finish()
        return rc

    def _finish(self) -> None:
        if self._finished:
            return
        self._finished = True
        # A grandchild that outlives the group may still hold the pipe open; don't wait on it forever.
        self._reader.join(timeout=5.0)
        with _live_lock:
            _live.discard(self)

    def signal(self, sig: int) -> None:
        try:
            os.killpg(self.popen.pid, sig)
        except (ProcessLookupError, PermissionError):
            pass

    def stop(self, grace: float = STOP_GRACE_SECONDS) -> None:
        """SIGINT to the process group, then SIGKILL if it is still running after `grace` seconds."""
        stop_all([self], grace)


def stop_all(children=None, grace: float = STOP_GRACE_SECONDS) -> None:
    """Stops `children` (default: every live child) together: SIGINT to each group, one shared grace period, SIGKILL."""
    if children is None:
        with _live_lock:
            children = list(_live)
    for child in children:
        child.signal(signal.SIGINT)
    deadline = time.monotonic() + grace
    for child in children:
        while child.poll() is None and time.monotonic() < deadline:
            time.sleep(0.2)
    for child in children:
        if child.poll() is None:
            child.signal(signal.SIGKILL)
            child.wait()


def run_logged(cmd, log_path: Path, cwd=None, env=None) -> int:
    """Runs `cmd` to completion with its output in `log_path`; returns the exit code."""
    return Child(cmd, log_path, cwd=cwd, env=env).wait()


def tail(path: Path, lines: int = 40) -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return "".join(deque(f, maxlen=lines))
    except FileNotFoundError:
        return ""
