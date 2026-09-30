# Junction Cameras Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add editable four/five-way groups containing individually placed fixed cameras.
**Architecture:** CameraJunction metadata and memberships decorate existing owner Camera records. Map edits the group and its members; the existing detector shares encounter cues by group while retaining per-camera relevance and passing. SQLite and owner backup preserve the relationships atomically.
**Tech Stack:** Kotlin, Compose, MapLibre, SQLite, existing TTS/CI.
**Spec:** docs/junction-cameras.md

## Global Constraints

- Four/five refers to connecting roads, not a required camera count.
- Group members are speed, red-light or combined owner cameras with known direction within 300m of the centre.
- Existing fixed/mobile alerts, data layers, 300/100-yard cues and 850m rearming remain.
- No external service, credentials, accounts or dependencies.
- Keep the current draft branch; physical acceptance remains before merge/release.

## Review Focus

- Same group switching camera must not repeat any cue; direction/passing remain per member.
- Ungroup/deleting one member must preserve other cameras and unrelated owner data.
- Invalid backups and failed writes must not leave half-restored memberships.
- Repositioning/group edits must validate actual member geometry.
- Rotation/back/cancel and parked checks must preserve drafts and prevent unintended writes.

### Task 1: Model and encounter grouping

Files: JunctionCameras.kt, Core.kt, CameraAnnouncement.kt; JunctionCamerasTest.kt.
Interfaces: CameraJunction(id,name,point,ways); Camera.junction; JunctionRules.validateMember; CameraEncounters.key.
- [x] Add failing tests for model/member validation, relevant group member selection, shared approach/close/speeding stages and per-camera passing/rearm.
- [x] Implement metadata validation and shared keys in the existing detector; retain per-camera geometry, distance/history and passing.
- [x] Run the focused JVM suite; all new and existing cases must pass.

### Task 2: Persistence and backup

Files: JunctionStore.kt, Data.kt, Backup.kt, MainActivity.kt; JunctionDbTest.kt.
Interfaces: JunctionStore.all/save/remove/attach/setMembership; OwnerBackup.junctions; export/parse schema 8.
- [x] Add Android tests for schema 9 migration, restart, member edits, ungroup/delete, backup roundtrip, legacy backup and invalid restore rollback.
- [x] Add schema 10 tables; decorate bounded/all owner camera reads. Validate and save member links transactionally alongside camera writes.
- [x] Restore metadata before member cameras in the existing owner transaction; export all groups including empty ones.
- [x] Run Android database tests in CI; preserve imported/mobile/correction layers.

### Task 3: Map and driving UI

Files: JunctionEditor.kt, CameraMapScreen.kt, CameraDetailSheet.kt, MainActivity.kt, DrivingService.kt.
Interfaces: junction editor/list/detail callbacks; CameraMapEditor optional junction context.
- [x] Add UI tests for choosing four/five, persisted editor state and member direction validation.
- [x] Add Map Add menu, centre placement, saved junction marker/management, individual pin placement and grouped editor context. Require direction for grouped members; allow ungroup without deleting cameras.
- [x] Show group name/road count on Drive and camera details; maintain queued audio validity across relevant members of one group.
- [ ] Run full unit/lint/APK/emulator checks, focused review, version 0.1.8 and deliver the acceptance APK with signing/backup guidance.

Execution ruling: developer instructions and the user's feature request authorize implementation without extra design/plan approval stops. Implement natively in this session; use one focused reviewer before delivery.

## Execution evidence

- Model/encounter tests: initial implementation failed four new tests, then passed.
- First complete Android run b4157d0: unit/lint/debug/test APK checks passed; 39 API-35 database/UI tests passed, including schema migration, backup and editor restoration.
- Final review found three important cases: unvisited arms marked passed; a cancelled position reused by a different member; moved-centre backup restoration validated against obsolete geometry. No minor findings.
- Turning and cross-camera position regressions were observed failing locally, then the focused JVM suite passed 30 tests after per-member approach tracking and camera-owned position drafts.
- Overlapping backup restore regression failed against the original implementation on API 35 (9e68038): 41 tests, one failure in restoreCanMoveJunctionTogetherWithItsReplacedMembers. Restore now checks retained members against incoming metadata and validates replaced members during the transactional merge. Ordinary centre edits keep full member validation.
