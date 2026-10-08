# Speed Buddy 0.4.0 — correction cycle 1

Scope: audit findings F1–F4 only. Branch `feat/regional-offline-road-data-0.4.0`, draft PR #7. Base reviewed: `9f066cf39a2817d911be4e2ae178573b9f4439d4`. App version, signing configuration and milestone remain unchanged. Physical acceptance is pending owner testing.

## Corrections

- **F1:** Road matches retain the original local OSM segment tangent. Directional source limits use way-relative forward/backward orientation, including reversed node order and `oneway=-1`, instead of the folded alignment score. Missing or unsuitable heading/geometry remains Unknown. Confident changes of direction on the same directional way invalidate prior authority, including gradual turns through uncertain headings.
- **F2:** Every processed fresh GPS frame performs a new regional spatial/heading match. Only reusable legacy road/camera candidates retain the existing cache window. One pending newest frame is drained after asynchronous work; superseded intermediate frames cannot publish.
- **F3:** New regional matches use `way/<OSM ID>`. Numeric `osm:<ID>` aliases are recognised at correction lookup and boundary/observation replay without rewriting stored evidence. Regional geometry wins duplicate contextual identities. Weak matches cannot activate saved owner limits.
- **F4:** GPS fixes must be fresh and monotonic. Database and decision work checks that its frame remains current before mutating decisions or publishing. Authenticated fallback requests run separately; a response can retry only its own still-current frame, never a newer GPS position. Repeated evaluation of the same fix does not count as another stabilizer sample. Correction saves cannot replay an older regional match against a newer location.

## Owner correction policy

New normal sign taps on identified ordinary two-way roads apply locally to both directions of the **same OSM way**, retaining the existing 150 m scope. Sharing requires an ordinary road class, no one-way/roundabout declaration and no directional speed tags. No propagation occurs by matching road names or proximity.

Explicit direction-specific selections and the existing `SET_OVERRIDE` action remain directional. Existing records without a sharing flag remain directional, and explicit directional records retain priority in their direction. Newly discovered directional source tags prevent an old shared tap expanding across directions. Learned boundaries remain scoped to connected identified ways; shared boundaries require ordinary two-way source geometry.

Backup **format 13** stores the sharing flag and accepts supported older formats. This is an owner-data schema revision, not a new app version. Older app versions do not understand new v13 exports; retain original older backups when rollback is needed. Existing database schema and saved records are retained.

## Regression coverage

- Actual `RoadMatcher` geometry, supported speed parsing, reversed node order, stationary/missing heading, reverse one-way and gradual same-way reversal through the decision pipeline.
- New two-way policy, explicit and directional-source exceptions, old aliases, unrelated ways, local distance bounds, weak identity evidence, JSON and portable backup round trips.
- Monotonic GPS publication, bounded latest-frame queue, live response retry, obsolete pipeline history and obsolete UI publication.
- Real installed SQLite/RTree packs through `RegionalPackMatcher`, `DrivingLimitPipeline` and actual `DrivingService.onLocationChanged`: rematching inside 5 seconds/150 m, blocked processing with a newer pending fix, and delayed network responses while new regional matching continues.
- Pending matching preserves existing speech relevance only while position, geometry and heading remain valid for at most 2 seconds. It does not enable new numeric warnings or allow correction against old geometry. The UI regression checks the correction gate.

The service callback tests instantiate the real service and initialise its databases but do not exercise Android foreground registration/permission enforcement or real GPS/audio hardware. CI's emulator suite runs the instrumentation regressions. Owner road and audio tests remain necessary.

## Focused owner verification

Perform phone interactions while safely stopped or through a passenger. Observe existing legal signs; do not change driving speed to test the app.

1. Revisit a known genuinely directional road in each direction. Check the source/display against legal signs, including a safe reversal only where lawful.
2. Cross a nearby junction or limit boundary within the old 150 m cache window. Confirm road identity/source changes with the new GPS frame, with normal stabilization rather than stale road reuse.
3. Set an ordinary local two-way correction while stopped. Revisit in both directions and after restart. Verify an unrelated way/carriageway and a position beyond the local scope are unaffected.
4. Revisit existing saved directional corrections and learned boundaries. Export/reimport a new backup on a test installation and verify both old records and new sharing semantics.
5. Drive through intermittent/no network with packs installed. Check speed/location remain current and delayed responses cannot restore an old road/limit.
6. Listen to a camera/limit announcement through successive GPS fixes and a short matching delay. Confirm ordinary matching does not cut speech off; a turn, expired evidence or irrelevant camera must still end the warning.
