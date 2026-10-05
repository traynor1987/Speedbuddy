# Full Speed Buddy integration

Owner request: combine the existing map branch and offline road-limit branch and release the complete app. Do not add navigation or redesign the existing features. PRs remain draft and unmerged pending physical acceptance.

Pinned inputs: offline 0906d7a912f9e9c9886e09ad02757d5719a11328; maps 7534d9e0e2d0806abb21c1a7b9c98145c8839ad5; main d95c2043615ac16e9f4a6afe4776b72798c261ac. The 0.2.1 acceptance candidate omitted map features and is withdrawn.

## Task 1: Reproduce and combine branch inventory
- Add a JVM regression asserting the production map/update classes are packaged alongside the limit engine. Run on offline baseline: expected failure because map classes are missing.
- Merge the map branch into an isolated worktree. Preserve all existing map/camera features, tests and assets; preserve offline road engine, tiled persistence, correction picker, boundary learning and secure signing workflow.

## Task 2: Reconcile shared production interfaces
- Keep one authoritative driving-limit decision engine. Adapt map road corrections and richer camera processing to that engine.
- Retain maps, road/camera editors, junctions, mobile reports, camera voice/direction, settings and update tools. Add the offline picker and diagnostics to the retained UI.
- Upgrade camera database from either lineage without destructive replacement. Combine portable backup fields, accepting historical map backups and offline v9/v10 backups; retain originals before restore.
- Use 0.2.2 / code 11 and the existing permanent certificate. Disable obsolete signing workflows that could publish alternate identities.

## Task 3: Verify the combined app
- Add actual UI regression proving the Map entry and sign correction picker coexist.
- Add cross-lineage backup/upgrade tests, directed owner correction priority and cache-to-map integration checks.
- Run all JVM tests, Python signing tests, APK/test compilation and lint. Expected: green, zero lint errors.
- Run all instrumentation on CI, including existing map and offline suites. Expected: green.
- Final fresh-context review of whole integration, then fix material findings and verify.

## Task 4: Publish exact-head full acceptance build
- Push the combined history to the feature branch, identify exact PR head, wait for push and PR CI success.
- Verify permanently signed APK source SHA, package, code/name, checksum and pinned certificate.
- Publish full acceptance APK on GitHub and withdraw the incomplete 0.2.1 candidate. Retain PR draft/unmerged.
- Give owner an update-in-place procedure for permanent 0.2.1 installations and backup-first instructions for historical installations. Wait for physical acceptance.

Review focus: shared database migration; archived backup fields; assumptions never treated as confirmed for warnings; camera direction/audio parity; all entry points visible; map download and driving cache interoperability; release update signature continuity.
