# 0.4.0 failed physical acceptance — corrective source changes

Physical acceptance remains FAIL. PR #7 remains draft/unmerged; version stays 0.4.0/code 20. No owner stores, correction formats or backups are migrated or deleted.

## Confirmed causes and changes

1. The provider resolver selected numeric legacy cache before live, while DrivingService accepted a network result only for the exact latest GPS timestamp. A response taking longer than a GPS update was discarded. The parser also discarded the server's OSM way identity. Live now precedes legacy when a verified regional provider is unavailable; its OSM identity must agree with fresh, confident local geometry. Scoped request evidence is bounded to 5 seconds/100 metres and compatible heading, then revalidated against current geometry. An asynchronous result retries the current frame without overwriting GPS or pending newer frames. Numeric-only or conflicting live identity is uncertain, not a substitute road match.

2. Installation required metadata matcherVersion `1`; the released handoff specifies JSON string `distance-heading-oneway-continuity-v1`. Synthetic SQLite tests repeated the wrong value. The verifier and fixtures now use the released matcher identifier; unsupported matchers still reject. Dataset region comparison parses JSON rather than relying on exact whitespace. Catalogue/manifest identity, sizes, hashes, coordinate system and format/matcher semantics are checked before transfer. Download redirects do not forward credentials. Existing compressed/raw size/digest and SQLite integrity checks remain.

3. The reported 15-second Unknown period is not conclusively diagnosed. Replays of repeated known/gap/known and a strong connected 40-to-national-60 transition do not reproduce it with complete fresh geometry. Existing assumed continuity expires at 2 seconds or 30 metres; extending it to hide a 15-second missing/ambiguous-road interval would violate the existing safety contract. This change does not claim a boundary repair or use zero countdown as legal authority. Live selection/latency fixes address one possible availability contributor, not proof of the physical cause.

## Provider policy

Eligible owner rule remains highest priority and requires its valid matched scope. Covered verified regional Known/Unknown/uncertain is terminal. Absent/unusable regional coverage allows live requests with real heading. Known live evidence requires agreeing local road identity and geometry; Unknown/uncertain or conflicting live evidence cannot be replaced with a convenient legacy number. Genuine live unavailability permits reliable saved legacy matching. While awaiting live, legacy remains explicitly labelled. Missing reliable geometry stays Unknown. Auth/rate/contract failures do not authorize Overpass; no public fallback policy is broadened. Diagnostics include live request classification, response sample age, selected provider and fallback reason. Auth rejection stops retries for that credential; transport failures back off; a credential changed during a request invalidates its response.

## Production acceptance blocker

The production catalogue probe returned HTTP 401 without provisioning. No bearer token or VPS access is supplied in this workspace; the authoritative handoff intentionally contains placeholders only. Its production catalogue sizes/hashes/schema and representative response were read, but neither actual regional gzip/SQLite nor current authenticated manifest was retrieved. Updated fixtures follow the released production schema semantics; they are not actual production pack bytes. Both-region download, verified install, offline reopen and production-coordinate matching remain UNVERIFIED. No server configuration or pack publication was changed.

Because production-pack validation and the reported boundary failure remain unresolved, source publication deliberately suppresses the signed owner APK. A source checkpoint is not a successful physical candidate.

## Retest after remaining validation

Do not uninstall or clear data. Before a new candidate is issued, validate both authenticated production packs and record source-specific road replay evidence for the boundary gap. After an eligible signed update, test no-pack live mode and fallback separately; inspect live request status, age and matched identity. Install/reopen each region offline and confirm regional authority. As a passenger or while parked, capture diagnostics immediately before/after an affected boundary and during Unknown, including road identity, source, confidence, GPS age, assumed state and decision reason. Check assumptions always show a warning and never trigger authoritative overspeed audio. Physical acceptance requires the owner's new test; CI cannot confer it.
