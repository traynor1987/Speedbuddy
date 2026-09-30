# Signed releases and updates

The app ID is `uk.co.traynor.speedbuddy`. Release signing is configured only when the four `SPEED_BUDDY_*` environment variables are present; the unsigned local `assembleRelease` is not distributable. Debug builds use the Android SDK debug signer and cannot update in place to a release signed with the owner's permanent key. Back up cameras and settings to a JSON file, uninstall debug, install the signed acceptance APK, and restore the backup. Uninstalling deletes local app data, including cached maps and imported camera data; the bundled camera snapshot will be reloaded. Keep the backup off the device as well.

## One-time owner setup

Create a long-lived Android JKS signing key on a trusted computer, with a unique store password, key alias, and key password. Back up the keystore and passwords in separate durable private locations. **Never commit the key, paste it into an issue, or store it in a build artifact.** Changing or losing this key prevents normal updates of the installed release app. In repository Settings → Secrets and variables → Actions, set:

- `SPEED_BUDDY_KEYSTORE_BASE64`: base64-encoded bytes of the JKS keystore, one line.
- `SPEED_BUDDY_STORE_PASSWORD`: keystore password.
- `SPEED_BUDDY_KEY_ALIAS`: alias within the keystore.
- `SPEED_BUDDY_KEY_PASSWORD`: password of that alias.

The `Signed Android release` workflow can be run manually from the branch to produce a signed acceptance artifact without publishing a release. It runs tests and lint, assembles the release APK, verifies its signature with `apksigner`, and uploads the APK as a time-limited private Actions artifact. After physical acceptance and merge to main, a `v<versionName>` tag pointing into main history builds and publishes the same named APK as a GitHub release. A tag that does not match the Android version or points outside main history fails. Increase both `versionCode` and `versionName` for every new release.

## In-app update centre

Settings → Updates checks the public GitHub `/releases/latest` API only on demand, while parked. It selects the stable release asset named `SpeedBuddy-release.apk` when its semantic version is newer. The app downloads over HTTPS, accepts redirects only to GitHub release asset hosts, caps size, verifies the GitHub asset SHA-256 digest, checks the APK package ID and increasing Android version code, then compares its APK signing certificate with the installed signer. It hands the verified APK to Android's installer using a temporary read-only FileProvider URI. Android asks for permission to install from Speed Buddy if needed and confirms installation. There are no silent installs, background downloads, or driving-time interruptions. Offline or missing releases report a recoverable error; alerts and road data are unaffected.

The downloaded file is a temporary cache entry. It is replaced by a verified APK only after all checks pass; an incomplete or failed download is removed. Android independently verifies the update signature again during installation. Device acceptance must exercise the signed-build upgrade path; the debug build correctly refuses an APK signed by a different key.
