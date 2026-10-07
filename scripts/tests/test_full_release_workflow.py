"""Guard against reintroducing the map branch's obsolete alternate release pipeline."""
from pathlib import Path
import unittest
import subprocess

ROOT = Path(__file__).resolve().parents[2]

class FullReleaseWorkflowTest(unittest.TestCase):
    def test_only_verified_permanent_signing_pipeline_is_enabled(self):
        self.assertFalse(subprocess.check_output(['git', 'ls-files', '--', '.github/workflows/signed-release.yml'], cwd=ROOT, text=True).strip(),
                         'obsolete release pipeline must not coexist with permanent owner signing')
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('needs: [build, database-tests]', workflow)
        self.assertIn("docs/owner-signing-certificate.json", workflow)
        self.assertIn('scripts/verify_owner_apk.py', workflow)
        self.assertIn('--version-code 20 --version-name 0.4.0', workflow)
        self.assertIn("github.ref == 'refs/heads/feat/regional-offline-road-data-0.4.0'", workflow)
        self.assertIn('v0.4.0-regional-offline-', workflow)
        self.assertIn('docs/OWNER_ACCEPTANCE.md', workflow)
        self.assertIn('Remove temporary private key', workflow)

if __name__ == '__main__':
    unittest.main()
