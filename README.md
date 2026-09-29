# Speed Buddy

A small, owner-first Android GPS speedometer with tagged OpenStreetMap road limits, fixed speed and red-light camera alerts, and local user cameras. No account, navigation, adverts, driving history, or analytics.

**Status:** source prototype for physical acceptance. Road matching and camera detection have not been validated on a real drive. Follow signs and the vehicle speedometer. Missing camera data does not mean there is no camera.

## Build

Open in Android Studio with Android SDK 36 and JDK 17, or run `gradle testDebugUnitTest assembleDebug` with Gradle 8.13. The debug APK will be under `app/build/outputs/apk/debug/`. CI is defined in `.github/workflows/android.yml` and will run when this repository is pushed to GitHub. Instrumented database test requires an emulator or device (`gradle connectedDebugAndroidTest`).

## Design

- A foreground `location` service is started by a visible activity after precise, while-in-use location permission. It requests one-second GPS updates from Android `LocationManager`. The active notification stops it; screen rotation and activity recreation do not start a second GPS subscription. No background location permission is requested.
- `Location.hasSpeed()` supplies metres/second, converted to mph. Samples older than five seconds, inaccurate fixes, uncertain speeds and implausible jumps are rejected. Unknown speed shows `--`.
- Overpass retrieves nearby OSM road geometries, `maxspeed` tags, camera nodes and enforcement relations in a roughly 3 km square. A single response is cached on device for 24 hours and reused while within 650 m of its centre. Failed refreshes are spaced at least 90 seconds. Public camera coverage is partial.
- Matching scores distance, course (including one-way direction), and previous road. Close competing candidates produce an unknown limit. The limit provider parses explicit OSM `maxspeed`, mph units, numeric km/h values, and UK national tags; conditional, variable and lane-specific limits show `--`.
- Camera candidates are filtered by heading, travel direction if tagged, current matched road when known, recent distance trend, and passed status. A new approach at 750 m triggers one optional tone/vibration; the display counts down and dismisses after passing. User cameras go through the same detector and work without a public-data cache. No route prediction is claimed.
- SQLite stores only personally added cameras. The OSM response cache stores nearby map data. It records no permanent trip or location history.

## Data and licences

Road and public camera data: © OpenStreetMap contributors, Open Database License (ODbL). Attribution and ODbL link appear in Settings. Source: https://www.openstreetmap.org/copyright . Public read-only query service: https://wiki.openstreetmap.org/wiki/Overpass_API . The app sends a roughly 3 km bounding box around the current position to that service, only as needed while driving. The public service can be slow, rate limited or unavailable; this owner prototype is not suitable for broad release on shared Overpass infrastructure without a dedicated provider. No OSM tile server is used.

OSM `maxspeed`, `highway=speed_camera`, and `type=enforcement`/`enforcement=maxspeed|traffic_signals` tagging are incomplete and may be wrong. The parser does not infer unsigned UK defaults or live variable limits. Nearby public cameras can be absent. User-added cameras remain private local records and are not uploaded to OSM.

## Physical acceptance

1. While parked, grant precise location, start mode, then verify `0` or `--` while acquiring GPS, Stop notification, and no editor while moving.
2. As a passenger or with the phone mounted, compare displayed speed against the car on several steady roads. Inspect `--` under GPS loss and recovery.
3. Compare posted limits across a junction and parallel roads; inspect diagnostics when `--` or a wrong match appears. Never treat a displayed limit as authoritative.
4. On a safe planned pass of a known fixed camera and a red-light camera, verify type, countdown, direction-away rejection and automatic dismissal. Do not handle the phone while moving.
5. While parked, add a missing camera, then approach it on a later journey and verify the same countdown; edit/delete while parked.
6. Repeat with connectivity disabled in a recently cached area; expect cached data for up to 24 hours and `--` outside it. User cameras should still alert.

Report time, road, posted limit, GPS accuracy, matched road ID, camera ID/rejection reason, and whether the 24-hour cache was available for discrepancies. These are visible in Diagnostics; no journey log is retained.
