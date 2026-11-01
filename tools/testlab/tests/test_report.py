from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import report  # noqa: E402
from report import Result  # noqa: E402


def meta(**overrides) -> dict:
    base = {"platform": "ios", "tier": "smoke", "run_id": "20261027-120000-1", "started": "2026-10-27T12:00:00-07:00",
            "wall_seconds": 125.0, "devices": [{"name": "TestLab 1", "udid": "U1"}],
            "shards": [{"device": "TestLab 1", "tests": [], "seconds": 120.0}]}
    base.update(overrides)
    return base


class MergeTest(unittest.TestCase):
    def test_rerun_passes_of_failed_or_unrun_tests_are_flaky_and_other_reruns_replace(self):
        first = {"a": Result("failed", 30, "boom"), "b": Result("not_run", 0, ""), "c": Result("failed", 9, "x"),
                 "d": Result("passed", 4)}
        rerun = {"a": Result("passed", 5), "b": Result("passed", 6), "c": Result("failed", 8, "y")}
        merged = report.merge(first, rerun)
        self.assertEqual(merged["a"], Result("flaky", 5, "passed on rerun after: boom"))
        self.assertEqual(merged["b"], Result("flaky", 6, "passed on rerun after: not run"))
        self.assertEqual(merged["c"], Result("failed", 8, "y"))
        self.assertEqual(merged["d"], Result("passed", 4))


class OutputsTest(unittest.TestCase):
    def test_last_failed_lists_failed_and_unrun_and_empties_when_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "last-failed.txt"
            report.write_last_failed(path, {"a": Result("failed", 1, "x"), "b": Result("flaky", 1, "y"),
                                            "c": Result("not_run", 0), "d": Result("passed", 1)})
            self.assertEqual(path.read_text(), "a\nc\n")
            report.write_last_failed(path, {"a": Result("passed", 1)})
            self.assertEqual(path.read_text(), "")

    def test_junit_counts_unrun_as_failures_and_flaky_as_passes(self):
        results = {"VANLIFEUITests/testA": Result("passed", 10), "VANLIFEUITests/testB": Result("failed", 20, "boom"),
                   "VANLIFEUITests/testC": Result("flaky", 30, "passed on rerun after: x"),
                   "VANLIFEUITests/testD": Result("skipped", 0), "VANLIFEUITests/testE": Result("not_run", 0)}
        with tempfile.TemporaryDirectory() as tmp:
            report.write_outputs(Path(tmp), meta(), results)
            suite = ET.parse(Path(tmp) / "junit.xml").getroot()
        self.assertEqual((suite.get("tests"), suite.get("failures"), suite.get("skipped")), ("5", "2", "1"))
        cases = {c.get("name"): c for c in suite.iter("testcase")}
        self.assertEqual(cases["testB"].find("failure").get("message"), "boom")
        self.assertIsNotNone(cases["testE"].find("failure"))
        self.assertIsNone(cases["testC"].find("failure"))
        self.assertIn("flaky", cases["testC"].find("system-out").text)
        self.assertEqual(cases["testA"].get("classname"), "VANLIFEUITests")

    def test_summary_fails_a_run_with_unrun_tests_and_points_at_the_rerun_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = report.write_outputs(Path(tmp), meta(platform="android"),
                                           {"A#x": Result("passed", 1), "A#y": Result("not_run", 0, "did not run")})
            stored = json.loads((Path(tmp) / "results.json").read_text())
        self.assertIn("FAILED: 1 passed · 0 failed · 0 flaky · 0 skipped · 1 not run", summary)
        self.assertIn("make -C android uitest-failed", summary)
        self.assertEqual(stored["tests"]["A#y"]["status"], "not_run")

    def test_summary_passes_with_flaky_tests(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = report.write_outputs(Path(tmp), meta(), {"A/x": Result("flaky", 3, "passed on rerun after: z")})
        self.assertIn("PASSED: 0 passed · 0 failed · 1 flaky", summary)
        self.assertNotIn("uitest-failed", summary)


class PruneTest(unittest.TestCase):
    def test_keeps_the_newest_runs_and_points_latest_at_the_newest(self):
        with tempfile.TemporaryDirectory() as tmp:
            runs = Path(tmp)
            for name in ("20261027-100000-1", "20261027-110000-2", "20261027-120000-3"):
                (runs / name).mkdir()
            report.prune_runs(runs, 2)
            self.assertEqual(sorted(p.name for p in runs.iterdir() if not p.is_symlink()),
                             ["20261027-110000-2", "20261027-120000-3"])
            self.assertEqual(os.readlink(runs / "latest"), "20261027-120000-3")


class CompareTest(unittest.TestCase):
    def test_paired_time_and_newly_failing(self):
        def run(tests):
            return {"tier": "full", "wall_seconds": 100, "tests": {
                t: {"status": s, "seconds": sec, "message": m} for t, (s, sec, m) in tests.items()}}
        a = run({"t1": ("passed", 100, ""), "t2": ("flaky", 50, "x"), "t3": ("passed", 40, ""),
                 "t4": ("failed", 10, "old")})
        b = run({"t1": ("passed", 70, ""), "t2": ("passed", 35, ""), "t3": ("failed", 5, "broke"),
                 "t4": ("passed", 12, "")})
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "a.json").write_text(json.dumps(a))
            (Path(tmp) / "b").mkdir()
            (Path(tmp) / "b" / "results.json").write_text(json.dumps(b))
            text = report.compare(Path(tmp) / "a.json", Path(tmp) / "b")
        self.assertIn("Paired tests 2 (green in both): A 2m 30s (150.0s) → B 1m 45s (105.0s), -30.0%", text)
        self.assertIn("Newly failing 1\n  t3  broke", text)
        self.assertIn("Newly passing 1\n  t4", text)


if __name__ == "__main__":
    unittest.main()
