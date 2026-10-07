# Speed Buddy 0.2.9 — road-cache efficiency + Android Auto integration

This signed prerelease is for owner physical testing only and must remain unmerged pending acceptance.

## Combined changes
- Completed trustworthy road tiles remain durable local knowledge; ordinary driving does not re-download them solely because they are old.
- Current missing coverage is first priority and one useful moving look-ahead tile is second.
- The passive Android Auto Car App Library surface observes existing DriveBus state and never starts a duplicate GPS, driving service, camera engine or audio path.

## Physical retest
1. Drive saved roads and confirm limits remain available without age-only refresh.
2. Enter new coverage and confirm current-road priority plus limited look-ahead.
3. Verify corrections, learned boundaries, camera alerts, voice and offline behaviour remain normal.
4. Install using the owner's working private Android Auto installation method; confirm Speed Buddy appears in Customise Launcher and opens while phone driving is active.
5. Confirm no duplicate GPS, camera or speech behaviour.
