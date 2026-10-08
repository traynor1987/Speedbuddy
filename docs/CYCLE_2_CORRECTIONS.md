# Speed Buddy 0.4.0 — correction cycle 2

Scope: regional road intelligence only. Starting head: `0b1506cff936fc14822e9b4d0eceefea8a347b2f`. PR #7 remains draft/unmerged; version remains 0.4.0/code 20. Physical acceptance is pending.

## Implementation

- Regional point matching and surrounding context are queried separately. A bounded 350 m context supplies connected look-ahead, turns and camera carriageway checks; roads outside the point gate cannot change the current road. Regional identities take priority over duplicate legacy geometry.
- The shared decision result carries contextual roads to DrivingService camera and turn detection. Known/unknown/uncertain regional coverage does not trigger legacy public-tile downloads. Optional legacy geometry lookup failures do not abort regional processing.
- UK national and numeric tags share one parser across regional and legacy paths. Directional previews use outgoing geometry and obey forward/reverse-only ways. Conditional, variable, lane-specific, missing and invalid limits remain Unknown. Motorway/dual limits require tags; highway class alone does not invent a legal limit.
- Connected, unambiguous same-road short segments can be traversed for an upcoming change within the existing 200-yard preview distance. Branch ambiguity stops look-ahead.
- Active pack pointer/version/database changes reset matching and decision history. Durable owner corrections, aliases, learned boundaries and backup format remain unchanged.
- Bad active pointers and failed pack readers are isolated. Malformed nearby rows, conflicting overlapping regional limits and truncated candidate sets remain uncertain. Incomplete surrounding context suppresses geometry-dependent turn/camera decisions rather than pretending parallel-road evidence is complete.
- Live limits require a trustworthy local road match and consistent matched/numeric provider state. The live contract has no road geometry, so a server number alone cannot create road identity. Timeouts and network failures remain unavailable; the Cycle 1 current-frame guards reject delayed responses.

## Tests and data limits

Fourteen new JVM regressions cover national/directional interpretation, permitted outgoing previews (including short intermediate segments), pack-generation resets, missing live geometry, inconsistent provider states and network failures. Thirteen new real SQLite/RTree instrumentation fixtures cover point/context separation, dense candidates, corrupt pointers/databases/rows, overlapping packs, activation/deletion, owner aliases, actual service junction/look-ahead and forward/reverse/parallel camera approaches. Cycle 1 persistence, backups and delayed-result tests remain in the suite. The full local JVM suite passes 240 tests; emulator execution must pass both exact-head GitHub workflows before this cycle is complete.

Fixtures are synthetic schema-v1 datasets. Unauthenticated production requests to `/speedbuddy/v1/regions` and the historical Merseyside manifest returned HTTP 401 on 8 October; actual production pack contents and coverage were not independently inspected. No production coverage or physical road acceptance is claimed.

Intermediate cycle commits use `[skip owner apk]`; build, JVM, lint and emulator jobs still run, while owner signing/release publication is skipped. A later intended acceptance build can omit that marker.
