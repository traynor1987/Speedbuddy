# Speed Buddy 0.3.0 — Android Auto visual and road-state retest

This permanent-owner-signed prerelease is for physical owner testing only. It
must remain unmerged until acceptance is recorded. The existing Android Auto
launcher discovery configuration is unchanged.

## What changed

- The passive Android Auto PaneTemplate now uses a pre-generated UK-style
  speed-limit sign as its dominant image for 20, 30, 40, 50, 60 and 70 mph.
- The host scales that image for both the wide display and split/dashboard
  layouts. The compact view keeps the sign and GPS speed; it shows at most one
  contextual row, with an active camera warning taking precedence over an
  upcoming change.
- Assumed limits retain the sign plus `⚠ Assumed — not confirmed`.
- An unknown limit uses a neutral question-mark sign rather than a large
  `Limit unavailable` message.
- A limit is only called upcoming when it is different and genuinely ahead.
  Same-limit, zero-yard and negative-distance transition markers are no longer
  shown as a misleading upcoming limit on either phone or car presentation.

## Physical retest

1. Install `SpeedBuddy-0.3.0-owner-acceptance.apk` as an ordinary in-place
   update over the permanently signed install, using the owner’s already
   working Android Auto installation method.
2. Confirm Speed Buddy remains visible in Android Auto's launcher, opens with
   no destination or route, and shows the large red-ring current-limit sign.
3. Test both the full-width panel and the dashboard/split layout. The sign and
   GPS speed must remain readable; secondary detail should collapse rather
   than make the sign tiny.
4. Confirm an assumed limit shows the small warning state, a real camera
   warning shows its type/limit/distance, and a genuine upcoming different
   limit shows a positive distance.
5. At a limit boundary, verify there is no `Upcoming X in 0 yd`; the app should
   show the current confirmed/assumed state until the transition is credible.
6. Confirm phone limits, offline road cache, corrections, cameras and voice
   remain normal, with no duplicate GPS, camera or audio behaviour on
   Android Auto connect/disconnect.

Do not merge until owner physical acceptance is recorded.
