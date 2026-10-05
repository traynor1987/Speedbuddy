"""Fail-closed artifact verification, including a genuine APK integration in CI."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('verify_owner_apk', Path(__file__).parents[1] / 'verify_owner_apk.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ArtifactVerificationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / 'owner.apk'
        self.sha = 'a' * 40
        self.pin = 'b' * 64
        with zipfile.ZipFile(self.apk, 'w') as apk:
            apk.writestr('assets/owner-build.json', json.dumps({'sourceSha': self.sha, 'versionCode': 10, 'versionName': '0.2.1'}))
        self.badging = "package: name='uk.co.traynor.speedbuddy' versionCode='10' versionName='0.2.1'"
        self.cert = f'Signer #1 certificate SHA-256 digest: {self.pin}\nSigner #1 certificate DN: CN=Speed Buddy Owner'

    def verify(self, cert=None, badging=None, sha=None):
        answers = [subprocess.CompletedProcess([], 0, stdout=self.cert if cert is None else cert),
                   subprocess.CompletedProcess([], 0, stdout=self.badging if badging is None else badging)]
        with patch.object(module.subprocess, 'run', side_effect=answers):
            return module.verify(self.apk, Path('/tools'), sha or self.sha, self.pin, 10, '0.2.1')

    def test_pinned_source_identity_emits_public_manifest(self):
        result = self.verify()
        self.assertEqual(result['certificateSha256'], self.pin)
        self.assertEqual(result['sourceSha'], self.sha)
        self.assertEqual(len(result['apkSha256']), 64)

    def test_signer_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            self.verify(cert=self.cert.replace(self.pin, 'c' * 64))

    def test_ephemeral_debug_key_rejected_even_if_pinned(self):
        with self.assertRaises(ValueError):
            self.verify(cert=self.cert.replace('CN=Speed Buddy Owner', 'CN=Android Debug'))

    def test_other_source_sha_rejected(self):
        with self.assertRaises(ValueError):
            self.verify(sha='c' * 40)

    def test_wrong_package_version_and_debuggable_rejected(self):
        for badging in [self.badging.replace('versionCode=\'10\'', 'versionCode=\'4\''),
                        self.badging.replace('uk.co.traynor.speedbuddy', 'other.package'),
                        self.badging + '\napplication-debuggable']:
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                self.verify(badging=badging)

    def test_bad_signature_is_not_ignored(self):
        with patch.object(module.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, [])):
            with self.assertRaises(subprocess.CalledProcessError):
                module.verify(self.apk, Path('/tools'), self.sha, self.pin, 10, '0.2.1')


if __name__ == '__main__':
    unittest.main()
