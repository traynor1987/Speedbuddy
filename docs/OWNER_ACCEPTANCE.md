# Full Speed Buddy physical acceptance

Version 0.2.3 / code 12 corrects the physically rejected 0.2.2/code 11 candidate while preserving map/camera and offline work. The incomplete 0.2.1 acceptance candidate is withdrawn. PR #2 remains open, draft and unmerged until owner physical acceptance.

## Installation and backup gate

If permanently signed 0.2.1 or 0.2.2 is installed: create and verify an external JSON owner backup, then install the replacement 0.2.3 APK as an update. **No uninstall is needed.** Confirm launch and your existing cameras/settings. If you have an earlier map backup, import it while stopped to recover map owner records absent from 0.2.1.

For an historical ephemeral-debug installation only: create and verify its external JSON owner backup first. Uninstall only after the permanently signed exact-head APK is actually available and the backup's contents have been verified. Install that APK, confirm launch, then import the portable backup while stopped. Never restore a raw SQLite database.

The agent cannot observe owner backup creation or phone installation. No uninstall, restore success or physical test is assumed.

| Backup version | Preserved owner data in the complete build |
| --- | --- |
| 1 | Personal cameras and settings present in that format |
| 2–8 map lineage | Supported settings, personal cameras, camera corrections, suppressed IDs, numeric/typed road corrections, aliases and junction/bidirectional/member fields present in that format restore actively |
| 9–10 offline lineage | Personal cameras/settings, directed road selections/evidence and learned boundaries; supported map records from retained v1–8 originals are reactivated |
| 12 | All v11 fields plus durable boundary observations and local via-road boundary scope |
| 13 | All v12 fields plus explicit sharing policy on new ordinary two-way local corrections; old directed records retain their semantics |
| 11 combined | All above active fields and complete retained originals; retained archives are receipts and cannot resurrect deleted active records |

Export creates portable UTF-8 JSON, filters permanent personal cameras, includes full supported settings and both correction stores, writes through Android's file picker and reads the file back before reporting success. Import validates before writes and retains/verifies the complete original first. Temporary mobile reports are deliberately excluded from owner exports; downloaded OSM/Lufop data can be recreated. Keep the original Lufop ZIP if reimport is needed.

## Permanent signing

The permanent signer is already configured in GitHub Actions. Its public SHA-256 fingerprint is:

`ddcf05c06ca3442774ea24273ea9a31c213bbc78520032cfa2994c2118d2f947`

The new build uses the same signer as permanent 0.2.1. Future builds must monotonically increase versionCode and retain this identity. No signing setup is required from the owner; private material must remain outside source and release assets.

## Owner procedure

Do interactions while stopped or have a passenger operate the phone.

1. Create OLD-app backup; open/check the JSON and confirm it exists outside app storage before any uninstall.
2. Update permanent 0.2.1/0.2.2 in place. Only for an historical debug installation, perform the backup-gated clean install described above.
3. Confirm launch, Map, Settings, Updates, camera tools and diagnostics are present. Import a compatible historical backup if required; confirm important settings, cameras, corrections, suppression and junctions.
4. Start Driving online; observe progressive road-data coverage and known current limits. Test no connection, app restart and saved road-data availability.
5. Where practical, test known 30 → confidently matched unknown road. Observe `30 !` with “Assumed • not confirmed”; it must expire/drop with time, distance or uncertain matching.
6. Tap the main sign: eight large UK choices appear without a keyboard. Correct the matched road, revisit/restart, and confirm persistence. Map editing and map-data reset tools remain available while parked.
7. For a transition predicted early, tap the sign and select 60 while still in 60; select the new limit at the real signs. Revisit and observe old CURRENT/new UPCOMING until the saved boundary. Check reverse direction where practical; reset is available in map-data tools.
8. Observe any brief 60 → 40 → 30 sequence: unstable matching should remain candidate/upcoming rather than rapidly authoritative. Genuine normal and short zones must still confirm, including 30 → 20; the earlier lower-limit crash must not recur.
9. Confirm fixed/red/combined camera warnings, direction and audio, music handling and imported/personal cameras. Check mobile reports/junctions/average sections where available.
10. Confirm background/resume, rotation and Stop/Start Driving. Report results and any incorrect limit/camera behaviour before PR acceptance.

The 0.2.2 physical test failed. Retest the corrections using [PHYSICAL_RETEST.md](PHYSICAL_RETEST.md). Keep PR #2 draft/unmerged until the owner reports acceptance.
