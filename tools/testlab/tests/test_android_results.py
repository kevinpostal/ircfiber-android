from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import android  # noqa: E402
from report import Result  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
EXPECTED = ["FiberIrcUiTest#quickSwitcherFiltersBuffers", "FiberIrcUiTest#offlineShowsReconnect",
            "FiberIrcUiTest#largeFontShowsEveryTextWhole", "AppRouterUiTest#webLinkOpensBuffer",
            "FiberIrcUiTest#neverRan"]


class AndroidResultsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.results = android.android_results([FIXTURES / "TEST-pixel6Api35-_app-.xml"], EXPECTED)

    def test_passed_and_skipped(self):
        self.assertEqual(self.results["FiberIrcUiTest#quickSwitcherFiltersBuffers"], Result("passed", 20.1))
        self.assertEqual(self.results["FiberIrcUiTest#largeFontShowsEveryTextWhole"].status, "skipped")

    def test_failure_message_is_first_line_and_first_frame_in_our_code(self):
        self.assertEqual(self.results["FiberIrcUiTest#offlineShowsReconnect"], Result(
            "failed", 30.2, "java.lang.AssertionError: Failed to assert the following: (TestTag = 'reconnect') "
                            "at com.kevinpostal.fiberirc.FiberIrcUiTest.offlineShowsReconnect(FiberIrcUiTest.kt:612)"))

    def test_error_counts_as_failure(self):
        crashed = self.results["AppRouterUiTest#webLinkOpensBuffer"]
        self.assertEqual(crashed.status, "failed")
        self.assertIn("Process crashed", crashed.message)

    def test_missing_tests_did_not_run(self):
        self.assertEqual(self.results["FiberIrcUiTest#neverRan"].status, "not_run")

    def test_no_xml_means_nothing_ran(self):
        results = android.android_results([], EXPECTED)
        self.assertEqual({r.status for r in results.values()}, {"not_run"})


if __name__ == "__main__":
    unittest.main()
