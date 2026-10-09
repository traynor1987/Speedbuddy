#!/usr/bin/env python3
"""Exercise actual release signing/verification with a disposable TEST ONLY key.

Never publishes an APK or establishes an owner signing identity.
"""
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
from verify_owner_apk import verify


def run(args, env=None):
    return subprocess.run(args, env=env, check=True, capture_output=True).stdout


def main():
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '36.0.0'
    unsigned = Path('app/build/outputs/apk/release/app-release-unsigned.apk')
    sha = os.environ['SPEED_BUDDY_SOURCE_SHA']
    with tempfile.TemporaryDirectory(prefix='speedbuddy-test-signing-') as folder:
        key = Path(folder) / 'TEST-ONLY.jks'
        apk = Path(folder) / 'TEST-ONLY.apk'
        env = os.environ.copy()
        env['TEST_SIGNING_PASSWORD'] = secrets.token_urlsafe(32)
        run(['keytool', '-genkeypair', '-keystore', str(key), '-alias', 'test-only',
             '-storetype', 'JKS', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1',
             '-dname', 'CN=Speed Buddy TEST ONLY', '-storepass:env', 'TEST_SIGNING_PASSWORD',
             '-keypass:env', 'TEST_SIGNING_PASSWORD'], env)
        certificate = run(['keytool', '-exportcert', '-keystore', str(key), '-alias', 'test-only',
                           '-storepass:env', 'TEST_SIGNING_PASSWORD'], env)
        fingerprint = hashlib.sha256(certificate).hexdigest()
        run([str(tools / 'apksigner'), 'sign', '--ks', str(key), '--ks-key-alias', 'test-only',
             '--ks-pass', 'env:TEST_SIGNING_PASSWORD', '--key-pass', 'env:TEST_SIGNING_PASSWORD',
             '--out', str(apk), str(unsigned)], env)
        verify(apk, tools, sha, fingerprint, 20, '0.4.0')
        for bad_sha, bad_pin in [(sha, '0' * 64), ('0' * 40, fingerprint)]:
            try:
                verify(apk, tools, bad_sha, bad_pin, 20, '0.4.0')
            except ValueError:
                pass
            else:
                raise AssertionError('Mismatched artifact identity was accepted')
    print('Actual release signing, package/version/SHA verification and mismatch rejection PASS (disposable test key destroyed).')


if __name__ == '__main__':
    main()
