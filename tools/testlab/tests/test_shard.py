from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import shard  # noqa: E402
from report import Result  # noqa: E402


class EstimatesTest(unittest.TestCase):
    def test_unknown_tests_are_estimated_at_the_median(self):
        self.assertEqual(shard.estimates(["new", "x"], {"x": 10, "y": 30, "z": 100}), {"new": 30.0, "x": 10.0})

    def test_without_timings_every_test_is_sixty_seconds(self):
        self.assertEqual(shard.estimates(["a", "b"], {}), {"a": 60.0, "b": 60.0})


class UpdateTimingsTest(unittest.TestCase):
    def test_moving_average_of_passed_and_flaky_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "timings.json"
            path.write_text(json.dumps({"a": 10, "b": 50, "c": 30}))
            shard.update_timings(path, {
                "a": Result("passed", 20), "b": Result("failed", 100, "boom"), "c": Result("flaky", 10, "x"),
                "d": Result("passed", 7.04), "e": Result("not_run", 0), "f": Result("skipped", 0.1),
            })
            self.assertEqual(json.loads(path.read_text()), {"a": 15.0, "b": 50, "c": 20.0, "d": 7.0})


if __name__ == "__main__":
    unittest.main()
