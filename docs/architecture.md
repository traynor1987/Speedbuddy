# Architecture decision — 2026-09-28

Intent: one owner-facing, glanceable Android driving screen for GPS speed, a trustworthy tagged road limit where available, and fixed speed/red-light camera approaches. No navigation, account, or social layer.

Options considered: (1) paid proprietary map matching and camera API, with credentials, recurring cost, provider restrictions and separate camera coverage; (2) region-wide offline OSM extracts, with large download/update machinery; (3) bounded Overpass extracts cached locally. Option 3 fits an owner prototype and avoids credentials. It has incomplete road/camera data and a public-service dependency; the UI explicitly shows unknown/degraded states. Broad distribution would require a dedicated data service or licensed provider.

Components: `DrivingService` owns GPS and lifecycle; `SpeedFilter` handles measurement; `RoadMatcher` resolves tagged geometry and ambiguity; `SpeedLimitProvider` parses only known static tags; `OsmDataSource` fetches/caches roads/cameras; `CameraDb` holds owner records; `CameraApproachDetector` handles direction, road, movement and deduplication; `OverspeedGate` re-arms after a lower speed; Compose renders live state. Core geometry/logic is pure Kotlin for deterministic tests.

Data flow: visible Start -> precise location permission -> foreground service -> GPS fix -> speed filter -> cached road match -> limit, plus cached/public and local cameras -> approach decision -> glanceable display and optional alert. Missing, inaccurate, stale or ambiguous inputs yield unknown rather than guessed values. Background work stops with the service; no permanent travel record.

Remaining acceptance risks: OSM coverage and tagging variation, junction/parallel road ambiguity, Android GPS and permission behaviour, camera direction and enforcement relation coverage, public Overpass availability. Unit tests do not resolve these; a physical drive is required.
