# Speed Buddy 0.4.0 owner acceptance

Candidate: **0.4.0 / code 20**, package `uk.co.traynor.speedbuddy`, branch `feat/regional-offline-road-data-0.4.0`, PR #7 open/draft/unmerged.

**Physical road acceptance: PENDING.** Automated validation does not establish posted-limit accuracy, camera coverage, battery behaviour or Android Auto device acceptance. Earlier failed road tests are not superseded by green CI.

## Exact build identity and installation

A published acceptance build must identify its exact source commit in the release notes and `owner-build.json`. The APK embeds the same public version/source identity, visible in Diagnostics. The sidecar also records the APK hash and permanent certificate. Do not confuse the latest source branch with an older installed APK. Correction cycles 2 and 3 publish source and CI reports only: no new owner APK is produced.

The permanent signing certificate remains pinned in `docs/owner-signing-certificate.json`:
`ddcf05c06ca3442774ea24273ea9a31c213bbc78520032cfa2994c2118d2f947`.

For an existing permanent installation, verify an external owner backup, then update in place with the same signer. **Do not uninstall a working permanent installation.** This milestone retains code 20; an intended replacement acceptance APK with the same code may be installed manually when available. The stable-only in-app update centre does not select development prereleases or same-version candidates.

For an historical ephemeral debug installation, create and verify its external JSON backup before considering a clean install. Uninstall only after the intended permanently signed, exact-source APK is available and the backup has been checked. The agent cannot verify your backup or installation remotely. Never copy a raw SQLite database into the app.

## Backup and restore

Current **owner backup version 13** preserves personal cameras, camera corrections/suppression/aliases, junction membership, supported settings, map road corrections, directed and shared road selections, learned boundaries and observations. Historical versions 1–12 retain the fields supported by their format. Complete original restore files are retained as receipts; old receipts do not resurrect removed active records.

Export through Android's document picker to storage outside the app, open/check the JSON, and retain it before an installation change. Restore while stopped and verify important cameras, corrections and settings afterward. Android automatic backup is disabled.

Regional pack databases, public OSM/Lufop caches, temporary mobile reports, recent diagnostics and the regional access token are excluded from owner exports. Keep access credentials separately and keep your Lufop ZIP if needed. After a clean install, configure regional access and re-download the required packs while online; a JSON owner restore alone does not restore offline road coverage.

## Regional offline operation

Offline Road Data supports Lancashire and Merseyside schema-v1 packs. Downloads require configured access and connectivity. Size/hash, bounded decompression, database integrity/schema checks precede atomic activation. A failed update retains the previous healthy pack. Installed packs remain listed and usable after catalogue/authentication failure, access removal and offline restart.

Delete waits for active database readers to close, deactivates the selected region and removes its stored versions. It cancels activation from an already-running download. Successful updates reclaim obsolete versions. Orphan temporary files are cleaned on recovery; an interrupted download restarts from the beginning, with no resume or delta support. Storage figures count stored regional files, not owner databases or map tiles.

Regional coverage is authoritative even for Unknown or uncertain matches. Outside usable regional coverage, saved legacy geometry and permitted live/legacy requests may participate. A server number without trustworthy local road geometry cannot establish identity. Conditional/variable/lane-specific or invalid limits remain Unknown; highway class alone does not invent a legal limit. Ordinary two-way UK corrections apply both directions by default, except asymmetric limits, explicit directional corrections, separate carriageways and uncertain identity.

Diagnostics identifies provider/state, pack name/version/OSM age, actual road/confidence, regional coverage, source/owner/assumed state, fallback reason and errors. Legacy tile counters are labelled separately. Dataset age is the published OSM timestamp, not a freshness guarantee.

Production pack endpoints required authentication during engineering checks, so actual production contents/coverage were not independently verified. Synthetic schema-v1 fixtures exercise matching and failures. Road signs remain authoritative. Basemap and parked map road details still use their separate online/legacy path; regional packs do not promise a fully offline map.

## Local diagnostic privacy

Up to **500** recent diagnostic payloads are retained locally. They may contain coordinates, heading, GPS accuracy, timestamps, matched road IDs/confidence, decisions and correction/boundary evidence. Retention is by count, not elapsed time; records survive restart and may remain indefinitely when no new records arrive. This is a bounded record of recent positions, even though there is no navigation journey logger or analytics upload.

Use **Clear local diagnostics** in Diagnostics after stopping driving. It removes diagnostic rows and performs database maintenance without deleting owner cameras, corrections, learned boundaries or backups. Late diagnostic payloads captured before the clear boundary are rejected. New driving decisions can create new records. Diagnostics are not included in owner exports and no diagnostic export/upload feature is provided. Clearing is not a promise of forensic erasure from filesystem snapshots or external device backups.

## Physical retest and rollback

When the intended acceptance APK is explicitly produced, safely test offline restart, pack updates/deletion, known/unknown/uncertain provider transitions, both-direction corrections, actual speed-change signs, junctions/parallel roads, dense urban candidates, cameras, GPS/network loss and background/resume. Compare actual posted signs and inspect diagnostics after stopping. Do not operate controls while moving. Keep PR #7 draft/unmerged until owner acceptance.

Before any rollback, export and verify the current version-13 backup. An older app may reject newer backups or database schemas; Android can also refuse a lower version code. Prefer restoring a verified compatible build in place when supported. A downgrade that requires uninstalling must be treated as a data-loss operation and requires an external verified backup plus a compatible restore plan. Preserve the current backup and APK; do not delete owner data to make a downgrade install.
