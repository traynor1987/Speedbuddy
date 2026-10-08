# Android Auto prototype — unavailable in the 0.4.0 acceptance candidate

The retained car presentation observes phone-owned DriveBus state only. It does not own GPS, driving, camera detection, navigation or audio. Screen destruction cancels its collector; a new screen would subscribe to the current phone state. Presenter tests cover known/unknown/assumed limits, cameras and upcoming limits.

The earlier experiment declared the NAVIGATION category despite providing no destination, route or turn-by-turn guidance. Cycle 4 removes that declaration, navigation-template permission, car application metadata and service registration. The retained service rejects non-allowlisted hosts rather than accepting every caller. The owner candidate will not appear in Android Auto's launcher. There is no alternate category, developer-mode workaround or platform bypass.

Official supported-category requirements: https://developer.android.com/training/cars/apps/library and https://developer.android.com/training/cars/apps/navigation. Host validation: https://developer.android.com/reference/androidx/car/app/validation/HostValidator.

Google Maps can continue its own navigation alongside phone-based Speed Buddy. Music ducking, guidance interaction, real vehicle disconnect/reconnect and DHU rendering have not been physically validated. No vehicle or DHU is available to engineering here. A future supported integration requires a truthful category and real host validation; this is not a blocker for phone physical acceptance.
