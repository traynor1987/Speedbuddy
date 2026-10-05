#!/usr/bin/env python3
"""Run ONLY on the owner's trusted computer. No passwords in files or stdout."""
import argparse
import base64
import getpass
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys

PIN_FILE=Path(__file__).resolve().parents[1]/'docs'/'owner-signing-certificate.json'

def resolve_certificate_pin(variable_pin):
    tracked=''
    if PIN_FILE.exists():
        try: tracked=json.loads(PIN_FILE.read_text())['certificateSha256']
        except (KeyError, json.JSONDecodeError): raise ValueError('Invalid tracked owner certificate; recover its identity first')
        if not isinstance(tracked,str) or not re.fullmatch(r'[0-9a-f]{64}',tracked):
            raise ValueError('Invalid tracked owner certificate; recover its identity first')
    if tracked and variable_pin and tracked!=variable_pin:
        raise ValueError('CI variable differs from tracked owner certificate; no signing identity changed')
    return tracked or variable_pin


def run(args, *, value=None, env=None):
    return subprocess.run(args, input=value, env=env, capture_output=True, check=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", type=Path, default=Path.home() / ".speedbuddy-signing" / "speedbuddy-owner.jks")
    parser.add_argument("--create", action="store_true", help="Create once; never overwrite an existing key")
    args = parser.parse_args()
    for name in ("gh", "keytool"):
        if not shutil.which(name):
            raise ValueError(f"Install {name} on this trusted computer first")
    repo = "traynor1987/Speedbuddy"
    run(["gh", "auth", "status"])
    key = args.keystore.expanduser().resolve()
    if args.create and key.exists():
        raise ValueError("Keystore already exists; omit --create to reuse it")
    if not args.create and not key.is_file():
        raise ValueError("Keystore not found; use --create only for the first permanent key")
    # A failed lookup must never be mistaken for permission to create a new identity.
    variables = json.loads(run(["gh", "variable", "list", "--repo", repo, "--json", "name,value"]))
    pin = next((item["value"].strip().lower() for item in variables
                if item["name"] == "SPEED_BUDDY_SIGNING_CERT_SHA256"), "")
    pin=resolve_certificate_pin(pin)
    # Refuse generation if an established pin exists. Never rotate automatically.
    if args.create and pin:
        raise ValueError("An owner certificate is already pinned; recover and reuse its key")
    if not sys.stdin.isatty():
        raise ValueError("Run setup interactively in a private terminal; redirected password input is not allowed")
    password = getpass.getpass("Permanent keystore password (hidden): ")
    if len(password) < 12:
        raise ValueError("Use a password of at least 12 characters")
    env = os.environ.copy()
    env["SPEED_BUDDY_SETUP_PASSWORD"] = password
    alias = "speedbuddy-owner"
    if args.create:
        if password != getpass.getpass("Confirm password (hidden): "):
            raise ValueError("Passwords differ")
        new_directory = not key.parent.exists()
        key.parent.mkdir(parents=True, exist_ok=True)
        if os.name != "nt" and new_directory:
            os.chmod(key.parent, 0o700)
        previous_umask = os.umask(0o077)
        try:
            run(["keytool", "-genkeypair", "-keystore", str(key), "-storetype", "JKS",
                 "-alias", alias, "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
                 "-validity", "10950", "-dname", "CN=Speed Buddy Owner, O=Speed Buddy, C=GB",
                 "-storepass:env", "SPEED_BUDDY_SETUP_PASSWORD", "-keypass:env", "SPEED_BUDDY_SETUP_PASSWORD"], env=env)
        finally:
            os.umask(previous_umask)
        if os.name != "nt":
            os.chmod(key, 0o600)
    cert = run(["keytool", "-exportcert", "-keystore", str(key), "-alias", alias,
                "-storepass:env", "SPEED_BUDDY_SETUP_PASSWORD"], env=env)
    fingerprint = hashlib.sha256(cert).hexdigest()
    if pin and pin != fingerprint:
        raise ValueError("Keystore certificate differs from the existing pin; nothing uploaded")
    print(f"Public certificate SHA-256: {fingerprint}")
    print(f"Permanent keystore: {key}")
    print("Keep an independent private backup of this key and store the password separately.")
    if input("Type BACKED UP after both private backups are safely stored: ").strip() != "BACKED UP":
        raise ValueError("Key retained locally; CI configuration deferred until backups exist")
    secrets = {
        "SPEED_BUDDY_KEYSTORE_BASE64": base64.b64encode(key.read_bytes()),
        "SPEED_BUDDY_STORE_PASSWORD": password.encode(),
        "SPEED_BUDDY_KEY_ALIAS": alias.encode(),
        "SPEED_BUDDY_KEY_PASSWORD": password.encode(),
    }
    for name, value in secrets.items():
        # gh locally encrypts stdin before sending it to GitHub; never use --body for secrets.
        run(["gh", "secret", "set", name, "--repo", repo], value=value)
    run(["gh", "variable", "set", "SPEED_BUDDY_SIGNING_CERT_SHA256", "--repo", repo,
         "--body", fingerprint])
    print("Permanent owner signing configured. Re-run the candidate's PUSH CI run.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, EOFError, KeyboardInterrupt) as exc:
        # Subprocess output can contain sensitive data. Never print it or command arguments.
        message = str(exc) if isinstance(exc, ValueError) else "Setup did not complete; existing key retained. Check local tools/access and retry without --create."
        print(message, file=sys.stderr)
        sys.exit(1)
