"""Run results: merging reruns, results.json / junit.xml / summary.md, last failures, run retention, run comparison."""
from __future__ import annotations

import json
import os
import shutil
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

STATUSES = ("passed", "failed", "flaky", "skipped", "not_run")
GREEN = ("passed", "flaky")
RED = ("failed", "not_run")
PLATFORM_NAMES = {"ios": "iOS", "android": "Android"}


@dataclass
class Result:
    status: str  # One of STATUSES.
    seconds: float
    message: str = ""


def merge(first: dict, rerun: dict) -> dict:
    """`first` with `rerun` applied: a rerun pass of a failed or unrun test is flaky; any other rerun result replaces."""
    merged = dict(first)
    for test, again in rerun.items():
        before = first.get(test)
        if again.status == "passed" and before is not None and before.status in RED:
            cause = before.message or before.status.replace("_", " ")
            merged[test] = Result("flaky", again.seconds, "passed on rerun after: " + cause)
        else:
            merged[test] = again
    return merged


def failed_ids(results: dict) -> list[str]:
    return [t for t, r in results.items() if r.status in RED]


def write_last_failed(path: Path, results: dict) -> None:
    ids = failed_ids(results)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("".join(f"{t}\n" for t in ids))


def write_outputs(run_dir: Path, meta: dict, results: dict) -> str:
    """results.json, junit.xml and summary.md in `run_dir`; returns the summary.

    meta: platform, tier, run_id, started, wall_seconds, devices [{name, udid}], shards [{device, tests, seconds}].
    """
    run_dir = Path(run_dir)
    doc = {key: meta[key] for key in ("platform", "tier", "run_id", "started", "wall_seconds", "devices", "shards")}
    doc["tests"] = {t: asdict(r) for t, r in results.items()}
    (run_dir / "results.json").write_text(json.dumps(doc, indent=1) + "\n")
    _junit(run_dir / "junit.xml", meta, results)
    summary = _summary(run_dir, meta, results)
    (run_dir / "summary.md").write_text(summary + "\n")
    return summary


def counts(results: dict) -> dict[str, int]:
    out = {s: 0 for s in STATUSES}
    for r in results.values():
        out[r.status] += 1
    return out


def passed(results: dict) -> bool:
    return not failed_ids(results)


def duration(seconds: float) -> str:
    total = int(round(seconds))
    if total < 60:
        return f"{total}s"
    minutes, secs = divmod(total, 60)
    if minutes < 60:
        return f"{minutes}m {secs}s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h {minutes}m {secs}s"


def _one_line(text: str, limit: int = 400) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _summary(run_dir: Path, meta: dict, results: dict) -> str:
    platform = meta["platform"]
    n = counts(results)
    verdict = "PASSED" if passed(results) else "FAILED"
    devices = meta["devices"]
    shards = [s for s in meta["shards"] if not s.get("rerun")]
    test_time = sum(r.seconds for r in results.values())
    lines = [
        f"{PLATFORM_NAMES.get(platform, platform)} UI tests · {meta['tier']} · {meta['run_id']}",
        f"{verdict}: {n['passed']} passed · {n['failed']} failed · {n['flaky']} flaky · {n['skipped']} skipped · "
        f"{n['not_run']} not run",
        f"Wall {duration(meta['wall_seconds'])} on {len(devices)} device{'s' if len(devices) != 1 else ''} "
        f"({', '.join(d['name'] for d in devices)}) · test time {duration(test_time)} · shards "
        + (" / ".join(duration(s["seconds"]) for s in shards) or "none"),
    ]
    for title, wanted in (("Failed", RED), ("Flaky", ("flaky",))):
        rows = [(t, r) for t, r in results.items() if r.status in wanted]
        if rows:
            lines += ["", title]
            lines += [f"  {t}  {_one_line(r.message or r.status.replace('_', ' '))}" for t, r in rows]
    slowest = sorted(results.items(), key=lambda item: -item[1].seconds)[:5]
    if slowest:
        lines += ["", "Slowest"] + [f"  {duration(r.seconds):>8}  {t}" for t, r in slowest]
    lines += ["", f"Artifacts {run_dir}"]
    if not passed(results):
        lines.append(f"Rerun failures: make -C {platform} uitest-failed")
    return "\n".join(lines)


def _junit(path: Path, meta: dict, results: dict) -> None:
    n = counts(results)
    suite = ET.Element("testsuite", {
        "name": f"{meta['platform']} UI ({meta['tier']})",
        "tests": str(len(results)),
        "failures": str(n["failed"] + n["not_run"]),
        "errors": "0",
        "skipped": str(n["skipped"]),
        "time": f"{sum(r.seconds for r in results.values()):.1f}",
    })
    for test, r in results.items():
        cls, _, name = test.replace("#", "/").partition("/")
        case = ET.SubElement(suite, "testcase", {"classname": cls, "name": name, "time": f"{r.seconds:.1f}"})
        if r.status == "failed":
            ET.SubElement(case, "failure", {"message": r.message or "failed"}).text = r.message
        elif r.status == "not_run":
            ET.SubElement(case, "failure", {"message": "not run" + (f": {r.message}" if r.message else "")})
        elif r.status == "skipped":
            ET.SubElement(case, "skipped", {"message": r.message} if r.message else {})
        elif r.status == "flaky":
            ET.SubElement(case, "system-out").text = f"flaky: {r.message}"
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def prune_runs(runs_dir: Path, keep: int) -> None:
    """Deletes all but the newest `keep` run directories (names sort by start time) and points `latest` at the newest."""
    runs_dir = Path(runs_dir)
    if not runs_dir.is_dir():
        return
    runs = sorted(p for p in runs_dir.iterdir() if p.is_dir() and not p.is_symlink())
    for old in runs[: max(0, len(runs) - keep)]:
        shutil.rmtree(old, ignore_errors=True)
    kept = runs[max(0, len(runs) - keep):]
    if kept:
        tmp = runs_dir / f".latest.{os.getpid()}"
        if tmp.is_symlink():
            tmp.unlink()
        os.symlink(kept[-1].name, tmp)
        os.replace(tmp, runs_dir / "latest")


def load_run(path) -> tuple[Path, dict]:
    path = Path(path)
    if path.is_dir():
        path = path / "results.json"
    return path, json.loads(path.read_text())


def compare(a, b) -> str:
    """Paired test time (tests green in both runs) and tests that changed between red and green, run A → run B."""
    path_a, run_a = load_run(a)
    path_b, run_b = load_run(b)
    tests_a, tests_b = run_a["tests"], run_b["tests"]
    both = [t for t in tests_a if t in tests_b]
    paired = [t for t in both if tests_a[t]["status"] in GREEN and tests_b[t]["status"] in GREEN]
    sum_a = sum(tests_a[t]["seconds"] for t in paired)
    sum_b = sum(tests_b[t]["seconds"] for t in paired)
    change = f"{(sum_b - sum_a) / sum_a * 100:+.1f}%" if sum_a else "n/a"
    newly_failing = [t for t in both if tests_a[t]["status"] in GREEN and tests_b[t]["status"] in RED]
    newly_passing = [t for t in both if tests_a[t]["status"] in RED and tests_b[t]["status"] in GREEN]
    lines = [
        f"A {path_a} · {run_a.get('tier')} · wall {duration(run_a.get('wall_seconds', 0))}",
        f"B {path_b} · {run_b.get('tier')} · wall {duration(run_b.get('wall_seconds', 0))}",
        f"Paired tests {len(paired)} (green in both): A {duration(sum_a)} ({sum_a:.1f}s) → "
        f"B {duration(sum_b)} ({sum_b:.1f}s), {change}",
        f"Newly failing {len(newly_failing)}",
    ]
    lines += [f"  {t}  {_one_line(tests_b[t].get('message') or tests_b[t]['status'])}" for t in newly_failing]
    lines.append(f"Newly passing {len(newly_passing)}")
    lines += [f"  {t}" for t in newly_passing]
    return "\n".join(lines)
