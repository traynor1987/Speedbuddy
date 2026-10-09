"""Regression guard for the physical same-road fallback window."""
from pathlib import Path
import unittest

SOURCE=(Path(__file__).resolve().parents[2]/"app/src/main/java/uk/co/traynor/speedbuddy/LimitDecision.kt").read_text()

class SameRoadContinuityTest(unittest.TestCase):
    def test_unmatched_same_way_is_limited_to_two_seconds_and_thirty_metres(self):
        self.assertIn("now-anchor.at in 0..2_000", SOURCE)
        self.assertIn("distance>30", SOURCE)
        self.assertNotIn("now-anchor.at in 0..90_000", SOURCE)
        self.assertNotIn("distance>750", SOURCE)

if __name__ == "__main__": unittest.main()
