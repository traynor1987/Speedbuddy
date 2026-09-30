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
- Camera candidates are filtered by heading, travel direction if tagged, current matched road when known, recent distance trend, and passed status. A new approach at 300 yards triggers one optional tone/vibration and the camera-type icon; the display counts down and dismisses after passing. User cameras go through the same detector and work without a public-data cache. No route prediction is claimed.
- A 5,233-record September 2026 Lufop UK snapshot is bundled and loaded on first run if no camera file exists. Settings can import a newer free Lufop Europe ASC ZIP while parked. Only UK `GBFixe*.asc` and `GBFeuRouge*.asc` are used. The September 2026 file supplied for testing contained 4,220 fixed and 1,013 red-light positions. The app stores them in a separate SQLite table and replaces that layer on each import; existing personal cameras are retained. The ZIP does not include a trustworthy enforced mph or camera direction, so those fields remain unknown. Import date and ZIP entry date appear in Settings. Failed/empty imports keep the old data.
- SQLite stores personally added and imported cameras separately. The OSM response cache stores nearby map data. It records no permanent trip or location history.
- Settings → Camera and speed-limit map supports active-drive follow and parked editing. MapLibre Native renders OpenFreeMap vector tiles as the basemap. Imported UK, OSM and personal cameras are overlaid in the visible area; zoom in for dense areas. Road colours and sparse limit signs use the same bounded OSM `maxspeed` extract as Drive, not the basemap tiles. Tap a road or camera to save a **local correction**; use Add → Camera pin to create a personal camera. Add → 4/5-way camera junction creates a named group of individually placed speed/red-light cameras. Each member needs an enforced travel direction within 300m of the centre. Relevant members share one encounter’s warning stages; ungrouping keeps the cameras. See [junction editing and acceptance](docs/junction-cameras.md). The map does not provide navigation, route history or a claim that all cameras or limits are present. Road data loads automatically when the map enters an uncached area; Refresh is available while parked.
- Local corrections to public camera type, position and direction, and OSM way speed limits live in separate SQLite tables. The driving detector and limit provider use these corrections, which survive a Lufop reimport. Owner backup version 8 includes corrections, junctions and member relationships; older backups still restore. A local road correction changes Speed Buddy’s interpretation of that OSM way only; posted signs remain authoritative.

## Data and licences

Road and public camera data: © OpenStreetMap contributors, Open Database License (ODbL). Attribution and ODbL link appear in Settings. Source: https://www.openstreetmap.org/copyright . Public read-only query service: https://wiki.openstreetmap.org/wiki/Overpass_API . The app sends a roughly 3 km bounding box around the current position to that service, only as needed while driving. The public service can be slow, rate limited or unavailable; this owner prototype is not suitable for broad release on shared Overpass infrastructure without a dedicated provider. No OSM tile server is used.

OSM `maxspeed`, `highway=speed_camera`, and `type=enforcement`/`enforcement=maxspeed|traffic_signals` tagging are incomplete and may be wrong. The parser does not infer unsigned UK defaults or live variable limits. Nearby public cameras can be absent. User-added cameras remain private local records and are not uploaded to OSM.

Imported camera data: Lufop.net and OpenStreetMap contributors, ODbL 1.0. The [free Europe ASC download](https://lufop.net/en/asc-and-csv-speed-camera-files/) requires the owner to register and download it in a browser; Speed Buddy does not sign in or fetch it automatically. The free file is updated monthly, so reimport monthly while parked. Its UK presence and category counts were verified against the owner-provided September 2026 ZIP, but field accuracy and coverage have not been validated on the road. Import is local and sends no location to Lufop. The JSON personal backup excludes this re-downloadable public layer.

Map display: [MapLibre Native Android](https://maplibre.org/maplibre-native/android/examples/getting-started/) (BSD-2-Clause) with [OpenFreeMap](https://openfreemap.org/quick_start/) tiles. Visible attribution credits OpenStreetMap contributors, OpenMapTiles and OpenFreeMap. Opening and panning the map sends viewed tile coordinates to OpenFreeMap; loading road details sends a bounded location query to Overpass. It does not upload personal camera pins or corrections. Map tiles are for online viewing and their normal cache only; no bulk/offline tile download is provided.

## Physical acceptance

1. While parked, grant precise location, start mode, then verify `0` or `--` while acquiring GPS, Stop notification, and no editor while moving.
2. As a passenger or with the phone mounted, compare displayed speed against the car on several steady roads. Inspect `--` under GPS loss and recovery.
3. Compare posted limits across a junction and parallel roads; inspect diagnostics when `--` or a wrong match appears. Never treat a displayed limit as authoritative.
4. While parked, confirm the bundled UK camera count/date in Settings, then optionally import a newer ASC ZIP. On a safe planned pass of a known fixed camera and a red-light camera, verify type, countdown, direction-away rejection and automatic dismissal. The file has no direction metadata, so assess nearby parallel/opposite-carriageway cases carefully. Do not handle the phone while moving.
5. While parked, add a missing camera, then approach it on a later journey and verify the same countdown; edit/delete while parked.
6. Repeat with connectivity disabled in a recently cached area; expect cached data for up to 24 hours and `--` outside it. User cameras should still alert.
7. Park, open the map, check fixed/red-light pins and tagged road colours; tap a road to correct its limit and a public camera to correct its direction. Confirm the driving screen uses the correction, a Lufop reimport retains it, and backup/restore carries it to a fresh installation. Pan to another area and confirm its road limits load automatically. Without network, previously cached map details may appear but unvisited tiles and road tags will not.

Report time, road, posted limit, GPS accuracy, matched road ID, camera ID/rejection reason, and whether the 24-hour cache was available for discrepancies. These are visible in Diagnostics; no journey log is retained.

## Temporary mobile-camera reports

Drive has a one-tap Report mobile camera action. Local reports expire (two hours by default), appear as distinct amber mobile markers, and use the existing 300-yard directional camera warnings and music-ducking speech. Still there renews an active observation; Not there removes it. Settings → Camera alerts controls fixed/mobile/voice warnings and report lifetime. These are reports from this phone, not Google/Waze crowdsourced coverage; no provider key or billing setup is required. See [official provider research, architecture and physical acceptance](docs/road-alerts.md).
