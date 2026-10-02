# Local speed-limit learning

This extends `dd896b3d985c9250488b51dfeadae2517eafc85b` on `feat/offline-road-limits`. It does not redo the offline cache, progressive 20-mile coverage, 15-minute coverage checks, offline fallback or owner signing preparation. PR #2 stays draft and unmerged.

## Own algorithms, local source data

OSM seeds local road geometry, speed tags and camera observations. Speed Buddy owns the deterministic runtime algorithms: GPS speed filtering, geometry/heading matching, limit stabilisation, owner-priority corrections and boundaries, camera approach/direction checks, distance checks and alert suppression. None needs an OSM request for every fix. Downloaded coverage and owner records work offline; new areas still require a seed dataset. Public-provider refresh is separate from live decisions and cannot overwrite owner feedback. Source provenance and attribution remain attached to source data. A future shared online database is outside this candidate; no account, upload or server dependency is introduced.

## Driving display and feedback

Tap the main speed-limit sign, then select 20, 30, 40, 50, 60, 70, National Speed Limit or Unknown. The picker uses large signs and no text input. It captures the displayed road identity; the service rejects a stale target when the live road differs. A fresh fix, confident road match and travel heading are required to save. Rejection and disk-write failure are shown briefly; they never claim success.

Numeric corrections, Unknown and National are explicit owner selections, stored independently of OSM tiles. Owner feedback takes priority. Unknown deliberately suppresses a numeric source; it is different from resetting the correction. National retains the national sign and uses a numeric value only when existing carriageway tags or motorway classification support one. Lane count or one-way tagging alone does not establish a dual carriageway.

For an early change, the same picker offers **This limit starts here**. This records the live location for a credible old/new directed road transition. The optional **Changed too soon** action holds the old value until the owner reaches the actual sign and uses the same starts-here action. There are no permanent separate feedback buttons. Boundary replay keeps old CURRENT and new UPCOMING until crossing within GPS uncertainty; opposite travel does not inherit that boundary.

Reset road or all local learning later in parked Diagnostics. No map coordinates or keyboard are required for quick feedback.

## Unknown-road continuity

A connected unknown road can inherit a recent confident numeric limit, displayed as `30 !` or `60 !` plus “Assumed • not confirmed”. The assumption never becomes an OSM value or a durable owner correction, and it is excluded from overspeed alert authority. Diagnostics records the original confirmed road/value, age and travelled distance.

The fallback needs confidence at least 0.7, accuracy at most 20 m, aligned travel, connected geometry, and compatible road context. It expires after 90 seconds or 750 m from the last confirmed fix, without renewing itself on unknown roads. Disconnected geometry, direction changes, loss of confidence, conditional/directional speed tags and incompatible motorway/link/service context drop it. A reliable source or explicit owner correction replaces it. Expiry diagnostics explains the reason and provenance.

## Conservative transitions

Different numeric limits require repeated strong, aligned fixes, at least three updates over two seconds and boundary/geometry progression. Weak matches retain candidate source information as UNCERTAIN, without promoting it merely because time passes. A suspicious `60 → 40 → 30` can consequently resolve to `60 → 30`; a strong, genuine short 40 zone can still confirm within a few seconds. There is no 20-second deletion rule, inferred permanent correction, or automatic rewrite of OSM tags.

## Persistence and backup

Road DB schema 2 adds correction evidence without deleting schema-1 overrides. Records retain road identity, travel heading, original source mph (including missing), explicit selection, location, timestamp and GPS accuracy. Tile replacement and cleanup only mutate public data. Boundaries retain their existing evidence format.

Portable backup version 10 includes full correction evidence, Unknown/National selections and existing boundaries/legacy archives. Versions 1–9 remain readable; v9 overrides retain their numeric meanings. Restore keeps the complete source before writes, and read-back still verifies exports. The earlier preview's archive-only compatibility limits remain as documented in `OWNER_ACCEPTANCE.md`.

## Verification and physical gate

Regression tests exercise 30/60 inheritance, expiry, reliable replacement, owner replacement, unknown selection, poor GPS/context/direction rejection, weak intermediate transitions, real short zones, direct boundary replay, current-road capture and national uncertainty. Android tests exercise schema upgrade, refresh/reopen, direction isolation, evidence backup/restore, reset, and the real sign picker without text entry.

CI must run both exact-source push and PR validation, including API-35 instrumentation, builds, lint and actual disposable signing verification. That disposable test key is never an owner identity or acceptance artifact.

The permanent owner signer is now established, recoverable and publicly pinned in `owner-signing-certificate.json`; CI signing and setup reject a conflicting certificate. The current candidate can be signed locally from the final exact remote source after validation CI succeeds, using the recovered permanent key and existing APK verifier. CI signing secrets still need setup for automatic future artifacts; no secrets are placed in the repository. A signed artifact must carry the new final source SHA, version 0.2.1/code 10, verified package, nondebuggable release flag, APK digest and pinned permanent certificate. Do not uninstall or install anything until the external old owner backup is verified and a permanently signed candidate exists. No owner physical observations have been recorded for these new changes.
