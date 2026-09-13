# FP Grid Monitor

An Android power-outage monitor by Flossy Pickle. Package: `com.flossypickle.poweroutagemonitor`.

## Current milestone

The app now has a five-step first-run wizard, Status, History and grouped Settings screens, plus Diagnostics and an isolated Test mode under Settings. The wizard explains detection, battery safety and background operation, verifies a real charger disconnect/reconnect sequence, collects the device name and stable timing defaults, then enables monitoring and requests notification permission. Test mode previews unmistakably simulated outage/restoration messages and can explicitly send them through the real configured delivery path without changing monitoring state or history. A user-controlled master switch starts or stops the foreground monitor, its alarms and its ongoing notification. The service observes Android's external-power state without polling, persists the outage state before first unlock, resumes after reboot or app upgrade, and records completed outages or brief interruptions locally with battery level and temperature when Android supplies them. History also records app and monitoring starts/stops, and flags a later start when the previous session never recorded a clean stop. Confirmed outage and stable-restoration events flow through a provider-independent durable queue to enabled Telegram chats with per-recipient retry and de-duplication.

The Status dashboard is centered on inferred grid state rather than battery level. A compact hero uses distinct symbols and language for online power, possible outage, confirmed outage, restoration checking, recently restored, paused, waiting and unknown states. Battery is a supporting horizontal bar. The dashboard also holds the monitoring master switch, internet and alert readiness, and the latest completed power event. Full event and delivery details remain in History.

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
- Publish the first installable builds as directly downloadable APKs through GitHub Releases.

Implemented transitions: waiting for connection -> powered -> pending outage -> confirmed outage -> pending restoration -> powered. Early restoration cancels a pending outage. Disconnection during pending restoration continues the same outage. Unknown readings never imply a power loss.

## Architecture

- `OutageEngine` contains deterministic business rules and has no Android dependencies.
- `monitoring` owns Android battery observations, the foreground service, boot recovery, persisted-deadline alarms and coordination.
- `audible` owns the optional local alarm's pure rules, device-protected state, sound playback and repeat scheduling.
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
- History merges grid events with a separate operational log. App and service sessions persist active markers, so a new start without a matching close/stop is highlighted as a possible crash, process kill or manufacturer restriction.
- Diagnostics counts those unrecorded interruptions, shows the latest time, reports the local alarm state, and includes the same credential-free facts in its copied report.
- History retention is independently configurable to the newest 50, 100 or 200 power events; a separate two-step action clears power history without changing settings or delivery records.
- History completion is idempotent by outage identity, so a process restart between persistence steps cannot create duplicate event rows. Immediate and delayed restorations follow the same completion path.
- Outage and restoration delays provide common one-tap presets plus a validated custom value from 0 seconds to 24 hours.
- Settings opens as a clean category list; device, timing, restoration, appearance, reliability, history, safety, about, testing and alert controls each have a focused subpage.
- Appearance supports System, Dark and Light themes. System is the default and follows the device setting.
- Audible alarm settings are isolated in their own category. The alarm is off by default, starts only for confirmed outages, repeats at a chosen interval, can use the built-in beep or an Android alarm sound, can temporarily use maximum alarm volume, stops at a chosen battery level, and can be dismissed from the dashboard or monitoring notification. Repeat scheduling can be Best effort or Exact; Exact falls back safely until Android grants Alarms & reminders access.
- `SetupWizardScreen` is shown only on a true fresh install. Existing installs migrate past it, and every choice remains editable in Settings.

The app remains one Gradle module for a fast, lightweight build. Package contracts allow later extraction into separate Gradle modules without coupling the state machine to Android or any provider.

## Build

Open this existing directory in Android Studio and use its bundled JDK. From PowerShell, with JAVA_HOME pointing at that JDK:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Permissions are limited to foreground service operation, notification display, restart after boot, network-state detection, alarm-volume adjustment, optional exact alarm scheduling and internet access for user-configured alert providers. The app transmits a message only when the user explicitly tests or enables an alert channel. Power loss indicates charger disconnection rather than independently verified mains failure.

A standalone outage-rule engine is connected through a coordinator that persists every observation and transition. In-process deadlines are backed by an idle-aware AlarmManager wake-up. The core outage-confirmation deadline remains best effort. Audible repeats can use best-effort scheduling or user-selected exact scheduling; on Android 12 and newer the settings page explains and opens the required Alarms & reminders access screen.

While monitoring is enabled, the foreground service listens dynamically for `ACTION_BATTERY_CHANGED`, `ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED`. The explicit connection events trigger a fresh read of Android's sticky battery snapshot; their intentionally sparse payload is never interpreted as a power state. This adds prompt vendor-independent event signals without polling or a manifest receiver that would depend on implicit-broadcast background behavior.

## Visual design

The dark theme uses navy surfaces with mint online-power and amber caution indicators; the light theme uses warm neutral surfaces and a deep green accent. The compact dashboard gives grid state the strongest visual weight and uses a horizontal battery bar for supporting device health. Scrolling remains available for smaller screens and larger accessibility text. A matching vector lightning-bolt launcher icon includes legacy API 23 and adaptive/themed variants. No image or icon library is required.

## Validation

Debug build, 39 unit tests and Android lint passed on 12 September 2026. API 36 emulator checks verified the grid-first light and dark dashboards, launcher graphic, FP Grid Monitor app label, grouped Settings UI, System/Dark/Light selection, Android Back behavior, combined grid/operational History UI, live Diagnostics, simulated alert preview and the separate Telegram setup flow. The Audible alarm page was checked at phone width, Android's installed-sound picker opened correctly, and selecting Exact timing showed its best-effort fallback plus the Alarms & reminders shortcut while access was unavailable. Test mode showed its explicit provider-send action and correct no-provider guidance while leaving the real state, history and queue unchanged. An end-to-end simulated device event waited for the first AC connection, armed, persisted a pending loss, fired its AlarmManager deadline, confirmed the outage after 10 seconds, confirmed stable restoration after 30 seconds, and stored the completed record with battery levels. A full emulator reboot verified that `LOCKED_BOOT_COMPLETED` restarted the foreground service from device-protected state without opening the app; an in-place APK upgrade verified the same behavior through `MY_PACKAGE_REPLACED`. The monitoring notification remained silent, non-vibrating, low priority and ongoing. Android 6.0 and physical-device behavior are not yet verified.

An audible-alarm emulator run temporarily enabled immediate outage confirmation. AC loss produced a confirmed outage, played one alarm tone, scheduled the next repeat, and exposed dismissal on both the dashboard and ongoing notification. Dismissal removed both controls and canceled the repeat; AC power, the 60-second delay and the default-off alarm state were restored. A separate test beep temporarily raised the alarm stream and returned it to its original volume. Do Not Disturb behavior and real-speaker loudness still require physical-device checks.

Operational History was verified with a normal dashboard monitoring stop/start and a hard process kill. The normal pair produced explicit stopped and started records. The forced kill produced highlighted app and monitoring interruption records at the next launch because neither previous session had recorded a clean end.

The delivery path was exercised offline with a fake token and recipient. The confirmed outage produced one `PENDING` item while network access was unavailable, network restoration woke its worker, the fake credential became one sanitized permanent failure, and stable power restoration produced its own separate item. The plaintext token did not appear in preferences, the queue or the stored error, and the fake configuration and queue records were removed after the test. A successful live Telegram delivery still requires a real user-owned bot and chat during physical-device validation.

A seeded failed-delivery record verified dashboard failure visibility, live Diagnostics counts, user-triggered retry, asynchronous screen refresh and two-step clearing. Clearing removed only terminal delivery metadata; monitoring remained active and the dashboard warning disappeared.

A clean-data emulator run verified the guided setup flow, scroll behavior, the old-battery warning, default timing summary, Android 13+ notification-permission handoff, persisted completion and automatic service startup. Its live power test records a real connected → disconnected → reconnected sequence from Android without changing outage history or sending alerts; it can be skipped when the charger cannot be handled during setup. The wizard uses the same dark theme and leaves alert-channel setup in its dedicated Settings section.

With the expanded dynamic receiver installed, simulated AC loss moved the persisted engine and dashboard into pending-outage state within two seconds. Reconnection before the 60-second threshold returned to powered state, recorded a brief interruption and left only an `alarm_cancelled` entry in Android's alarm history.

A separate zero-delay emulator regression moved directly from powered to confirmed outage and back to powered, then stored one confirmed-outage History record with its original confirmation timestamp. Repeating the powered signal did not create another record. Unit tests also cover replaying the same completion after a process interruption.

A cold emulator reboot also verified unattended recovery: Android delivered `LOCKED_BOOT_COMPLETED`, recreated the foreground monitoring service and restored its quiet ongoing notification without launching the app screen.

Telegram bot tokens are encrypted with AES-GCM using an Android Keystore key and never displayed after saving. Tokens, chat destinations, queued messages and the pre-unlock alert bridge are excluded from Android backup and device transfer, preventing credentials or stale alerts from being restored onto another phone. The setup screen supports token validation, chat discovery, multiple recipients, test messages and two-step removal. Diagnostics reports queued, retrying and failed deliveries without revealing credentials.

The Status screen reads validated internet availability through Android's event-driven network callbacks and shows it beside the last power reading. It raises a compact reliability warning when Android blocks notifications or reports the app as background restricted; Diagnostics explains the problem and opens the relevant system settings. Android 6 uses a dynamically registered connectivity broadcast only while the app screen is visible because default-network callbacks were added in Android 7.

WorkManager 2.11.2 schedules delivery only when Android reports a connected network and persists scheduled work across app restarts and device reboots. The app keeps provider results in its own queue because retry state belongs to each recipient. Retryable Telegram failures use the queue's bounded backoff; invalid credentials, missing destinations and other permanent configuration errors stop and remain visible in Diagnostics.

Diagnostics includes a **Keep Power Monitor Running** section that identifies the device maker, explains the stable checks to make, and opens both this app's system page and Android's battery-optimization list. It deliberately avoids brittle manufacturer menu paths that change between software versions. Background guidance follows Android's current [Doze and App Standby guidance](https://developer.android.com/training/monitoring-device-state/doze-standby).

The delivery queue allows one item per event, alert kind, provider and destination. Retryable failures back off through 1 minute, 5 minutes, 15 minutes, 30 minutes, 1 hour, 2 hours, 4 hours and 6 hours, then remain at 6-hour intervals. Permanent provider errors stop. A five-minute in-flight lease lets a delivery recover after process death. Provider-specific handling must still account for the narrow crash window after a remote service accepts a message but before the device records success.

An alert captured before the first unlock is retained if a provider is still marked enabled but its credential-protected destination is temporarily unavailable. Saving a repaired Telegram configuration immediately materializes that retained event into the durable delivery queue.

Current Android, Google Play, SMS and unattended-email trade-offs are documented in [Alert channel options](docs/alert-channel-options.md). Email is the selected next channel; a Resend HTTPS adapter is recommended because it needs no SMTP library or interactive login after its one-time account, API-key and domain setup.

Initial distribution is through GitHub Releases. The signing and release checklist is documented in [Direct APK releases](docs/direct-apk-releases.md); a public artifact is intentionally deferred until the long-lived release signing key is created and backed up.
