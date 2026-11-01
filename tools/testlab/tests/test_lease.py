from __future__ import annotations

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

TESTLAB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TESTLAB))

import lease  # noqa: E402
from config import UsageError  # noqa: E402

INFO = {"platform": "ios", "worktree": "/tmp/worktree", "command": "testlab ios"}


class LeaseTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.home = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_capacity_limits_slots_and_release_frees_them(self):
        first = lease.try_acquire(self.home, 3, 2, 1, INFO)
        second = lease.try_acquire(self.home, 3, 2, 1, INFO)
        self.assertEqual((first.slots, second.slots), ([1, 2], [3]))
        self.assertIsNone(lease.try_acquire(self.home, 3, 1, 1, INFO))
        first.release()
        third = lease.try_acquire(self.home, 3, 4, 1, INFO)
        self.assertEqual(third.slots, [1, 2])
        second.release()
        third.release()

    def test_below_minimum_takes_nothing(self):
        holder = lease.try_acquire(self.home, 2, 1, 1, INFO)
        self.assertIsNone(lease.try_acquire(self.home, 2, 2, 2, INFO))
        # The free slot was not kept by the failed attempt.
        other = lease.try_acquire(self.home, 2, 1, 1, INFO)
        self.assertEqual(other.slots, [2])
        holder.release()
        other.release()

    def test_minimum_above_capacity_is_a_usage_error_not_a_wait(self):
        with self.assertRaises(UsageError):
            lease.acquire(self.home, 1, 2, 2, INFO, poll=0.01)

    def test_holders_describe_held_slots_only(self):
        held = lease.try_acquire(self.home, 4, 2, 1, INFO)
        found = lease.holders(self.home, 4)
        self.assertEqual([(h["slot"], h["platform"], h["worktree"]) for h in found],
                         [(1, "ios", "/tmp/worktree"), (2, "ios", "/tmp/worktree")])
        held.release_slot(1)
        self.assertEqual([h["slot"] for h in lease.holders(self.home, 4)], [2])
        held.release()
        self.assertEqual(lease.holders(self.home, 4), [])

    def test_slot_of_a_killed_process_frees(self):
        code = textwrap.dedent(f"""
            import sys, time
            from pathlib import Path
            sys.path.insert(0, {str(TESTLAB)!r})
            import lease
            held = lease.try_acquire(Path({str(self.home)!r}), 1, 1, 1, {{"platform": "android"}})
            print("held" if held else "free", flush=True)
            time.sleep(60)
        """)
        child = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(child.stdout.readline().strip(), "held")
            self.assertIsNone(lease.try_acquire(self.home, 1, 1, 1, INFO))
            self.assertEqual([h["pid"] for h in lease.holders(self.home, 1)], [child.pid])
        finally:
            child.kill()
            child.wait()
            child.stdout.close()
        after = lease.try_acquire(self.home, 1, 1, 1, INFO)
        self.assertIsNotNone(after)
        after.release()


if __name__ == "__main__":
    unittest.main()
