# Staged camera warnings — 0.1.7

The existing camera detector now emits one combined cue per GPS fix, sharing its
road/direction relevance checks, stopped-card retention and encounter rearming.

- At 300 yards (274.32m): the usual camera type and reliable limit announcement.
- At or inside 100 yards (91.44m): one double beep per encounter.
- While approaching inside 300 yards, speed above a reliable limit plus the
  configured Warning threshold produces one double beep followed by
  “Warning, speeding. Speed camera ahead. Speed limit 30 miles per hour.”
  Mobile, red-light, combined and average camera wording retains its type.
- If stages coincide, one double beep and one announcement cover them together.
  The 100-yard reminder still occurs after an earlier speeding warning.

A camera's recorded limit takes priority. Otherwise, the current road limit
requires a confident road match and camera within 20m of its geometry. Unknown,
weak or unrelated road limits cannot trigger a speeding warning. The default
warning margin is +2mph; Settings allows 0/1/2/3/5mph. This margin is a warning
preference, not a statement of lawful speed or enforcement tolerance.

Camera speeding warnings operate with the camera category enabled, independently
of the optional general overspeed warning. Voice off suppresses speech but keeps
the new double beeps. Vibration retains its existing switch. The optional general
overspeed beep does not overlap an active camera warning.

Only an accurate moving fix with heading can emit a new stage. Stopping and weak
GPS preserve the current approach card without consuming or repeating cues.
Expired/removed mobile reports, disabled categories, behind-driver and rejected
roads cannot emit stages. Leaving the existing 850m encounter area rearms all
stages together; merely stopping or fluctuating around 100 yards does not.

Two 160ms tones, with a 110ms gap, precede speeding speech. The existing temporary
navigation audio focus ducks compatible media players and is released afterwards.
Media volume is never changed. Beeps do not wait for the speech engine. Queued
speech has a ten-second maximum age and rechecks the current camera identity,
category/voice settings, GPS freshness, speed and current reliable limit. Changes
to the limit or a driver slowing below threshold cancel stale speeding speech.
The latest still-valid limit change waits for camera audio to finish.
Stop Drive, focus loss and a timeout cancel pending
tones/speech and release resources. A beep-only reminder can accompany ongoing
approach speech without cutting the sentence off. TTS failure retains audible
fallbacks; actual routing and volume depend on Android/audio settings.

## Physical acceptance

Use an existing real camera or a temporary local report, and passenger-operated
checks. Do not exceed a road limit to test the speeding stage on public roads;
use an Android emulator/mock-location trace for the overspeed condition.

1. Approach below the warning threshold: hear the usual alert near 300 yards,
   then two beeps near 100 yards. Keep approaching; neither stage repeats.
2. Stop before passing, resume and fluctuate around the close threshold. The
   card stays visible and completed cues do not repeat.
3. Replay a GPS trace above a known limit plus margin: two beeps, then the speeding
   sentence with the correct camera type/limit. Starting inside 100 yards emits
   one combined sequence. Unknown/weak road limits do not invent a speeding cue.
4. Pass, leave the encounter area, then approach again; all stages rearm.
5. Try mobile and fixed camera switches, Voice off and Vibration off. Voice off
   keeps two beeps. Disabled camera categories stay silent.
6. Play music, check temporary ducking and restoration after both beeps and speech.
   Stop Drive during the beep gap/speech and check there is no delayed cue.
7. Recheck opposite direction, parallel-road filtering, GPS loss, offline operation,
   speed-limit transitions, map and background/resume.

Automated regression cases cover distance staging, deduplication, speed/tolerance,
limit selection, stopped/weak fixes, rearming, mobile expiry and combined audio.
Device GPS/audio acceptance remains necessary before merge/release.
