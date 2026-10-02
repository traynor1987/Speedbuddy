# Offline road limits implementation plan

> Execution: implement directly in the authorized session using executing-plans and TDD; whole-change review before candidate delivery.

Goal: local road matching survives internet loss and learns specific premature boundaries.
Architecture: pure refresh/decision logic + transactional SQLite tile storage + bounded Overpass fetch + foreground-service integration.
Tech: Kotlin, SQLiteOpenHelper, coroutines, existing Compose and Android CI.
Spec: ../specs/2026-10-02-offline-road-limits-design.md

Global constraints: 20-mile target; 15-minute check; no blocking Start; retain old data on failure; <=90 daily requests /9 MB transfer, one request at a time, >=30 sec spacing; seven-day refresh age; 32 MB public-payload/160 tile cleanup plus database overhead; static tagged limits only; no navigation; no source modifications.
Review focus: Overpass successful HTTP with runtime remark/partial geometry; stale timestamps across restarts; noisy GPS at nearby parallel road; opposite-direction feedback; stop while download or DB work is in flight. Pin these in tests and review.

1. Persistent road subsystem: RoadCache.kt (tiles/planner/query models), RoadDb.kt (atomic storage/spatial queries/corrections), RoadDownload.kt (strict JSON/count/size validation and budget). Tests: RoadCacheTest unit and RoadDbTest instrumentation. Write RED fixtures first; prove failed refresh preserves old data; implement; run suite; commit.
2. Decision subsystem: LimitDecision.kt, existing Core upcoming/matcher reuse. Interfaces: decide(fix, match, sourceLimit, ownerLimit, boundaries, now); tooEarly(fix,now); changedNow(fix,now) yields persistent boundary. Tests: LimitDecisionTest covers 30->upcoming20, jitter, same-limit, owner priority, unknown, subsequent boundary and opposite direction. RED -> implement -> suite -> commit.
3. Integration: DrivingService starts without waiting, reads local candidates off main, runs bounded cancellable refresh, publishes subtle transient status and decision evidence; MainActivity feedback actions and parked reset/override. Existing sound/vibration/camera engine unchanged. Test integration lifecycle/DB and compile UI. Commit.
4. Verify all Android unit/build/lint/emulator tests, review final diff and resolve findings; update README and acceptance report with exact evidence and limitations; push candidate branch, verify CI, provide APK for physical acceptance. Never merge or claim physical PASS without owner report.
