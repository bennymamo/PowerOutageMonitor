# Power Outage Monitor

An Android power-outage monitor by Flossy Pickle. Package: `com.flossypickle.poweroutagemonitor`.

## Current milestone

The app now has Status, History and grouped Settings screens, plus Diagnostics and an isolated Test mode under Settings. A user-enabled foreground service observes Android's external-power state without polling, persists the outage state before first unlock, resumes after reboot or app upgrade, and records completed outages or brief interruptions locally. Alert delivery integrations are not implemented yet.

Android 6.0 (API 23) minimum; compile/target API 37. Kotlin and Jetpack Compose, one application module. API 36 emulator testing is the initial development target; physical old-device testing is required before reliability claims.

## Accepted design

- Wait for the first external-power connection before arming outage detection.
- Judge external power by the plugged source, never charging status alone.
- Keep the pure Kotlin outage engine independent from Android and persist critical state synchronously in device-protected storage.
- Keep a small bounded atomic event-history file; reconsider Room when delivery-attempt queries require relational storage.
- Use UTC epoch timestamps so state can be reconstructed across process death and reboot.
- Add user-enabled foreground monitoring with a quiet, compact notification.
- Support boot recovery before first unlock using device-protected monitoring state; keep credentials separate.
- Add Telegram only after background and boot behavior are validated. No cloud backend, analytics or advertising.

Implemented transitions: waiting for connection -> powered -> pending outage -> confirmed outage -> pending restoration -> powered. Early restoration cancels a pending outage. Disconnection during pending restoration continues the same outage. Unknown readings never imply a power loss.

## Architecture

- `OutageEngine` contains deterministic business rules and has no Android dependencies.
- `monitoring` owns Android battery observations, the foreground service, boot recovery, persisted-deadline alarms and coordination.
- `storage` owns device-protected monitor state and bounded atomic event history.
- `diagnostics` collects a credential-free local health report, and `guidance` supplies replaceable device guidance data.
- `integrations.power` defines normalized grid evidence for Android charging, EcoFlow, Huawei, Tesla, Home Assistant, MQTT, REST, WebSocket, SNMP or other future sources.
- `integrations.alerts` defines independent destinations such as Telegram, SMS, email, webhooks, ntfy and Gotify.
- `ui` contains separate Status, History and Settings screens. User-adjustable behavior belongs in grouped Settings sections.

The app remains one Gradle module for a fast, lightweight build. Package contracts allow later extraction into separate Gradle modules without coupling the state machine to Android or any provider.

## Build

Open this existing directory in Android Studio and use its bundled JDK. From PowerShell, with JAVA_HOME pointing at that JDK:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Permissions are limited to foreground service operation, notification display, restart after boot and read-only network-state detection for Diagnostics. No internet permission is currently declared, no user information is transmitted, and power loss indicates charger disconnection rather than independently verified mains failure.

A standalone outage-rule engine is connected through a coordinator that persists every observation and transition. In-process deadlines are backed by an idle-aware AlarmManager wake-up. Android can delay this inexact alarm under Doze; exact-alarm special access is deliberately not requested.

## Visual design

Dark navy surfaces with mint external-power and amber battery indicators. The compact battery gauge is drawn natively in Compose so the charger-test instructions remain visible on the Pixel 4 emulator at default text size. Scrolling remains available for smaller screens and larger accessibility text. A matching vector lightning-bolt launcher icon includes legacy API 23 and adaptive/themed variants. No image or icon library is required.

## Validation

Debug build, seven outage-engine tests and Android lint passed on 10 September 2026. API 36 emulator checks verified the dark dashboard, launcher graphic, grouped Settings UI, completed History UI, live Diagnostics and simulated alert preview. The test-mode preview left the real state and history byte-for-byte unchanged. An end-to-end simulated device event waited for the first AC connection, armed, persisted a pending loss, fired its AlarmManager deadline, confirmed the outage after 10 seconds, confirmed stable restoration after 30 seconds, and stored the completed record with battery levels. A full emulator reboot verified that `LOCKED_BOOT_COMPLETED` restarted the foreground service from device-protected state without opening the app; an in-place APK upgrade verified the same behavior through `MY_PACKAGE_REPLACED`. The notification remained silent, non-vibrating, low priority and ongoing. Android 6.0 and physical-device behavior are not yet verified.

Background guidance follows Android's current [Doze and App Standby guidance](https://developer.android.com/training/monitoring-device-state/doze-standby) and opens the platform battery-optimization settings rather than assuming a manufacturer's changing menu layout.
