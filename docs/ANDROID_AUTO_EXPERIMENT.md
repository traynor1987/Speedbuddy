# Android Auto passive-surface experiment

This private, prerelease-only experiment uses the Android for Cars App Library
service/template path to determine whether the owner's Android Auto host will
launch a passive Speed Buddy surface without a destination, route or active
navigation session.

The car service observes the existing in-process `DriveBus` only. It never
starts `DrivingService`, location collection, road matching, database refresh,
camera detection, audio scheduling, a `NavigationManager` session or a trip.
Google Maps remains responsible for navigation.

## Install and test

1. Install the signed `SpeedBuddy-0.2.6-owner-acceptance.apk` over the existing
   Speed Buddy install. Do not uninstall: the permanent signing certificate and
   increased version code permit an ordinary in-place update.
2. On the phone, open Android Auto settings and tap the version entry repeatedly
   until Developer settings are enabled. In Developer settings, enable
   **Unknown sources** for this private sideload test.
3. Start Speed Buddy on the phone, grant the existing precise-location
   permissions, and tap **Start Driving**. This remains the single GPS and
   warning owner.
4. Connect to Android Auto and open the launcher. Look for **Speed Buddy**;
   open it with no destination selected.
5. Confirm that the screen stays open while Google Maps remains the separate
   navigation app. Disconnect/reconnect and verify the phone driving session
   continues without duplicate speech or alerts.

## Expected display

The template host controls physical sizing. On a full-width panel it presents
the current limit first, then GPS speed, followed by camera and credible
upcoming-limit rows. On a split/half-width panel the same template is allowed
to collapse/truncate secondary rows; the first row remains the limit-first
presentation. Assumed limits are explicitly marked `⚠` with `Assumed — not
confirmed`; a credible continuity interval is never converted to `Unknown` by
the car surface.

## Known framework limitation

This does not use custom drawing, a map surface, route calculation, turn-by-turn
guidance, a destination or an active navigation session. The Android Auto host
owns template typography and exact layout, so a UK circular speed-sign graphic
cannot be forced. This APK is an empirical host-compatibility test and is not a
claim of Play-distribution eligibility.
