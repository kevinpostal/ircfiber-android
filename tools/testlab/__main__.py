"""TestLab: queued, isolated, parallel UI test runs for the Android app (`python3 tools/testlab android`, `make uitest…`).

Vendored from vanlife's tools/testlab (Android half). Device slots are host-wide: this copy, vanlife's and FiberIRC's
(iOS) lease the same slots (config.lease_home), so their simulators and emulators queue behind each other.

Exit codes: 0 nothing failed or unrun (flaky allowed) · 1 failures or unrun tests · 2 usage or selection error ·
3 infrastructure failure (build, missing tool, no usable device) · 130 interrupted.
"""
from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time

import config
import lease
import proc
import report
from config import EXIT_INFRA, EXIT_INTERRUPTED, EXIT_OK, EXIT_USAGE, InfraError, UsageError

STOP_GRACE_SECONDS = 10.0
_stopping = False


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, _on_signal)
    try:
        return args.func(args)
    except UsageError as e:
        print(f"testlab: {e}", file=sys.stderr)
        return EXIT_USAGE
    except InfraError as e:
        print(f"testlab: {e}", file=sys.stderr)
        return EXIT_INFRA
    except KeyboardInterrupt:
        proc.stop_all()  # A build or Gradle child outside a run's own cleanup (they run in their own sessions).
        print("testlab: interrupted; test processes stopped, slots released", file=sys.stderr)
        return EXIT_INTERRUPTED
    except BrokenPipeError:  # Output piped into `head`: not an error.
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
        return EXIT_OK
    except OSError as e:  # Disk full, permissions: the host, not the tests.
        print(f"testlab: {e}", file=sys.stderr)
        return EXIT_INFRA


def _on_signal(signum, frame) -> None:
    """First SIGINT/SIGTERM unwinds the run (its `finally` blocks stop children and release slots); later ones while
    that cleanup runs are ignored so it can finish."""
    global _stopping
    if _stopping:
        print("testlab: still stopping…", file=sys.stderr, flush=True)
        return
    _stopping = True
    raise KeyboardInterrupt


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="testlab", description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="command", required=True)

    android_p = sub.add_parser("android", help="Android UI tests on Gradle-managed emulators")
    android_p.add_argument("--tier", choices=["smoke", "full", "failed"], help="default full")
    android_p.add_argument("--only", action="append", metavar="REGEX",
                           help="tests whose id (Class#method) matches; repeatable")
    android_p.add_argument("--dry-run", action="store_true", help="print the selection and shard plan, then exit")
    android_p.add_argument("--shards", type=int, default=None, help="emulators to shard over (default 2)")
    android_p.set_defaults(func=cmd_android)

    sub.add_parser("status", help="device slots, emulators, foreign test runs").set_defaults(func=cmd_status)

    stop_p = sub.add_parser("stop", help="interrupt TestLab runs holding slots, or foreign test runs")
    which = stop_p.add_mutually_exclusive_group(required=True)
    which.add_argument("--slot", type=int, action="append", metavar="N", help="the run holding slot N; repeatable")
    which.add_argument("--all", action="store_true", help="every TestLab run holding a slot")
    which.add_argument("--foreign", action="store_true", help="test runs outside TestLab (see status)")
    stop_p.set_defaults(func=cmd_stop)

    sub.add_parser("clean", help="delete old run directories").set_defaults(func=cmd_clean)

    compare_p = sub.add_parser("compare", help="paired test time and newly failing tests, run A → run B")
    compare_p.add_argument("a", help="run directory or results.json")
    compare_p.add_argument("b", help="run directory or results.json")
    compare_p.set_defaults(func=cmd_compare)
    return p


def cmd_android(args) -> int:
    if args.only and args.tier:
        raise UsageError("--only and --tier are exclusive")
    if args.only and not all(args.only):
        raise UsageError("--only needs a regex, e.g. make uitest-only ONLY=coldLaunch")
    if args.shards is not None and args.shards < 1:
        raise UsageError("--shards must be at least 1")
    import android
    return android.run(args)


def cmd_status(args) -> int:
    home, capacity = config.lease_home(), config.capacity()
    held = lease.holders(home, capacity)
    print(f"capacity {capacity} · {sum(1 for h in held if h['slot'] <= capacity)} in use · {home}")
    for h in held:
        print(f"  slot {h['slot']}  {h.get('platform', '?')}  pid {h.get('pid', '?')}  {lease.age(h.get('started'))}  "
              f"{h.get('worktree', '?')}  {h.get('command', '')}")
    commands = subprocess.run(["ps", "-axo", "command="], capture_output=True, text=True).stdout.splitlines()
    print(f"emulator processes (qemu-system) {sum(1 for c in commands if 'qemu-system' in c.split(' ', 1)[0])}")
    foreign = lease.foreign_processes(h.get("pid") for h in held)
    print("foreign test processes" + ("" if foreign else " none"))
    for pid, command in foreign:
        print(f"  pid {pid}  {command[:240]}")
    return EXIT_OK


def cmd_stop(args) -> int:
    held = lease.holders(config.lease_home(), config.capacity())
    if args.foreign:
        targets = lease.foreign_processes(h.get("pid") for h in held)
    else:
        chosen = held if args.all else [h for h in held if h["slot"] in args.slot]
        for slot in sorted(set(args.slot or []) - {h["slot"] for h in held}):
            print(f"slot {slot} is free")
        pids = {}
        for h in chosen:
            pid = h.get("pid")
            if isinstance(pid, int) and pid != os.getpid():
                pids[pid] = h.get("command") or "?"
        targets = sorted(pids.items())
    if not targets:
        print("nothing to stop")
        return EXIT_OK
    for pid, command in targets:
        _kill(pid, signal.SIGINT)
        print(f"SIGINT  pid {pid}  {command[:240]}")
    deadline = time.monotonic() + STOP_GRACE_SECONDS
    while time.monotonic() < deadline and any(_alive(pid) for pid, _ in targets):
        time.sleep(0.5)
    for pid, command in targets:
        if _alive(pid):
            _kill(pid, signal.SIGTERM)
            print(f"SIGTERM pid {pid}  {command[:240]}")
    return EXIT_OK


def cmd_clean(args) -> int:
    report.prune_runs(config.ANDROID_STATE / "runs", config.RETAIN_RUNS)
    print(f"kept the newest {config.RETAIN_RUNS} runs in {config.ANDROID_STATE / 'runs'}")
    return EXIT_OK


def cmd_compare(args) -> int:
    try:
        print(report.compare(args.a, args.b))
    except (OSError, ValueError, KeyError) as e:
        raise UsageError(f"cannot compare: {e}") from None
    return EXIT_OK


def _kill(pid: int, sig: int) -> None:
    try:
        os.kill(pid, sig)
    except (ProcessLookupError, PermissionError):
        pass


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


if __name__ == "__main__":
    sys.exit(main())
