# Power Outage Monitor

An Android power-outage monitor by Flossy Pickle. Package: `com.flossypickle.poweroutagemonitor`.

## Current milestone

The app now has a five-step first-run wizard, Status, History and grouped Settings screens, plus Diagnostics and an isolated Test mode under Settings. The wizard explains detection, battery safety and background operation, verifies a real charger disconnect/reconnect sequence, collects the device name and stable timing defaults, then enables monitoring and requests notification permission. Test mode previews unmistakably simulated outage/restoration messages and can explicitly send them through the real configured delivery path without changing monitoring state or history. A user-controlled master switch starts or stops the foreground monitor, its alarms and its ongoing notification. The service observes Android's external-power state without polling, persists the outage state before first unlock, resumes after reboot or app upgrade, and records completed outages or brief interruptions locally with battery level and temperature when Android supplies them. Confirmed outage and stable-restoration events flow through a provider-independent durable queue to enabled Telegram chats with per-recipient retry and de-duplication.

Android 6.0 (API 23) minimum; compile/target API 37. Kotlin and Jetpack Compose, one application module. API 36 emulator testing is the initial development target; physical old-device testing is required before reliability claims.

## Accepted design

- Wait for the first external-power connection before arming outage detection.
- Judge external power by the plugged source, never charging status alone.
- Keep the pure Kotlin outage engine independent from Android and persist critical state synchronously in device-protected storage.
- Keep a small bounded atomic event-history file; reconsider Room when delivery-attempt queries require relational storage.
- Keep future alert deliveries in an atomic credential-protected queue, separate from the before-unlock power state.
- Use UTC epoch timestamps so state can be reconstructed across process death and reboot.
- Add user-enabled foreground monitoring with a quiet, compact notification.
- Support boot recovery before first unlock using device-protected monitoring state; keep credentials separate.
- Store Telegram bot tokens with a non-exportable Android Keystore key and exclude credentials and destinations from backup and device transfer.
- No cloud backend, analytics or advertising.

Implemented transitions: waiting for connection -> powered -> pending outage -> confirmed outage -> pending restoration -> powered. Early restoration cancels a pending outage. Disconnection during pending restoration continues the same outage. Unknown readings never imply a power loss.

## Architecture

- `OutageEngine` contains deterministic business rules and has no Android dependencies.
- `monitoring` owns Android battery observations, the foreground service, boot recovery, persisted-deadline alarms and coordination.
- `storage` owns device-protected monitor state and bounded atomic event history.
- `diagnostics` collects a credential-free local health report, and `guidance` supplies replaceable device guidance data.
- `integrations.power` defines normalized grid evidence for Android charging, EcoFlow, Huawei, Tesla, Home Assistant, MQTT, REST, WebSocket, SNMP or other future sources.
- `integrations.alerts` defines independent destinations such as Telegram, SMS, email, webhooks, ntfy and Gotify.
- `integrations.alerts.AlertQueueEngine` owns provider-neutral de-duplication, in-flight leases and retry decisions; `storage.AlertQueueStore` persists that queue without exposing it before unlock.
- `integrations.alerts.telegram` owns Telegram's API client, provider adapter and configuration. Adding another destination does not change outage detection.
- `AlertDeliveryCoordinator` keeps non-secret events in device-protected storage when an outage is detected before unlock, then materializes per-recipient queue items when credentials become available.
- `AlertDeliveryWorker` gives each queue item its own network-constrained WorkManager chain so a slow or failing recipient cannot block another destination.
- `ui` contains separate Status, History and Settings screens. User-adjustable behavior belongs in grouped Settings sections.
- History associates provider-neutral delivery totals with each power event. Diagnostics can retry failed items after a configuration fix or clear terminal delivery details while retaining outage history.
- History retention is independently configurable to the newest 50, 100 or 200 power events; a separate two-step action clears power history without changing settings or delivery records.
- `SetupWizardScreen` is shown only on a true fresh install. Existing installs migrate past it, and every choice remains editable in Settings.

The app remains one Gradle module for a fast, lightweight build. Package contracts allow later extraction into separate Gradle modules without coupling the state machine to Android or any provider.

## Build

Open this existing directory in Android Studio and use its bundled JDK. From PowerShell, with JAVA_HOME pointing at that JDK:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Permissions are limited to foreground service operation, notification display, restart after boot, network-state detection and internet access for user-configured alert providers. The app transmits a message only when the user explicitly tests or enables an alert channel. Power loss indicates charger disconnection rather than independently verified mains failure.

A standalone outage-rule engine is connected through a coordinator that persists every observation and transition. In-process deadlines are backed by an idle-aware AlarmManager wake-up. Android can delay this inexact alarm under Doze; exact-alarm special access is deliberately not requested.

While monitoring is enabled, the foreground service listens dynamically for `ACTION_BATTERY_CHANGED`, `ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED`. The explicit connection events trigger a fresh read of Android's sticky battery snapshot; their intentionally sparse payload is never interpreted as a power state. This adds prompt vendor-independent event signals without polling or a manifest receiver that would depend on implicit-broadcast background behavior.

## Visual design

Dark navy surfaces with mint external-power and amber battery indicators. The compact battery gauge is drawn natively in Compose so the charger-test instructions remain visible on the Pixel 4 emulator at default text size. Scrolling remains available for smaller screens and larger accessibility text. A matching vector lightning-bolt launcher icon includes legacy API 23 and adaptive/themed variants. No image or icon library is required.

## Validation

Debug build, 23 unit tests and Android lint passed on 10 September 2026. API 36 emulator checks verified the dark dashboard, launcher graphic, grouped Settings UI, completed History UI, live Diagnostics, simulated alert preview and the separate Telegram setup flow. Test mode showed its explicit provider-send action and correct no-provider guidance while leaving the real state, history and queue unchanged. An end-to-end simulated device event waited for the first AC connection, armed, persisted a pending loss, fired its AlarmManager deadline, confirmed the outage after 10 seconds, confirmed stable restoration after 30 seconds, and stored the completed record with battery levels. A full emulator reboot verified that `LOCKED_BOOT_COMPLETED` restarted the foreground service from device-protected state without opening the app; an in-place APK upgrade verified the same behavior through `MY_PACKAGE_REPLACED`. The notification remained silent, non-vibrating, low priority and ongoing. Android 6.0 and physical-device behavior are not yet verified.

The delivery path was exercised offline with a fake token and recipient. The confirmed outage produced one `PENDING` item while network access was unavailable, network restoration woke its worker, the fake credential became one sanitized permanent failure, and stable power restoration produced its own separate item. The plaintext token did not appear in preferences, the queue or the stored error, and the fake configuration and queue records were removed after the test. A successful live Telegram delivery still requires a real user-owned bot and chat during physical-device validation.

A seeded failed-delivery record verified dashboard failure visibility, live Diagnostics counts, user-triggered retry, asynchronous screen refresh and two-step clearing. Clearing removed only terminal delivery metadata; monitoring remained active and the dashboard warning disappeared.

A clean-data emulator run verified the guided setup flow, scroll behavior, the old-battery warning, default timing summary, Android 13+ notification-permission handoff, persisted completion and automatic service startup. Its live power test records a real connected → disconnected → reconnected sequence from Android without changing outage history or sending alerts; it can be skipped when the charger cannot be handled during setup. The wizard uses the same dark theme and leaves alert-channel setup in its dedicated Settings section.

With the expanded dynamic receiver installed, simulated AC loss moved the persisted engine and dashboard into pending-outage state within two seconds. Reconnection before the 60-second threshold returned to powered state, recorded a brief interruption and left only an `alarm_cancelled` entry in Android's alarm history.

A cold emulator reboot also verified unattended recovery: Android delivered `LOCKED_BOOT_COMPLETED`, recreated the foreground monitoring service and restored its quiet ongoing notification without launching the app screen.

Telegram bot tokens are encrypted with AES-GCM using an Android Keystore key and never displayed after saving. Tokens, chat destinations, queued messages and the pre-unlock alert bridge are excluded from Android backup and device transfer, preventing credentials or stale alerts from being restored onto another phone. The setup screen supports token validation, chat discovery, multiple recipients, test messages and two-step removal. Diagnostics reports queued, retrying and failed deliveries without revealing credentials.

The Status screen reads validated internet availability through Android's event-driven network callbacks and shows it beside the last power reading. It raises a compact reliability warning when Android blocks notifications or reports the app as background restricted; Diagnostics explains the problem and opens the relevant system settings. Android 6 uses a dynamically registered connectivity broadcast only while the app screen is visible because default-network callbacks were added in Android 7.

WorkManager 2.11.2 schedules delivery only when Android reports a connected network and persists scheduled work across app restarts and device reboots. The app keeps provider results in its own queue because retry state belongs to each recipient. Retryable Telegram failures use the queue's bounded backoff; invalid credentials, missing destinations and other permanent configuration errors stop and remain visible in Diagnostics.

Background guidance follows Android's current [Doze and App Standby guidance](https://developer.android.com/training/monitoring-device-state/doze-standby) and opens the platform battery-optimization settings rather than assuming a manufacturer's changing menu layout.

The delivery queue allows one item per event, alert kind, provider and destination. Retryable failures back off through 1 minute, 5 minutes, 15 minutes, 30 minutes, 1 hour, 2 hours, 4 hours and 6 hours, then remain at 6-hour intervals. Permanent provider errors stop. A five-minute in-flight lease lets a delivery recover after process death. Provider-specific handling must still account for the narrow crash window after a remote service accepts a message but before the device records success.
