# Speed Buddy architecture — 0.4.0 correction candidate

`DrivingService` owns GPS and the foreground lifecycle; phone surfaces observe `DriveBus`; the retained Android Auto prototype is not registered in the acceptance candidate. Speed/location publish immediately. A latest-fix queue rejects superseded asynchronous results; every valid fix receives fresh regional spatial matching. The shared limit pipeline selects provider authority, then durable owner evidence and bounded, visibly assumed continuity. No unmatched road identity or legal limit is invented.

Regional packs are app-private, verified SQLite/RTree schema-v1 databases for Lancashire and Merseyside. Point candidates and bounded 350 m context are queried separately. Geometry supports direction, carriageway/camera checks, turns and connected previews within the existing 200-yard range. Incomplete/conflicting context suppresses dependent decisions. Unknown/uncertain covered regional states are terminal; only genuinely unavailable regional coverage permits legacy fallback. Static UK national and directional tags share a parser; unsupported conditional/variable/lane data remains Unknown.

`RegionalPackFiles` coordinates all in-process stores and matcher readers. Readers retain a read lease through database close; activation/deletion/recovery require an exclusive lease. Downloads prepare outside that lease, with one operation per region and a deletion revision that rejects late activation. Verified databases and fsynced pointer files are activated by rename. Healthy active data survives verification failure; obsolete versions and orphan temporary work are reclaimed without reaching owner data. Android components currently share one app process; this coordinator is not a cross-process file lock. A future separate-process reader would require additional coordination.

Offline management merges local inventory independently of authenticated catalogue state. There is no resumable or delta download. Restart abandons partial work and reclaims orphan files before a new attempt. Regional activation is separate from owner cameras, road corrections, learned boundaries and backup receipts. Native SQLite resources are scoped with `use`; no long-lived regional reader survives a match.

Legacy tile caches and parked map details remain separate from regional packs. Legacy cache cleanup protects local coverage and owner tables; actual cursor stepping executes incremental vacuum. Databases originally using no auto-vacuum are converted by an explicit maintenance VACUUM outside transactions. `secure_delete` is enabled for deleted database payloads. These operations preserve owner rows and do not guarantee forensic erasure beyond the database.

## Privacy and backup

Up to **500** locally retained diagnostic payloads include recent coordinates, headings, GPS accuracy, times, road identity/confidence and decision/correction evidence. Each payload is limited to 32,000 characters. Retention is count-based with no fixed time expiry, persists through restart and can be indefinite while inactive. It is a bounded history of recent positions, not an analytics or navigation journey service.

**Clear local diagnostics** is available after driving stops. It deletes only diagnostic rows, persists a cutoff against late inserts and runs maintenance; new sessions create new diagnostics. It neither resets owner data nor removes externally saved backups. No diagnostic upload/export feature exists, and owner JSON exports exclude diagnostics. Clearing does not guarantee erasure from external backups or filesystem snapshots.

Owner backup version 13 includes personal camera/settings/correction/junction data, directed/shared road evidence, learned boundaries and observations. Restore validates portable JSON and retains original receipts. Public caches, regional packs, temporary reports and regional access tokens are excluded. Access must be configured and packs downloaded separately after a clean install. Android automatic backup is disabled.

## Remaining acceptance limits

Production regional pack contents/coverage were not independently inspected because unauthenticated endpoints returned HTTP 401. Synthetic SQLite/RTree fixtures establish deterministic pipeline and lifecycle behaviour, not physical accuracy. GPS/OEM process behaviour, field tagging, camera/audio accuracy and Android Auto host behaviour still need owner/device acceptance. A stopped or killed driving service requires the owner to start driving again; offline pack discovery does not silently restart GPS tracking.
