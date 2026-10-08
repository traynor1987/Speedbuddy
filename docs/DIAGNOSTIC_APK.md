# Speed Buddy 0.4.0 — DIAGNOSTIC ONLY

Physical acceptance: FAILED. This owner-authorised prerelease collects evidence on the corrected Android implementation. It is not a stable release or physical acceptance approval. PR #7 remains draft and unmerged.

VersionName 0.4.0, versionCode 20, package uk.co.traynor.speedbuddy. Install as an update over the permanently signed 0.4.0 candidate; equal versionCode is supported by Android replacement installs. Do not uninstall or clear app storage. Keep existing external backups. Confirm your settings, token, cameras and corrections after updating. The permanent certificate and APK SHA-256 are recorded in owner-build.json.

Production Lancashire/Merseyside pack compatibility remains unverified: authenticated production catalogue/manifests were inaccessible to the engineering environment (HTTP 401). The approximately 15-second boundary failure has not been reproduced conclusively or physically verified as fixed. Physical acceptance remains failed pending owner evidence.

## Diagnostic checks

Operate the phone only while safely stopped; a passenger may observe during a drive. Follow posted road signs regardless of the app.

1. **Without packs:** confirm your access token is retained. Start Driving without installed regional packs. Capture Diagnostics: live request status, response age, provider, fallback reason and GPS freshness. Verified regional data has authority when installed. Without it, fresh compatible live evidence precedes saved legacy OSM. Numeric-only live data requires reliable local road identity; conflicting identity stays uncertain. Saved legacy data may cover pending/unavailable live requests, with the actual failure shown. Authentication/rate-limit/uncertain responses do not trigger a public-network fallback bypass.
2. **Packs:** download Lancashire and Merseyside individually while stopped. Record installation result and stored size. Restart offline and confirm both remain installed and usable. Capture exact schema, authentication, integrity or offline reopening errors. Do not reset owner data.
3. **Boundary:** on a known upcoming 60 mph boundary, observe the countdown and current sign. Record whether `--` appears and its approximate duration, plus any assumed-limit warning. After stopping safely, capture timestamped Diagnostics, road/provider identity, GPS freshness, live response age and fallback reason. Report route, direction, boundary location and approximate time. Countdown zero alone does not establish a legal limit; an assumed limit must remain labelled assumed.
