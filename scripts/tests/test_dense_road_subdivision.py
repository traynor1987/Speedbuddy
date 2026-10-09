"""Regression guards for bounded dense-city road retrieval."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
CACHE=(ROOT/"app/src/main/java/uk/co/traynor/speedbuddy/RoadCache.kt").read_text()
DOWNLOAD=(ROOT/"app/src/main/java/uk/co/traynor/speedbuddy/RoadDownload.kt").read_text()
DATABASE=(ROOT/"app/src/main/java/uk/co/traynor/speedbuddy/RoadDb.kt").read_text()
SERVICE=(ROOT/"app/src/main/java/uk/co/traynor/speedbuddy/DrivingService.kt").read_text()

class DenseRoadSubdivisionTest(unittest.TestCase):
    def test_decoded_response_cap_is_preserved_and_explicit(self):
        self.assertIn("RoadResponseTooLargeException", DOWNLOAD)
        self.assertIn("output.size()+n>4_000_000", DOWNLOAD)
        self.assertNotIn("output.size()+n<=64_000_000", DOWNLOAD)

    def test_oversize_moves_to_bounded_current_child_not_an_unbounded_retry(self):
        self.assertIn("MAX_ROAD_SUBDIVISION_DEPTH = 3", CACHE)
        self.assertIn("childContaining(point)", CACHE)
        self.assertIn("RoadSubdivision.afterOversize", DOWNLOAD)
        self.assertIn("Road response too large at minimum dense-city region", DOWNLOAD)

    def test_subdivision_is_persistent_but_not_parent_coverage(self):
        self.assertIn("CREATE TABLE tile_subdivisions", DATABASE)
        self.assertIn("fun markSubdivided", DATABASE)
        self.assertIn("SELECT id,fetched FROM tiles WHERE complete=1", DATABASE)

    def test_driver_uses_one_authoritative_downloader_and_prioritises_current_region(self):
        self.assertIn("adaptiveDownloader.fetch(target,priorityPoint)", SERVICE)
        self.assertIn("fix.point.takeIf(target::contains)", SERVICE)
        self.assertNotIn("LocationManager()", DOWNLOAD)

if __name__ == "__main__":
    unittest.main()
