import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('setup_owner_signer', Path(__file__).parents[1] / 'setup_owner_signer.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OwnerSignerSetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.key = Path(self.tmp.name) / 'owner.jks'
        pin_patch=patch.object(module,'PIN_FILE',Path(self.tmp.name)/'public-pin.json',create=True)
        pin_patch.start();self.addCleanup(pin_patch.stop)

    def invoke(self, answers):
        with patch.object(module.sys, 'argv', ['setup_owner_signer.py', '--create', '--keystore', str(self.key)]), \
             patch.object(module.shutil, 'which', return_value='/tool'), \
             patch.object(module, 'run', side_effect=answers) as runner, \
             patch.object(module.getpass, 'getpass') as prompt:
            with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                module.main()
            prompt.assert_not_called()
            self.assertFalse(self.key.exists())
            return runner

    def test_existing_key_is_never_overwritten(self):
        self.key.write_bytes(b'existing test key')
        with patch.object(module.sys, 'argv', ['setup_owner_signer.py', '--create', '--keystore', str(self.key)]), \
             patch.object(module.shutil, 'which', return_value='/tool'), patch.object(module, 'run', return_value=b''), \
             self.assertRaises(ValueError):
            module.main()
        self.assertEqual(self.key.read_bytes(), b'existing test key')

    def test_existing_public_pin_prevents_new_key_generation(self):
        self.invoke([b'', json.dumps([{'name': 'SPEED_BUDDY_SIGNING_CERT_SHA256', 'value': 'a' * 64}]).encode()])

    def test_failed_pin_lookup_cannot_authorize_new_identity(self):
        self.invoke([b'', subprocess.CalledProcessError(1, [])])

    def test_redirected_input_never_reads_a_password(self):
        with patch.object(module.sys.stdin, 'isatty', return_value=False):
            self.invoke([b'', b'[]'])

    def test_tracked_pin_is_reused_when_ci_variable_is_not_configured(self):
        module.PIN_FILE.write_text(json.dumps({'certificateSha256':'b'*64}))
        self.assertEqual(module.resolve_certificate_pin(''), 'b'*64)

    def test_conflicting_ci_pin_cannot_replace_tracked_identity(self):
        module.PIN_FILE.write_text(json.dumps({'certificateSha256':'b'*64}))
        with self.assertRaises(ValueError): module.resolve_certificate_pin('a'*64)

    def test_invalid_tracked_pin_cannot_be_treated_as_no_signer(self):
        module.PIN_FILE.write_text('{}')
        with self.assertRaises(ValueError): module.resolve_certificate_pin('')


if __name__ == '__main__':
    unittest.main()
