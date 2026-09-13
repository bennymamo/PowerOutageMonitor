# Samsung Galaxy S10 physical-device validation

Test date: 13 September 2026  
Device: Samsung Galaxy S10 (SM-G973F / beyond1lteeea)  
Android: 12 (API 31)  
App revision: `32a8f95`  
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

## Still required

- Repeat the real outage/restoration test with the screen off.
- Repeat after removing the app screen from Android Recents without force-stopping it.
- Reboot with monitoring enabled, do not manually open the app, and verify automatic service recovery plus a real outage/restoration pair.
- Apply and record Samsung battery/background settings from Diagnostics.
- Leave the device connected and unused overnight, then inspect History for unexplained app or monitoring interruption markers.
- Test the optional audible alarm on the real speaker, including dismissal, selected Android sound, maximum-volume restoration, battery cutoff and Do Not Disturb behavior.
- Repeat essential compatibility testing on an Android 6/API 23 device if one is available.

This result proves the primary event, persistence, queue and Telegram path on one real Android 12 device. It does not yet establish long-idle or reboot reliability on Samsung firmware.
