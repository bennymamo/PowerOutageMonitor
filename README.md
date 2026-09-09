# Power Outage Monitor

An Android power-outage monitor by Flossy Pickle. Package: `com.flossypickle.poweroutagemonitor`.

## Current milestone

The starter greeting has been replaced with a live power dashboard showing external power, source, battery percentage and charging status. Observation is event-driven and active only while the activity is started. No background monitoring, outage confirmation or alert delivery is implemented yet.

Android 6.0 (API 23) minimum; compile/target API 37. Kotlin and Jetpack Compose, one application module. API 36 emulator testing is the initial development target; physical old-device testing is required before reliability claims.

## Accepted design

- Wait for the first external-power connection before arming outage detection.
- Judge external power by the plugged source, never charging status alone.
- Build a pure Kotlin outage engine, then persist its state/history using Room.
- Store settings with DataStore. Use UTC history timestamps and monotonic elapsed time within a boot.
- Add user-enabled foreground monitoring with a quiet, compact notification.
- Support boot recovery before first unlock using device-protected monitoring state; keep credentials separate.
- Add Telegram only after background and boot behavior are validated. No cloud backend, analytics or advertising.

Planned transitions: waiting for connection -> powered -> pending outage -> confirmed outage -> pending restoration -> powered. Early restoration cancels a pending outage. Disconnection during pending restoration continues the same outage. Unknown readings must never imply a power loss. Recovery must expose observation gaps.

## Build

Open this existing directory in Android Studio and use its bundled JDK. From PowerShell, with JAVA_HOME pointing at that JDK:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

No additional permissions or network access are requested by the current dashboard. No user information is transmitted. Power loss indicates charger disconnection, not independently verified mains failure.

A standalone outage-rule engine and unit tests are included as the next foundation. It is not yet connected to the dashboard or persistence. Its clock values are valid only within a single boot; recovery will be implemented by the persistence/coordinator layer.

## Visual design

Dark navy surfaces with mint external-power and amber battery indicators. The compact battery gauge is drawn natively in Compose so the charger-test instructions remain visible on the Pixel 4 emulator at default text size. Scrolling remains available for smaller screens and larger accessibility text. A matching vector lightning-bolt launcher icon includes legacy API 23 and adaptive/themed variants. No image or icon library is required.

## Validation

Debug build, six outage-engine tests and Android lint passed on 9 September 2026. API 36 emulator checks verified the dark dashboard, launcher graphic and AC power-source update while battery status remained not charging. Lint reports no errors; warnings include existing dependency versions and unused starter resources. Android 6.0 and physical-device behavior are not yet verified.
