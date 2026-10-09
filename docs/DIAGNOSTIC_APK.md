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

On an ordinary known road, a completed fresh confirmed decision clears the small exclamation badge and red ⚠ ASSUMED immediately, including when the number stays the same. Pending processing or genuinely bounded continuity remains assumed; a confirmed display never comes from relabelling uncertain evidence. The previous presentation recovery latch has been removed. Current-speed typography, question-mark sign, themes, camera controls and layouts are preserved.

At a confirmed 30→30 junction, keep 30 with no “Limit changing…” or repeated numeric-limit speech, while road identity and camera relevance update. A fresh strong regional match beyond a connected boundary's GPS uncertainty margin can display the different confirmed number immediately; weak, ambiguous or unresolved evidence retains uncertainty. Unknown helper text stays “Waiting to verify this road” across pending/completed correction-readiness changes, and clears immediately for a confirmed number.

A genuinely unresolved connected junction still permits numeric-free “Limit changing…” after credible movement/heading evidence. It lasts at most five seconds and resolves to confirmed evidence or Unknown without restarting indefinitely at the same junction. The approximately 15-second boundary failure remains unverified. See `docs/REGIONAL_CONFIDENCE_CORRECTIONS.md` for root causes and retest scope. Capture diagnostics while stopped when a posted limit disagrees or uncertainty persists.
