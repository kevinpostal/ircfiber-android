"""Android UI tests on Gradle-managed emulators on this Mac (vendored from vanlife's tools/testlab/android.py; its
vanlife-k3s `--remote`/`--split` pools are not part of this repo).

Gradle creates, boots, shards and shuts down the `pixel6Api35` managed device and installs on nothing else, so other
sessions' emulators and a plugged-in phone are never touched. Failed and unrun tests run once more on one emulator.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

import config
import discover
import lease
import proc
import report
import shard
from config import ANDROID, ANDROID_STATE, InfraError
from report import Result

DEVICE = "pixel6Api35"
TASK = f":app:{DEVICE}DebugAndroidTest"
RESULTS = ANDROID / "app/build/outputs/androidTest-results/managedDevice"
# Runner arguments given with -P replace defaultConfig's testInstrumentationRunnerArguments on managed devices, so the
# default filter (live tests only with their argument, plus the `testlab` selection) is passed again with them.
RUNNER_FILTER = "com.kevinpostal.fiberirc.LiveE2eFilter"
ECHO = ("> Task :app:pixel6Api35", "FAILED", "BUILD SUCCESSFUL", "BUILD FAILED")
SILENCE_SECONDS = 1800
HEARTBEAT_SECONDS = 60
HOMEBREW_JDK = Path("/opt/homebrew/opt/openjdk@21")


def run(args) -> int:
    files = sorted((ANDROID / "app/src/androidTest/java").rglob("*.kt"))
    fqns = discover.kotlin_tests(files)
    ids, label = discover.select(list(fqns), args.tier, args.only, config.SUITES["android"]["smoke"],
                                 ANDROID_STATE / "last-failed.txt")
    if not ids:
        if label == "failed":
            print("no failures recorded")
            return config.EXIT_OK
        raise discover.SelectionError(f"no UI tests found under {ANDROID / 'app/src/androidTest'}")
    shards = args.shards or 2
    timings = shard.load_timings(ANDROID_STATE / "timings.json")
    if args.dry_run:
        print_plan(label, ids, shards, timings)
        return config.EXIT_OK
    config.require_free_space(ANDROID_STATE)

    t0 = time.monotonic()
    started = datetime.now().astimezone().isoformat(timespec="seconds")
    run_id = f"{datetime.now():%Y%m%d-%H%M%S}-{os.getpid()}"
    runs = ANDROID_STATE / "runs"
    run_dir = runs / run_id
    run_dir.mkdir(parents=True)
    report.prune_runs(runs, config.RETAIN_RUNS)
    print(f"[android] {label} · {len(ids)} tests · {run_dir}", flush=True)

    selected = None if label == "full" else [fqns[t] for t in ids]
    results, devices, shard_meta, no_results = _run_local(run_dir, fqns, ids, selected, shards)

    meta = {"platform": "android", "tier": label, "run_id": run_id, "started": started,
            "wall_seconds": round(time.monotonic() - t0, 1), "devices": devices, "shards": shard_meta}
    summary = report.write_outputs(run_dir, meta, results)
    report.write_last_failed(ANDROID_STATE / "last-failed.txt", results)
    shard.update_timings(ANDROID_STATE / "timings.json", results)
    print(summary, flush=True)
    if no_results:
        raise InfraError(f"Gradle produced no test results, rerun included (logs {run_dir}/gradle*.log)")
    return config.EXIT_OK if report.passed(results) else config.EXIT_FAILED


def _run_local(run_dir: Path, fqns: dict, ids: list[str], selected: list[str] | None, shards: int):
    env = {"JAVA_HOME": java_home()}
    shard_meta: list[dict] = []
    devices: list[dict] = []
    no_results = False
    # Gradle shares this worktree's build directory: one TestLab Android run per worktree at a time.
    with lease.FileLock(ANDROID / ".gradle/testlab.lock", "this worktree's Android test run"):
        build(run_dir, env)
        held = lease.acquire(config.lease_home(), config.capacity(), want=config.EMULATOR_UNITS * shards,
                             minimum=config.EMULATOR_UNITS, info=lease.run_info("android"))
        try:
            shards = len(held.slots) // config.EMULATOR_UNITS
            for extra in held.slots[shards * config.EMULATOR_UNITS:]:
                held.release_slot(extra)
            devices = [{"name": f"{DEVICE}_{i}", "udid": None} for i in range(shards)]  # Gradle's names for them.
            seconds = gradle_test(run_dir / "gradle.log", selected, shards, env)
            shard_meta.append({"device": f"{DEVICE} ×{shards}", "tests": ids, "seconds": seconds})
            results = android_results(sorted(RESULTS.rglob("TEST-*.xml")), ids)
            again = report.failed_ids(results)
            if again:
                print(f"[android] rerunning {len(again)} failed or unrun test(s) on one emulator", flush=True)
                seconds = gradle_test(run_dir / "gradle-rerun.log", [fqns[t] for t in again], 1, env)
                shard_meta.append({"device": DEVICE, "tests": again, "seconds": seconds, "rerun": True})
                xmls = sorted(RESULTS.rglob("TEST-*.xml"))
                no_results = not xmls
                results = report.merge(results, android_results(xmls, again))
        finally:
            proc.stop_all()
            held.release()
    return results, devices, shard_meta, no_results


def print_plan(label: str, ids: list[str], shards: int, timings: dict) -> None:
    est = shard.estimates(ids, timings)
    print(f"Android · {label} · {len(ids)} tests · {shards} managed-device shard(s), split by Gradle · "
          f"est {report.duration(sum(est.values()) / max(1, shards))} per shard · estimates from "
          f"{ANDROID_STATE / 'timings.json'}")
    for test in ids:
        print(f"  {report.duration(est[test]):>8}  {test}")


def java_home() -> str:
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"]
    found = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True)
    if found.returncode == 0 and found.stdout.strip():
        return found.stdout.strip()
    if HOMEBREW_JDK.exists():
        return str(HOMEBREW_JDK)
    raise InfraError("JDK 21 not found; set JAVA_HOME")


def build(run_dir: Path, env: dict) -> None:
    log = run_dir / "build.log"
    print(f"[android] building app and test APKs · log {log}", flush=True)
    building = time.monotonic()
    command = ["./gradlew", "--console=plain", ":app:assembleDebug", ":app:assembleDebugAndroidTest"]
    if proc.run_logged(command, log, cwd=ANDROID, env=env) != 0:
        raise InfraError(f"Android build failed (log {log}):\n{proc.tail(log)}")
    print(f"[android] built in {report.duration(time.monotonic() - building)}", flush=True)


def gradle_test(log: Path, fqns: list[str] | None, shards: int, env: dict) -> float:
    """Runs the managed-device task on `shards` emulators (`fqns` None: every test); returns its wall seconds.
    Clears the previous results first, so only this run's XML is read.

    The selection goes in the `testlab` runner argument, ids joined by `+`, read by the androidTest LiveE2eFilter:
    managed-device runs keep only the first entry of a comma-separated `class`, and pass arguments through the device
    shell unquoted (no `|` regex)."""
    shutil.rmtree(RESULTS, ignore_errors=True)
    command = ["./gradlew", "--console=plain", TASK, "--rerun",
               f"-Pandroid.experimental.androidTest.numManagedDeviceShards={shards}",
               f"-Pandroid.testInstrumentationRunnerArguments.filter={RUNNER_FILTER}",
               "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000"]
    if fqns:
        command.append("-Pandroid.testInstrumentationRunnerArguments.testlab=" + "+".join(fqns))
    start = time.monotonic()
    child = proc.Child(command, log, cwd=ANDROID, env=env, on_line=_echo)
    next_beat = start + HEARTBEAT_SECONDS
    while child.poll() is None:
        time.sleep(1)
        now = time.monotonic()
        if now >= next_beat:
            print(f"[android] running · {int((now - start) // 60)} min", flush=True)
            next_beat += HEARTBEAT_SECONDS
        if child.idle_seconds() > SILENCE_SECONDS:
            print(f"[android] Gradle silent for {SILENCE_SECONDS // 60} min; stopping it", flush=True)
            child.stop()
    return round(time.monotonic() - start, 1)


def _echo(line: str) -> None:
    if any(marker in line for marker in ECHO):
        print(f"[android] {line}", flush=True)


def android_results(xml_paths, expected: list[str]) -> dict[str, Result]:
    """Results of `expected` (`Class#method`) from JUnit XML; missing → not_run. A test reported twice keeps a
    failure."""
    found: dict[str, Result] = {}
    for path in xml_paths:
        for case in ET.parse(path).getroot().iter("testcase"):
            test = f"{case.get('classname', '').rsplit('.', 1)[-1]}#{case.get('name', '')}"
            seconds = float(case.get("time") or 0.0)
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            if failure is not None:
                result = Result("failed", seconds, _failure_message(failure))
            elif case.find("skipped") is not None:
                result = Result("skipped", seconds)
            else:
                result = Result("passed", seconds)
            if found.get(test) is None or found[test].status != "failed":
                found[test] = result
    return {t: found.get(t, Result("not_run", 0.0, "did not run")) for t in expected}


def _failure_message(element) -> str:
    """First line of the failure, then the first stack frame in our code."""
    lines = [line.strip() for line in (element.text or element.get("message") or "").splitlines() if line.strip()]
    first = lines[0] if lines else (element.get("message") or element.get("type") or "failed")
    frame = next((line for line in lines[1:] if "com.kevinpostal" in line), "")
    return f"{first} {frame}".strip()
