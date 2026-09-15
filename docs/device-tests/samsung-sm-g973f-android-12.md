# Samsung Galaxy S10 physical-device validation

Test date: 13 September 2026  
Device: Samsung Galaxy S10 (SM-G973F / beyond1lteeea)  
Android: 12 (API 31)  
Validation started at app revision: `32a8f95`
Connection: Android Wireless debugging over the local network

No device serial number, network address, Telegram recipient, bot token or other credential is stored in this report.

## Passed

- Wireless debugging paired and connected successfully after restarting a stale Android connection port.
- The debug APK installed successfully on real hardware.
- The five-step fresh-install setup rendered and completed.
- Android reported the real AC charger connected, disconnected and reconnected during guided setup.
- Starting monitoring created a foreground `MonitoringService` and a quiet ongoing notification.
- AC power, charging status, battery percentage and battery temperature were read from the real Samsung battery service.
- Installing a newer debug APK over the app preserved its settings and encrypted Telegram token.
- `MY_PACKAGE_REPLACED` restarted foreground monitoring after that in-place update.
- Telegram bot-token validation succeeded.
- Telegram chat discovery found one private chat after a fresh message to the bot.
- The direct Telegram configuration test arrived.
- The provider-neutral Test-mode outage was persisted, sent through WorkManager and recorded as `SENT` through Telegram with one attempt.
- A real screen-on AC disconnection entered `PENDING_OUTAGE` immediately.
- After the configured 60-second delay, the persisted phase changed to `OUTAGE`.
- The real outage Telegram message was recorded as `SENT` with one attempt and was received by the user.
- AC reconnection completed the configured 30-second restoration check and returned the persisted phase to `POWERED`.
- After restoration was confirmed, the dashboard showed its temporary Power restored state for the intended 60 seconds and then settled to Grid power online.
- The linked Telegram restoration message was recorded as `SENT` with one attempt and was received by the user.
- Local History stored one `confirmed_outage` record with a 151-second total interruption, 100% start/end battery, and 33.3°C/33.2°C battery temperatures.
- The dashboard returned to Grid power online and showed Telegram (1).
- A full outage/restoration pair passed with the screen off. Both Telegram messages arrived once.
- A full outage/restoration pair passed after removing the app from Recents without force-stopping it.
- Reboot recovery passed: monitoring and its notification returned without manually opening the app, and the subsequent outage/restoration alerts arrived once.
- No duplicate or missing alerts were observed across the screen-off, removed-from-Recents and reboot tests.
- Samsung battery usage was set to Unrestricted. Android also listed the package on its device-idle allowlist, so the absence of a red restriction warning was the expected healthy result.
- The built-in audible alarm and the selected Android alarm sound both played on the real speaker.
- Physical testing exposed a playback ownership bug: the selected-sound preview kept playing and the Dismiss action only cancelled future repeats.
- The corrected APK now owns one stoppable playback session. Android audio diagnostics on this device verified the selected sound stops at the five-second preview limit and the app-owned Dismiss action stops active playback immediately.
- The accelerated 10-second test delays were returned to the recommended 60-second outage and 30-second restoration values. Monitoring remained enabled, the persisted phase was `POWERED`, and the device was on AC power.

## 15 September follow-up: monitoring restart evidence

Read-only Android `ApplicationExitInfo` inspection explained the cluster of unrecorded monitoring starts around midnight: those process exits were `USER REQUESTED` with `installPackageLI`, the normal result of repeated in-place development APK installs. One earlier exit was a system-pressure `SIGNALED` stop; the sticky foreground service restarted roughly two seconds later. The recent retained exit records showed no app crash. The current service is foreground and its notification remains silent.

History now records a system-provided package-update or reboot resume separately from an unexplained service restart. A fresh in-place update on this phone produced `monitoring_resumed_after_update`, rather than another red interruption marker, and the foreground service resumed normally. Old records are retained unchanged so their original evidence is not rewritten.

## Still required

- Leave the device connected and unused overnight, then inspect History for unexplained app or monitoring interruption markers.
- Run that overnight window **without installing APKs**. Package replacements were the main source of earlier noisy restart markers; any new unexplained marker during a clean window warrants investigation.
- Confirm the corrected dashboard or notification Dismiss control during one real outage. Its exact receiver action has passed on this device, but the user-facing control has not yet been repeated after installing the fix.
- Test the optional audible alarm's battery cutoff and Do Not Disturb behavior. The built-in sound, Android-selected sound, preview limit and immediate stop path have passed.
- Repeat essential compatibility testing on an Android 6/API 23 device if one is available.

This result proves the primary event, persistence, queue, Telegram, screen-off, removed-from-Recents and reboot-recovery paths on one real Android 12 device. Overnight idle and Android 6 compatibility remain open.
