# Speed Buddy 0.2.3 — physical road retest

The 0.2.2/code 11 physical acceptance candidate failed owner road testing and is rejected and superseded by this correction candidate. This does not claim physical acceptance. PR #2 remains OPEN, DRAFT and UNMERGED.

This replacement uses the existing permanent Speed Buddy signer. Its SHA-256 certificate fingerprint is `ddcf05c06ca3442774ea24273ea9a31c213bbc78520032cfa2994c2118d2f947`. Version code 12 updates code 11 in place. Back up owner data before updating. `owner-build.json` records exact source, APK digest, package and signer proof.

## Short road retest

Use a passenger for taps, or stop safely before interacting.

1. On the previously failing constant-60 road, verify a brief lookup gap displays **60 !**, then returns to **60**, without an Unknown flash.
2. Approach the real 60 → 40 signs. Check **60 CURRENT / 40 UPCOMING**, then **40 CURRENT** when the transition is confirmed.
3. If it changes early, tap the main sign and choose **60** while still in the 60 zone. Expect **60 confirmed here**; the upcoming 40 remains available.
4. At/just after the actual 40 signs, tap and choose **40**. Expect **40 starts here**.
5. Repeat the journey. It should retain 60 before the learned boundary, show upcoming 40, and promote 40 after crossing. Repeat after restarting.
6. Drive in reverse. The forward learned boundary must not apply to the opposite direction.
7. Repeat offline in downloaded coverage. Confirm learned boundaries and saved roads still work. A genuinely unknown/unreliable road must eventually show Unknown; **!** must not persist indefinitely.

If the apparent 40 is false and the real next zone is 30, select **60** while still in 60, then **30** at the real signs. The resulting bypass is local to those road IDs and that direction; unrelated genuine short 40 zones remain supported.

Report any failure with approximate location/direction, posted limit, displayed value and whether **! / Upcoming** appeared. Stop here for owner physical acceptance; do not merge.
