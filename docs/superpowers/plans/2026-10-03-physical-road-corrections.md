# Physical road correction implementation plan

Owner specification: physical acceptance failed on 2026-10-03. Continue the recovered branch at 968dc732f9cf5b49b107a599e479b1c645a37aad. Implement inline in this session. PR #2 must remain open, draft and unmerged. The 0.2.2/code 11 physical candidate is rejected; preserve its historical evidence.

## Decisions

Preserve the existing cache, matcher, stabilizer and signer. In `LimitDecision.kt`, inherit confirmed evidence through a bounded missing match only while fresh GPS still fits its geometry and heading. Never renew an anchor from assumed evidence. Preserve stabilizer history when reliable tags return. During a candidate transition use connected directed geometry to hold a bounded assumed current limit if the stabilizer's narrower geometry window is exceeded.

The normal sign picker must route through `LimitDecisionEngine.planSelection`. A recent credible directed transition plus selection of its old limit produces a durable `BoundaryObservation`, not an override. A later selection matching a credible upcoming source limit pairs with that observation to produce a `BoundaryCorrection`. Account for GPS uncertainty, require forward progress, constrain pairing to 120 seconds / 600 metres and connected geometry. A skipped intermediate road is listed explicitly on the local boundary. No universal short-zone suppression. Unrelated corrections remain directed local overrides within 150 metres of the captured picker position; explicit Unknown/National retain their existing meanings.

Use independent SQLite owner tables for observations and bounded diagnostics. Public-data replacement never mutates these. A saved observation may resume after restart within its wall-clock bounds; learned boundaries persist indefinitely until owner reset. Extend portable backups for both observation evidence and boundary road scopes. Persist before changing live state, and reject stale picker targets. Decision diagnostics include fix, matched/source/upcoming state, assumption reason and classification.

## Tasks

- [x] Reproduce missing-match continuity, assumption recovery and large boundary steps with actual matcher/provider/engine tests; run red before production edits.
- [x] Fix continuity transitions; run relevant and existing JVM regressions.
- [x] Add failing natural two-tap, reverse, false intermediate, invalid GPS and expiry tests; implement conservative selection plans and durable observations/boundaries.
- [x] Route Drive mode and sign-picker selections through that production path; instrument storage/reopen/refresh and actual UI state.
- [ ] Verify all JVM tests, Android instrumentation, debug/release lint and builds, signing pipeline tests. Advance to 0.2.3/code 12 without changing the certificate pin.
- [ ] Commit/push the existing branch, verify exact remote SHA and both push/PR CI. Use only the green exact-source permanent-signing artifact. Independently verify source/package/version/certificate, provide replacement APK and short owner road retest. Stop for physical acceptance.

## Review focus

No stale assumptions after a U-turn, bad GPS, disconnected road or exhausted bounds. No whole-road override from the first early-transition tap. No opposite-direction boundary leakage. No unfinished observation revived after expiry/reboot. No overwritten learning on refresh or backup restore. No APK from a different source SHA or certificate.
