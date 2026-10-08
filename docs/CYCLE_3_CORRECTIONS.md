# Speed Buddy 0.4.0 — correction cycle 3

Base: `e69034267ac255af0fbf7a6f68d1aa42827c8d8b`. Scope F5–F9 only; preserve Cycles 1/2, keep PR #7 draft/unmerged, version 0.4.0/code 20. Commits use `[skip owner apk]`.

- F5: local inventory and catalogue merge independently; offline, authentication failure/removal and restored screens retain installed cards. Missing/unreadable databases and damaged pointers have explicit management/error states. Download progress and failure reporting remain; buttons reset in finally.
- F6: shared in-process reader leases protect active SQLite reads. Exclusive activation/deletion/recovery waits for readers. One download per region plus revision invalidation prevents delete/late-activation revival. Verified replacement can repair corrupt same-version files; healthy updates remain immutable. Failed validation preserves healthy active data. Activation uses fsynced unique pointer work; obsolete versions and orphan staging/downloads are reclaimed. Deletion counts bytes from the selected region/pointers and reports remaining total regional storage.
- F7: provider, state, coverage, pack name/version/OSM timestamp/age, sample age, incomplete context, fallback and error accompany decisions. UI separately shows actual matched road/confidence, source, owner, assumed and pending/stale state. Legacy counters are labelled. Unreadable coverage remains not established, not falsely absent. Public build identity is visible.
- F8: README, architecture, signing/update and acceptance instructions describe current version, exact-source identity, backup v13, excluded packs/access tokens, manual candidate installation, pending physical acceptance and downgrade precautions. Future intended release notes include exact SHA; Cycle 3 produces no owner APK.
- F9: 500 count-bounded diagnostic records persist with no fixed expiry. Stopped-only clearing deletes diagnostic rows, persists a cutoff against late receipts and performs maintenance while preserving owner data. Diagnostic insertion/selection/clearing use consistent lock order. Secure deletion is enabled for database payloads; no forensic-erasure promise is made. No diagnostic upload/export feature is added.

SQLite maintenance consumes the incremental-vacuum cursor. Existing non-incremental databases are converted by a full maintenance VACUUM outside transactions; owner rows remain. Android components use one process; a future separate-process reader would need cross-process coordination. Interrupted downloads restart, with no resume/delta feature.

Added 10 JVM tests, 14 instrumentation tests and 3 Python tests (plus stronger assertions in two existing pack tests and the existing release-workflow test).

Regression additions: offline list merge, provider-state/freshness interpretation, real filesystem leases/revision/recovery, actual SQLite deletion/download/recovery/corrupt repair, offline/restored/error UI, count retention/restart/clearing, concurrent persistence and real byte reclamation. Previously passing tests remain. Emulator execution must pass both exact-head workflows before completion.

Production data limits remain those of Cycle 2: synthetic schema-v1 fixtures, unauthenticated production endpoints returned HTTP 401, no independent real-pack coverage or physical-road claim. Device/OEM lifecycle, audio/Auto and physical acceptance remain pending for the next approved cycle.

Local pre-publication validation: 250 JVM tests, 26 Python tests, debug/instrumentation/release assembly, debug/release lint and disposable-key release verification passed. The local environment has no emulator; both exact-head push and PR emulator runs remain required. Initial local ART-profile generation failed once, then passed with unchanged source; validation was not weakened.
