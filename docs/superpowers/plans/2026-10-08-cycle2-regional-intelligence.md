# Cycle 2 regional intelligence implementation plan

**Goal:** Complete the owner's Cycle 2 contract on the existing draft 0.4.0 branch.
**Architecture:** One canonical context feeds decisions, cameras and turns. Current-position matching stays separate from bounded look-ahead geometry. Pack failures are isolated and activation generations invalidate previous evidence. Live numeric responses require trustworthy local geometry.
**Tech stack:** Existing Kotlin/Android, bundled SQLite RTree and JUnit/emulator tests.
**Spec:** Owner's 8 October Cycle 2 instruction and technical audit; Cycle 1 corrections remain authoritative.

## Constraints
Keep version 0.4.0/code 20, preserve owner data, do not modify main or merge PR #7, do not begin Cycle 3. Use one coherent publication after local checks. The owner explicitly directs execution without restarting design/planning.

## Tasks
1. Add failing parser/turn/upcoming tests; unify UK tag interpretation and permitted outgoing geometry. Missing/invalid/conditional tags stay Unknown; highway class alone never supplies a limit.
2. Separate point candidates from bounded surrounding context in RegionalPackMatcher. Pass canonical contextual roads from DrivingLimitPipeline to camera and turn consumers. Test regional-only junctions and parallel carriageways through real installed SQLite packs and service callbacks.
3. Isolate corrupt pointers/databases/rows, reset evidence across active-pack generations, prevent live numeric responses without compatible local identity. Test activation, deletion, corrupt/missing packs, dense candidates, offline/timeouts and provider transitions. Retain owner-correction persistence/backup tests and add regional transition coverage.
4. Run full JVM/Python suite, assembly and lint; inspect diff against Cycle 1. Publish through GitHub with an expected-head lease, verify both exact-head workflows, investigate failures, report production fixture limitations and stop at Cycle 2.

## Review focus
- Reverse-only roads and directional conditional tags must not offer illegal previews.
- Corruption in one regional pack must not suppress another healthy pack.
- Dense context truncation must not pretend to have complete carriageway evidence.
- Pack switches must discard old numeric authority while keeping durable owner evidence.
- Live responses without matched local geometry cannot invent identity or numeric authority.

## Execution ledger
- Starting HEAD 0b1506cff936fc14822e9b4d0eceefea8a347b2f confirmed remotely; PR draft/unmerged.
- Cached toolchain recovered; source checkout isolated from earlier workspaces.
- First local test attempt found incomplete cached Kotlin DSL metadata. Use session-specific Gradle home with dependency cache; leave the original cache untouched.
- Red: initial four parser/preview regressions failed in both focused exact-source harness and full Gradle test task.
- Green: first full local pass ran 236 JVM tests, assembled debug/instrumentation APKs and passed lintDebug. Initial incremental duplicate declarations disappeared after a clean non-incremental build; no source constants were changed to mask it.
- Review: corrected covering-reader completeness, restricted-30 symbol, accepted source-tag conflicts and neighboring-pack context. Added overlap/truncation/border fixtures and directional lane sharing guard. Production catalogue/manifest access returned 401; synthetic fixtures are explicit.

- Final look-ahead review: a 10 m same-limit remainder plus 90 m continuation failed before the fix and passes afterward; direct boundaries below 25 m retain their existing exclusion. Full local JVM suite: 240 tests, no failures/errors/skips; Python suite: 23 passed.
- Final full local validation: testDebugUnitTest, assembleDebug, assembleDebugAndroidTest, assembleRelease, lintDebug and lintRelease all passed. Emulator is unavailable locally; both exact-head CI runs remain the completion gate.
