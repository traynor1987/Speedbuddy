# Cycle 4 final correction and phone acceptance candidate

Starting remote head: `809c08a100005cca592d18c0b2bd2d731e9c807f`; push #282 and PR #283 were successful. Cycles 1–3 are preserved. Version remains 0.4.0 / code 20.

Confirmed corrections: drain the latest GPS frame after initialization without another callback; reject owner camera snapshots changed during suspended reads; retain actionable GPS/permission start failure status after destruction; revalidate TTS-error fallback relevance and voice permission; restore correction-picker target identity while rejecting changed roads; allow scrolling on tall devices using large fonts.

Android Auto: remove unsupported passive NAVIGATION discovery and exported service registration. Retain passive presenter/templates with fail-closed host validation. There is no supported replacement category or platform bypass. Host/DHU/vehicle acceptance is unavailable and does not block phone testing.

Validation includes existing JVM, Android SQLite/RTree/pipeline/UI and Python regressions, debug/release/instrumentation assembly, lint, release signing verification and exact-head push/PR workflows. New tests exercise owner-edit races, pre-initialization GPS queuing, real speech-error handling and picker/font restoration. Permanent signing and prerelease publication occur only after both validation jobs and exact-head PR success. Local validation passed 252 JVM tests, 26 Python tests, debug/release/instrumentation APK assembly, debug/release lint and disposable signing/mismatch rejection. No local instrumentation emulator is available; exact-head CI executes the complete suite. The downloaded audit-baseline owner APK independently verified against the tracked permanent certificate. See workflow results for final executed counts and `owner-build.json` for source, APK hash and certificate.

Production catalogue and Merseyside/Lancashire manifest probes returned HTTP 401 without credentials. Synthetic schema-v1 fixtures validate contract/pipeline behaviour; actual production contents and physical accuracy are unverified. No local device, foldable, DHU or vehicle was available. Existing map editor remains a separate legacy/online geometry path.

No merge, main modification, new version, stable release or owner-data deletion. Physical road acceptance remains pending; see OWNER_ACCEPTANCE.md for backup, install, rollback and road checklist.
