# Speed Buddy 0.2.8 — road-cache efficiency retest

This candidate is for owner physical testing only. It does not claim physical acceptance and must not be merged until that testing passes.

It uses the existing permanent Speed Buddy signing identity. `owner-build.json` records the exact source SHA, package, version and signer proof.

## What changed

- A genuinely complete downloaded road tile is durable local Speed Buddy knowledge and is not automatically downloaded again just because it is old.
- During driving, a missing current tile is first priority; one moving look-ahead tile is second priority.
- The service no longer attempts to fill the entire 20-mile diagnostic circle during ordinary driving, protecting the public-provider allowance for roads actually being used.
- Incomplete, failed or partial responses do not count as completed coverage. Owner corrections and learned boundaries remain local precedence data.

## Physical retest

Use a passenger for taps, or stop safely before interacting.

1. Drive through previously downloaded Skelmersdale and Ormskirk roads. Their limits should remain available and Diagnostics should not show an automatic refresh solely because saved data is old.
2. Enter a new/unknown area. Verify that the current road gets priority and a small moving look-ahead area is fetched without the coverage counter racing to fill the full 20-mile circle.
3. Repeat offline in previously saved coverage. Confirm saved limits, corrections, boundary learning, camera alerts and voice warnings continue to work.
4. Verify a known → brief source loss → same known limit remains continuous as the existing assumed-limit behaviour requires.
5. Report any issue with approximate location/direction, posted limit, displayed value and the Diagnostics map-request text.

Do not merge until owner physical acceptance is recorded.
