from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import discover  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"


class KotlinDiscoveryTest(unittest.TestCase):
    def test_package_class_and_annotated_tests(self):
        tests = discover.kotlin_tests([FIXTURES / "SampleUiTest.kt"])
        self.assertEqual(tests, {
            "SampleUiTest#plain": "com.example.app.SampleUiTest#plain",
            "SampleUiTest#annotated": "com.example.app.SampleUiTest#annotated",
            "SampleUiTest#sameLine": "com.example.app.SampleUiTest#sameLine",
        })

    def test_live_e2e_files_are_skipped(self):
        tests = discover.kotlin_tests([FIXTURES / "LiveSampleTest.kt", FIXTURES / "SampleUiTest.kt"])
        self.assertNotIn("LiveSampleTest#againstProduction", tests)
        self.assertEqual(len(tests), 3)


ALL = ["A/testOne", "A/testTwo", "B/testThree", "B/testFour"]


class SelectTest(unittest.TestCase):
    def test_smoke_entry_missing_from_sources_is_an_error_naming_it(self):
        with self.assertRaises(discover.SelectionError) as raised:
            discover.select(ALL, "smoke", None, ["A/testOne", "A/testGone"], "unused")
        self.assertIn("A/testGone", str(raised.exception))

    def test_smoke_keeps_the_list_order(self):
        self.assertEqual(discover.select(ALL, "smoke", None, ["B/testFour", "A/testOne"], "unused"),
                         (["B/testFour", "A/testOne"], "smoke"))

    def test_only_is_the_union_of_its_regexes_in_discovery_order(self):
        ids, label = discover.select(ALL, None, ["Four$", "^A/testO"], [], "unused")
        self.assertEqual((ids, label), (["A/testOne", "B/testFour"], "only"))

    def test_only_without_a_match_is_an_error(self):
        with self.assertRaisesRegex(discover.SelectionError, "no tests match"):
            discover.select(ALL, None, ["zzz"], [], "unused")

    def test_no_tier_means_full(self):
        self.assertEqual(discover.select(ALL, None, None, [], "unused"), (ALL, "full"))

    def test_failed_without_a_file_selects_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(discover.select(ALL, "failed", None, [], Path(tmp) / "last-failed.txt"), ([], "failed"))

    def test_failed_drops_ids_no_longer_in_the_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "last-failed.txt"
            path.write_text("B/testThree\nA/testRenamed\n\nA/testOne\nB/testThree\n")
            self.assertEqual(discover.select(ALL, "failed", None, [], path), (["B/testThree", "A/testOne"], "failed"))


if __name__ == "__main__":
    unittest.main()
