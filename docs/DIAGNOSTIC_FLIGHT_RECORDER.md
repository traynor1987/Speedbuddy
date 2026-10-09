# Diagnostic flight recorder — 0.4.0

Focused execution plan: preserve the current driving decisions and dashboard. Add a process-local, 500-event recorder at GPS ingress, regional result, pipeline evidence, presentation resolution and DriveBus publication. Capture immutable privacy-safe snapshots before StateFlow/UI conflation. Serialise publication and capture together; do not add a new GPS or warning owner.

1. Pin sub-second capture, deduplication, bounded retention and strict export whitelisting with JVM tests; implement the recorder and source hooks.
2. Add a process-local inspection snapshot that survives Activity recreation. Freeze only the Diagnostics screen, including time-dependent rows. Add newest-first history, explicit share/export and history-only clear. Test these controls on Android.
3. Run the complete JVM/Python suites, debug/release builds, instrumentation assembly and lint; require both exact-head CI gates including emulator tests. Review changes, commit to the existing branch and publish a permanent-signed diagnostic prerelease.

Investigation and final verification results are appended during execution. Physical acceptance remains FAILED/PENDING. The unknown intermediate values observed by the owner are not established by this code inspection, and the earlier 15-second Unknown boundary gap is not claimed resolved.

## Publication investigation

The service requests GPS callbacks at a minimum interval of 1,000 ms, with zero distance filter; callback timing remains device-controlled. It immediately publishes a pending current-fix state, then awaits regional and optional legacy/owner database reads and publishes the evaluated state. A live response can retry the latest compatible frame without waiting for another GPS callback. Thus two or more meaningful publications can occur within one second, even at zero speed. Stationary position/heading/accuracy drift can change geometry matching and confidence despite zero speed.

Pending assumptions expire after the existing 2-second/30-metre bounds via a scheduled job and 1-second service maintenance. Genuine unresolved connected transitions retain the existing 5-second presentation window. These paths are recorded, not weakened or delayed. Stale callbacks and superseded regional results are recorded as rejected, not as current driving decisions. The existing queue and pipeline freshness guards remain.

Diagnostics directly observes DriveBus StateFlow. It can conflate a pending/completed pair, and Compose can redraw or skip frames independently. Source hooks are synchronous before StateFlow publication or presentation smoothing; UI subscriptions never create events. The service owns decisions on the main dispatcher, with regional/database/network work off-thread. No underlying state race was confirmed from the owner's observation. Publication/expiry/capture and freeze are now serialised under the bus lock for internally consistent inspection.

## Retention and privacy

500 immutable events, bounded fix/opaque-ID caches, and per-stage previous snapshots are memory-only. Activity recreation keeps the process-local recorder and frozen snapshot; process death clears them. An export writes one JSON report under the private app cache and opens Android's share chooser only after an explicit button press. It reads neither existing location-bearing diagnostic receipts nor preferences, databases, owner backups or authentication. Existing persistent local diagnostics stay separate.

Report fields are a whitelist. Road and pack-generation identifiers use a random process salt and SHA-256; road names, geometry, points, source tags and free text never enter the exported model. Reasons use fixed classifications; inherited road IDs and remote messages are omitted. Availability flags and nulls distinguish an unevaluated stage from an actual Unknown decision. Match confidence is numeric; evidence confidence is the pipeline's categorical confirmed/assumed/owner/unknown state, not an invented independent numeric score. No precise-location export option is needed or provided.

## Regression scope

New JVM cases pin rapid confirmed/assumed/unknown capture with no UI subscriber, millisecond ordering, repeated identical samples, time-only/camera-only deduplication, stationary drift/confidence changes, concurrent capture, 500-event eviction/clear, clock monotonicity, 30-second counts, frozen reports, redaction and stale pipeline rejection. Android cases exercise actual pending/final publication, delayed/stale rejection, concurrent bus writes, frozen correction safety, freeze/unfreeze/restoration, lagging rendered state, recomposition-only reads, clear/camera/audio eligibility and on-device FileProvider export readback. The real regional service delay fixture additionally verifies the old match is recorded and rejected while the newer decision remains current. Existing same-limit junction, different-limit, live/legacy, owner and directional regressions remain required.

## Local validation before publication

Starting remote HEAD: `4a50793dc3099c7c8a34d165513312f8dc649b7a`; push #300 and PR #301 succeeded. PR #7 verified open/draft/unmerged, base main unchanged.

309 JVM tests passed (zero failures/errors/skips); 26 Python tests passed. Debug, release and Android instrumentation APK assembly succeeded. Debug/release lint: zero errors, existing 81/83 warnings. Actual release test signing/package/version/source verification and mismatched certificate/SHA rejection passed with a disposable test key that was destroyed. The permanent signer is used only by the existing owner CI publication job. Android runtime validation is delegated to the exact-head CI emulator gate because this workspace has no KVM device.

Independent review found and corrected stage-availability labelling and frozen-action/snapshot consistency issues in the new recorder/UI. No underlying owner-observed race was confirmed. Physical share-chooser recipient access, recorder performance on the owner's handset, rapid flicker cause and the earlier 15-second Unknown boundary gap remain owner acceptance requirements.
