#!/usr/bin/env python3
"""Verify an owner release and emit ONLY public build provenance."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


def verify(apk, tools, sha, fingerprint, version_code, version_name):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid source SHA")
    fingerprint = fingerprint.lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("Permanent certificate pin is missing/invalid")
    result = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                            check=True, capture_output=True, text=True)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", result.stdout)
    if certs != [fingerprint] or "CN=Android Debug" in result.stdout:
        raise ValueError("APK does not use the single pinned permanent release signer")
    badging = subprocess.run([str(tools / "aapt2"), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    expected = f"package: name='uk.co.traynor.speedbuddy' versionCode='{version_code}' versionName='{version_name}'"
    if not badging.startswith(expected):
        raise ValueError("APK identity/version does not match candidate")
    if re.search(r"^application-debuggable", badging, re.M):
        raise ValueError("Owner release must not be debuggable")
    with zipfile.ZipFile(apk) as archive:
        embedded = json.loads(archive.read("assets/owner-build.json"))
    if embedded != {"sourceSha": sha, "versionCode": version_code, "versionName": version_name}:
        raise ValueError("Embedded build identity differs from exact source/version")
    return {"sourceSha": sha, "package": "uk.co.traynor.speedbuddy", "versionCode": version_code,
            "versionName": version_name, "certificateSha256": fingerprint,
            "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--certificate", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    provenance = verify(args.apk, args.build_tools, args.source_sha, args.certificate, args.version_code, args.version_name)
    args.output.write_text(json.dumps(provenance, indent=2) + "\n")
    print(json.dumps(provenance, indent=2))


if __name__ == "__main__":
    main()
