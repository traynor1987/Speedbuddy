# Permanent Owner Acceptance Implementation Plan
> Execute inline using executing-plans; final review by a separate reviewer if available.
Goal: prepare safe backup restore and exact permanent-signer acceptance pipeline.
Spec: ../specs/2026-10-02-owner-release-design.md
Constraints: preserve existing offline implementation; same package; 0.2.1/code10; PR draft; no owner data removal; no keys/passwords in repository/logs/reports.
Review focus: unsupported legacy records must not vanish; unknown version rejected before writes; interrupted restore retains archive; signer pin mismatch cannot upload; source manifest matches APK.
### Task 1: Backup safety
- Write instrumentation tests for v8 import (core + retained fields), v9 road correction round-trip/reopen, unknown-version rejection and archive-before-apply.
- Verify failures against baseline, then implement Backup codec/store, RoadDb owner correction merge, import confirmation and bounded off-main-thread IO.
- Run instrumentation + unit suite.
### Task 2: Permanent signer pipeline
- Configure version and embedded source SHA; compile release unsigned in validation CI, remove installable debug artifact upload.
- Add signed push job after unit/build/emulator checks, secret availability gate, pinned signer verification and public provenance.
- Add trusted-computer setup script and secure documented owner steps; no private key generated here.
- Run setup/verification script negative checks and unsigned release build; owner-signed output requires securely configured secrets.
### Task 3: Exact candidate handoff
- Run full JVM/emulator/lint/debug+release validation, independent review.
- Push one exact candidate commit using repository APIs, reverify PR and exact-head CI.
- If secrets unavailable: stop with setup steps, backup coverage limitations and no uninstall instruction. If signer ready: download and verify artifact and stop for owner backup/install/physical testing.
