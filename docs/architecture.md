# Architecture decision — 2026-09-28

Intent: one owner-facing, glanceable Android driving screen for GPS speed, a trustworthy tagged road limit where available, and fixed speed/red-light camera approaches. No navigation, account, or social layer.

Options considered: (1) paid proprietary map matching and camera API, with credentials, recurring cost, provider restrictions and separate camera coverage; (2) region-wide offline OSM extracts, with large download/update machinery; (3) bounded Overpass extracts cached locally. Option 3 fits an owner prototype and avoids credentials. It has incomplete road/camera data and a public-service dependency; the UI explicitly shows unknown/degraded states. Broad distribution would require a dedicated data service or licensed provider.

Components: `DrivingService` owns GPS and lifecycle; `SpeedFilter` handles measurement; `RoadMatcher` resolves tagged geometry and ambiguity; `SpeedLimitProvider` parses only known static tags; `OsmDataSource` fetches/caches roads/cameras; `CameraDb` holds owner records; `CameraApproachDetector` handles direction, road, movement and deduplication; `OverspeedGate` re-arms after a lower speed; Compose renders live state. Core geometry/logic is pure Kotlin for deterministic tests.

Data flow: visible Start -> precise location permission -> foreground service -> GPS fix -> speed filter -> cached road match -> limit, plus cached/public and local cameras -> approach decision -> glanceable display and optional alert. Missing, inaccurate, stale or ambiguous inputs yield unknown rather than guessed values. Background work stops with the service; no permanent travel record.

Remaining acceptance risks: OSM coverage and tagging variation, junction/parallel road ambiguity, Android GPS and permission behaviour, camera direction and enforcement relation coverage, public Overpass availability. Unit tests do not resolve these; a physical drive is required.

## Road-data continuity after first device drive

The initial 650 m usability radius and 90-second request interval caused long unknown gaps at motorway speed even with a good GPS fix. The bounded 3 km Overpass extract now remains usable within an inner 2.3 km square, and the app requests the next extract 900 m ahead once the vehicle moves away from the centre. A previous in-memory extract remains available during overlap. Requests are spaced by at least 15 seconds. A brief (at most 3 seconds) failed match can retain the last tagged limit only when the GPS fix remains close to the same road geometry and heading; a different matched road or missing map coverage immediately clears it. Diagnostics shows the last map request state.

An explicit UK `maxspeed:type` or `source:maxspeed` value can resolve national single/dual carriageway, restricted and motorway limits when `maxspeed` is absent. A road merely tagged `highway=motorway` is still unknown. Variable/conditional speed limits remain unknown because live gantry or time-dependent signs cannot be resolved from static OSM data. Public Overpass availability and missing OSM tagging can still cause unknown stretches; this is a bounded owner prototype, not a guaranteed live speed-limit service.
