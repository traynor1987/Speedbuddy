"""Regression guard for the physical same-road fallback window."""
from pathlib import Path
import unittest

SOURCE=(Path(__file__).resolve().parents[2]/"app/src/main/java/uk/co/traynor/speedbuddy/LimitDecision.kt").read_text()

class SameRoadContinuityTest(unittest.TestCase):
    def test_unmatched_same_way_uses_the_bounded_assumption_window_not_fifteen_seconds(self):
        self.assertIn("now-anchor.at in 0..90_000", SOURCE)
        self.assertNotIn("now-anchor.at in 0..15_000", SOURCE)

if __name__ == "__main__": unittest.main()
