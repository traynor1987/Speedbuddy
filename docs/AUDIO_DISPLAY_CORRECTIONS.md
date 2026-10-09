# Audio/display diagnostic correction — 0.4.0

Starting verified branch head: `b8fe9d85f29ab46f895c2b339938a0d014c8603f`.
Physical acceptance remains FAILED/PENDING. PR #7 stays draft, open and unmerged.

## Established implementation causes

Current-road speech was selected from the published numeric presentation, not directly from a raw regional or upcoming-road limit. However, relevance was checked when submitting speech and draining engine initialization, with an empty TTS `onStart` callback. A new GPS pending/Unknown publication could invalidate the road decision before the asynchronous matching/database work completed and called audio revalidation, or before the next maintenance tick. Confirmed speech relevance also lacked a fresh-fix age check. Deterministic regressions reproduce stale numeric eligibility and an invalidated queued announcement.

Camera speech separately prefers the camera's enforced limit over the matched current-road limit. It previously described that number as “Speed limit…”, indistinguishable from a current-road announcement. A verified camera-specific 30 can legitimately be announced while the current road is Unknown. It now says “Camera limit…”. Camera-specific eligibility and overspeed alerts remain independent of current-road certainty.

These are confirmed code paths, not a reconstruction of the owner's uncaptured event. The screenshot does not establish its announcement category, fix generation or playback timing. No evidence establishes a new matcher confidence defect or a presentation latch in this incident: fresh confirmed regional evidence already replaces Unknown immediately. Regional geometry, directional evidence, legacy/live corroboration, owner corrections, learned boundaries, generation ordering and confidence thresholds are unchanged.

## Focused correction

Current-road candidates require a fresh fix, a completed confirmed applicable decision and agreement with the final numeric presentation. Assumed, Unknown, changing and conflicting presentations cannot schedule a new current-road announcement. An already verified cue retains the existing bounded same-road processing allowance; this does not confirm an assumed display.

The service observes every source publication before UI conflation, cancels deferred current-road cues when invalidated, and revalidates submitted/initialization-queued current-road speech at publication. The TTS start callback checks relevance, voice settings and queue age again. Superseded callbacks cannot clear or stop a newer valid utterance. No numeric display delay was added. Existing same-number suppression, first-known silence, voice settings, camera priority, focus handling and beep fallback are preserved. Upcoming previews remain separate and cannot become current-road speech; there is no existing upcoming-limit TTS producer to replace.

## Recorder correlation and privacy

The existing 500-event rolling recorder now includes typed numeric speech tickets for CURRENT, UPCOMING, CAMERA and OVERSPEED. Scheduling, deferral, submission, observed playback, completion, cancellation, supersession, expiry and failures share the road recorder's monotonic timestamps and global event sequence. Tickets preserve the originating fix sequence, privacy-safe road/camera identifier, source, confidence, evidence timestamp/age and scheduling display. Observed playback captures its actual current publication separately. An unobserved playback is explicitly marked; submission is not proof of sound.

Camera-tag timestamps identify GPS applicability verification, rather than pretending that the stored tag was freshly downloaded. JSON schema version is 2. No spoken text, coordinates, raw IDs, road names, camera notes, credentials or authentication headers are added to reports. Existing whitelist redaction and process-salted identifiers remain in effect. Nothing uploads automatically. Diagnostics recent changes show speech category, number, outcome and source without changing dashboard styling.

## Validation scope and owner retest

JVM regressions cover stale/Unknown eligibility, immediate regional recovery, initialization/deferred invalidation, same-limit identity changes, real numeric changes, camera/upcoming separation, legacy/live and owner evidence, assumption recovery, stationary fluctuations, ticket correlation, redaction and the shared ring-buffer budget. Android regressions exercise real publication/queue/callback/timeout handlers, camera independence, late superseded callbacks and delayed-beep supersession. Existing installed-pack and service regressions retain delayed regional/network generation checks.

The Android speech tests seed queues and invoke the production callback handlers without requiring an installed voice. They do not establish handset TTS callback latency or prove that every audible syllable can be cancelled once playback begins. Export a report promptly after a recurrence while stopped, before the rolling history expires or the process exits. Check both current-road transitions and camera phrases with the owner's voice settings, retaining the installed packs and all owner data.

Capturing rapid state changes does not prove their underlying cause fixed. The earlier approximately 15-second Unknown boundary gap is not established as resolved. Real-road acceptance remains pending.
