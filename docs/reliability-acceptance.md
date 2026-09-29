# Speed Buddy 0.1.5 device acceptance

Use a mounted phone and a passenger to observe. Edit only while parked. Keep a JSON backup before installing. This acceptance build is debug signed; the permanent release still needs the owner's signing secrets described in `updates-and-signing.md`.

## Parked setup

1. Open Settings → Map & road data. Confirm 5,233 source cameras on a fresh installation, September 2026 archive, and last update/attempt information. Existing installations must preserve their later source file and all owner data.
2. On the A570/County Road area shown in the owner's screenshots, refresh road data while connected. Inspect actual markers before choosing a test route; do not assume the screenshot's two R+ pins are authoritative source cameras. Choose a safely accessible known fixed camera, red-light camera and parallel-carriageway location using posted signs and actual source details.
3. Long-press a parked map position; move the center pin, select each camera type in turn, enter a speed, rotate the visual direction dial and save one user camera. Verify USER ADDED, source/effective coordinates and saved metadata. Rotate the phone while editing another pin and confirm inputs survive.
4. Edit a source camera position/direction/speed. Clear its camera speed and confirm it stays unknown. Hide it, reopen the app, and verify Map/Drive omit linked records. Restore it under Manage corrections. Reimport a valid ZIP; all owner edits remain. Export, restore and compare owner counts and settings.

## Road observations (passenger)

5. Approach the fixed camera in its known enforcement direction. At 300 yd confirm the camera icon/card and one spoken “Speed camera ahead” followed by the known speed. Play music and confirm it lowers during speech and returns afterward. An unknown camera speed must not be guessed from an unrelated road.
6. Stop safely before passing: approaching stays visible and speech does not repeat on restarting. Pass it, leave at least 850 m, then return on the same drive: a fresh warning must occur. Reverse direction and test a parallel carriageway; record heading, GPS accuracy and Diagnostics decision. Unknown source direction remains a documented fallback.
7. Repeat with a red-light, combined, user-added and user-direction-corrected camera. Hide the user correction's source camera and confirm no warning from its linked duplicate. Delete only a user camera, accepting the confirmation.
8. Observe 30→20, 20→30, 30→40, a 30→30 continuation and known↔unknown roads. The current sign stays centered. Left/right differing limits appear on their corresponding sides; the upcoming limit never replaces current before the actual segment transition. Same-limit continuation must not announce unknown or repeat speech.
9. While stopped on an identified unknown road, use the small + below the unknown sign. Select 20/30/etc or an appropriate national single/dual limit. Drive that same segment and confirm Map and Drive agree immediately. Try an ambiguous junction position: editing must refuse an unrelated road. Variable/conditional/lane-specific data stays unknown when a simple fixed override cannot safely resolve it.
10. Where an actual complete average-speed relation is present, inspect the purple section, enter it in the enforced direction, and verify section context/remaining distance through a stop and exit. Incomplete section data should show point alerts only; no calculated average is displayed.

## Offline, lifecycle and failures

11. Visit two road areas while connected, then turn on airplane mode with GPS available. Revisit both within saved coverage; owner/source camera alerts and saved road intelligence continue. Missing tiles are identified separately. A never-visited area may have unknown road limits.
12. Lose GPS briefly before a camera: its card becomes last-known, not a live distance claim. Recover GPS, pass the camera and confirm normal clearing. Background/resume, lock/unlock and rotate during an active drive; one service remains active, notifications stop it, and map recenter restores follow after manual panning.
13. Import a malformed/empty ZIP, then configure a permitted test HTTPS endpoint that returns HTTP 500, an oversized body or invalid ZIP. Update now must retain the previous valid source count and owner data, displaying the attempt failure. Configure a valid feed and confirm atomic activation. Start driving while an update is queued: it must defer. Remove the feed afterward.
14. Check portrait, landscape, Fold outer/inner screen, large font and TalkBack in day/night modes. Main limit stays centered with zero, one or two previews; controls remain reachable, sheets scroll and Back closes the current editor before leaving Map. Pan a dense camera area smoothly and tap clusters. Confirm keep-awake can be turned off.
15. The release signer/update installer requires a signed physical acceptance build and a newer same-key GitHub release. Follow `updates-and-signing.md`; debug-to-release cannot update in place. Verify no live Drive interruption during checks, failed downloads leave the installed app intact, and Android confirms installation.

Record failures with app version, device/Android version, road/segment ID, camera ID, source/direction, GPS accuracy, heading and Diagnostics reason. Physical acceptance is outstanding until the owner completes these observations; CI does not establish roadside correctness or audio behaviour on the owner's music app.
