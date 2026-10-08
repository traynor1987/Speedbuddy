# Speed Buddy 0.4.0 — DIAGNOSTIC ONLY

Physical acceptance: FAILED. This owner-authorised prerelease collects evidence on the corrected Android implementation. It is not a stable release or physical acceptance approval. PR #7 remains draft and unmerged.

VersionName 0.4.0, versionCode 20, package uk.co.traynor.speedbuddy. Install as an update over the permanently signed 0.4.0 candidate; equal versionCode is supported by Android replacement installs. Do not uninstall or clear app storage. Keep existing external backups. Confirm your settings, token, cameras and corrections after updating. The permanent certificate and APK SHA-256 are recorded in owner-build.json.

The owner physically downloaded and installed Lancashire and Merseyside production packs 20261006-1 (about 205.6 MB combined), and Diagnostics recognised regional offline coverage. This verifies download/install on that phone; it does not establish correct road matching or boundary transitions. The approximately 15-second boundary failure has not been reproduced conclusively or physically verified as fixed. Physical acceptance remains failed pending owner evidence.

## Diagnostic checks

Operate the phone only while safely stopped; a passenger may observe during a drive. Follow posted road signs regardless of the app.

1. **Without packs:** confirm your access token is retained. Start Driving without installed regional packs. Capture Diagnostics: live request status, response age, provider, fallback reason and GPS freshness. Verified regional data has authority when installed. Without it, fresh compatible live evidence precedes saved legacy OSM. Numeric-only live data requires reliable local road identity; conflicting identity stays uncertain. Saved legacy data may cover pending/unavailable live requests, with the actual failure shown. Authentication/rate-limit/uncertain responses do not trigger a public-network fallback bypass.
2. **Packs:** retain both installed packs. Force-close and reopen offline while stopped. Expect “Checking installed road packs…” before the complete inventory; no false Not installed / 0.0 MB or enabled conflicting actions. Confirm both 20261006-1 versions and about 205.6 MB return. Capture any integrity or reopening errors. Do not reset owner data.
3. **Boundary:** on a known upcoming 60 mph boundary, observe the countdown and current sign. Record whether `--` appears and its approximate duration, plus any assumed-limit warning. After stopping safely, capture timestamped Diagnostics, road/provider identity, GPS freshness, live response age and fallback reason. Report route, direction, boundary location and approximate time. Countdown zero alone does not establish a legal limit; an assumed limit must remain labelled assumed.

## Display and junction retest

On a known 20 mph road, observe confirmed / ⚠ ASSUMED / unknown state while stopped or with a passenger. Caution may remain visible while matching repeatedly has processing gaps; the recovery warning is conservative and never supplies a number after the underlying decision loses valid evidence. It clears only after two fresh same-limit decisions at least one second apart without another assumption. A newly verified different numeric limit appears immediately.

At a connected junction, expect a numeric-free “Limit changing…” only after credible movement/heading evidence of a transition. It lasts at most five seconds, ends immediately when the new limit is confirmed, and cannot repeatedly restart at the same junction. Unknown uses a custom red-ringed white question-mark sign. Assumed numeric limits retain the existing small exclamation badge and red ⚠ ASSUMED label. Follow posted signs; capture diagnostics only after stopping safely.
