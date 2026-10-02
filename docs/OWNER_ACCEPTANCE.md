# Permanent owner release and clean-install acceptance

PR #2 stays DRAFT and UNMERGED until the owner reports physical acceptance.
Do not uninstall, clear data or run an APK installer until the owner has exported and verified their old backup and a permanently signed acceptance APK exists.

## What the old backup covers

Inspect the OLD installed app's Settings → Back up owner data. Keep the exported UTF-8 JSON outside the app's storage, preferably in two independent locations. It must have `format: speed-buddy-owner-backup` and a recognized version, not a SQLite/raw database file. Verify it opens as JSON and has the expected cameras and settings; inspect other sections as applicable. Do not assume an export succeeded because a file was created.

Repository history has two lineages:

| Old backup version | Exported owner data | Restore in this candidate |
| --- | --- | --- |
| 1 (main/offline baseline) | Personal cameras and six settings: overspeed, speedCamera, redCamera, cameraSound, vibrate, tolerance | Active cameras/settings |
| 2–8 (map preview, highest known app 0.1.8/code 9) | Personal cameras, preview settings, and the correction/suppression/alias/junction sections present in that format | Compatible personal cameras and the six settings active; complete original JSON archived before writes |
| 8 specifically | Cameras including bidirectional/junction fields; cameraCorrections; roadLimits; suppressedCameraIds; roadCorrections; cameraAliases; junctions; preview settings | Extra preview settings, bidirectional/junction behavior, camera corrections, suppressions, legacy road limits/corrections, aliases and junctions remain **archived only, not active** |
| 9 (offline baseline) | Personal cameras, six settings, direction-scoped road overrides, learned boundaries, and exact retained legacy JSON | All current fields active; retained legacy fields remain archived only |
| 10 (this UX candidate) | v9 data plus original source limit, correction location/time/accuracy and explicit Unknown/National road selections | All current correction evidence and selections active; retained preview fields remain archived only |

The map preview deliberately excludes temporary mobile-camera reports. They cannot be recovered from that export. Record anything important manually before considering uninstall. Downloaded OSM/Lufop data and the road cache are recreatable and are not exported. Re-import the original Lufop camera archive if needed. Android automatic/raw database restore is not the owner migration route.

The restore review lists unsupported fields before confirmation. Full legacy JSON is saved and read back before any camera/settings/road write; subsequent v9 exports include it verbatim. Archived suppressions/camera direction corrections do **not** affect warnings in this branch. If their active behavior is essential, keep the old installation pending a separate compatibility solution. Do not infer complete behavioral restoration from a successful import. The original external backup remains the recovery authority.

## Permanent signer and future CI setup

The owner-authorized new permanent signer is pinned in `docs/owner-signing-certificate.json`:

`ddcf05c06ca3442774ea24273ea9a31c213bbc78520032cfa2994c2118d2f947`

It is RSA 3072-bit, alias `speedbuddy-owner`, with a 30-year certificate. Its protected keystore and separate recovery password were saved and independently retrieved with matching bytes. No private signing material is committed. Reuse this identity; do not generate another key. The old debug identities cannot update into this lineage in place.

The current acceptance APK may be built and signed locally from a clean checkout of the final exact remote SHA using the recovered key, then checked with `scripts/verify_owner_apk.py` against this public pin and embedded source identity. This happens only after both CI runs pass. CI automatic signing still needs the four repository secrets and matching public variable; absence means no **CI-produced** owner APK. It does not invalidate a locally produced and independently verified permanent-signed APK.

On your trusted computer install a JDK (`keytool`), Python 3 and GitHub CLI (`gh`), and authenticate `gh` with repository Actions secrets/variables write permission for `traynor1987/Speedbuddy`. No key or password should be sent to chat.

Check out the exact candidate feature SHA supplied in the handoff:

```sh
git clone --branch feat/offline-road-limits https://github.com/traynor1987/Speedbuddy.git
cd Speedbuddy
git checkout EXACT_CANDIDATE_SHA
python3 scripts/setup_owner_signer.py --keystore /private/path/SpeedBuddy-owner-permanent-ddcf05c06ca3.jks
```

Recover the saved permanent key and its separate password onto that trusted computer. The script prompts invisibly and checks the tracked certificate plus any existing CI pin; `--create` is now refused because the permanent identity exists. Protect the key with your OS account permissions. Make an independent protected backup of the key and store the password separately; only then type `BACKED UP` locally. The script sends four secrets directly through `gh` stdin (which locally encrypts them):

- `SPEED_BUDDY_KEYSTORE_BASE64`
- `SPEED_BUDDY_STORE_PASSWORD`
- `SPEED_BUDDY_KEY_ALIAS`
- `SPEED_BUDDY_KEY_PASSWORD`

It sets the public repository variable `SPEED_BUDDY_SIGNING_CERT_SHA256` to the same tracked fingerprint. This fingerprint may safely be shared; it contains no private key. Keep the local key and its backup for all future releases. For interrupted setup, rerun **without** `--create` and with the same recovered keystore path. Never solve a failure by generating another identity.

Re-run the exact candidate's **PUSH** Android workflow using Actions → run → Re-run all jobs. Reruns preserve the original SHA. The signed job waits for JVM/build/lint and Android API 35 instrumentation jobs; no secret setup means no acceptance artifact, and incomplete setup/pin mismatch fails closed. PR jobs never receive signing secrets. A temporary runner copy of the key is removed and is not uploaded/cached. Installable debug validation APKs are not published.

Release compilation is deliberately unsigned in Gradle; the owner job uses Android `apksigner` with the permanent key. It verifies the package, release/nondebuggable flag, version, cryptographic signature, pinned certificate and embedded `assets/owner-build.json`. The artifact `speed-buddy-owner-acceptance-<full SHA>` contains `SpeedBuddy-0.2.1-owner-acceptance.apk` and public `owner-build.json` with APK SHA-256, source SHA and certificate. This workflow is the established future owner signing mechanism.

Version baseline is **0.2.1 / code 10**, above repository-history code 9 and previous offline name 0.2.0. Keep the same package `uk.co.traynor.speedbuddy`, key/pin and monotonically higher version codes for future in-place updates. There is no compatibility promise for the abandoned ephemeral installation.

## Owner install sequence — only after backup and signed artifact verification

1. Open OLD Speed Buddy, stop Driving, export its owner backup.
2. Verify the external file is readable/recognized and the expected records are present. Keep two copies; review the archive-only limitations above. Report backup completion, version and coverage before uninstall.
3. Only after that verification and confirmation of the signed artifact, uninstall the old app yourself.
4. Install the permanently signed candidate fresh and launch it.
5. Stop Driving; Settings → Restore owner data → select the original JSON. Review active versus archived fields before confirming.
6. Verify actual restored cameras/settings and applicable road corrections. Keep the original backup. Re-import downloaded cameras separately if needed.

No backup success or restore success is assumed until you report it. New v10 export is read back and parsed before the app claims success. Restore merges owner records; it never copies a raw database. A multi-store failure is reported as incomplete, with the verified complete source retained for retry.

## Physical acceptance

Only while safe/parked, operate menus/network controls. Verify:

- Normal launch, restored owner data, Start Driving; road data populates without blocking Drive.
- Known limits; internet off/poor signal keeps saved limits; close/reopen retains data; phone restart where practical.
- 30 → 20: 30 current before boundary, 20 may be upcoming; no crash. Same-limit continuation does not briefly become Unavailable.
- Tap the main speed-limit sign → choose a large UK limit sign; verify it applies to the actual matched road. Unknown and National need no typing.
- Known 30/60 → connected unknown road shows a temporary `!`, which drops on expiry, poor match or a reliable correction.
- Tap the sign at the real boundary → This limit starts here; repeat later and after restart. Optional Changed too soon uses the same picker and starts-here action. Verify reverse-direction isolation.
- Weak brief 40 between 60 and 30 stays a candidate; a real short 40 zone still confirms from strong progression.
- Fixed-camera warnings, camera direction and audio; background/resume; Stop/Start Driving.
- Previous lower-limit alert/crash case does not recur.

Report only observed results and exact APK/source/certificate identity. Green CI does not authorize merge. PR #2 remains draft until owner acceptance and final review.
