# FP Grid Monitor

## Purpose

FP Grid Monitor turns a spare Android phone or tablet into a simple grid-power monitor. Keep the device connected to a normal wall charger and the app watches whether Android reports that external power is present. If power disappears long enough to count as an outage, it records the event and can alert you. When stable power returns, it can send a restoration message linked to the same outage.

The app is being developed by [Flossy Pickle](https://flossypickle.com). It has no advertising, analytics, or required cloud account.

> **Development status:** FP Grid Monitor is under active development. Physical testing on a Samsung Galaxy S10 running Android 12 has passed screen-off, removal-from-Recents, reboot, Telegram outage/restoration, and built-in alarm checks. Android 6 compatibility and longer unattended testing still need physical verification. There is not yet a signed public release APK.

## What it can do

- Detect external power loss and stable restoration without constant polling.
- Ignore brief cable movement with configurable outage and restoration delays.
- Continue monitoring with the screen off and resume after a reboot, as far as the device manufacturer allows.
- Keep a local history of outages, brief interruptions, app starts, monitoring starts/stops, and possible unclean shutdowns.
- Send alerts through Telegram, Gmail, Resend, or the device's own SMS service.
- Queue internet alerts while offline and retry them in the correct order when connectivity returns.
- Sound an optional repeating local alarm using a built-in beep or an Android alarm sound.
- Warn once when the monitoring device's battery becomes low during an outage.
- Show diagnostics, setup checks, internet status, and provider failures without exposing credentials.
- Offer System, Dark, and Light themes and Guided or Experienced setup instructions.

## How detection works

FP Grid Monitor treats the charger's connection as evidence of grid power:

1. It waits until the device has seen external power at least once.
2. When Android reports that the charger has disconnected, it starts the outage-confirmation delay.
3. If power returns during that delay, the interruption is recorded but no outage alert is sent.
4. If the delay expires, the outage is confirmed and enabled alerts are queued.
5. When power returns and remains stable for the restoration delay, the outage is completed and restoration alerts are queued.

The app checks whether Android considers an external power source connected. It does not use the battery's *charging/not charging* label as proof of an outage, because many devices stop charging when their battery is full.

## Is it suitable for your setup?

### Advantages

- Reuses an old Android device and its battery as a small built-in backup supply.
- Detects and records locally even when the internet is unavailable.
- Requires no FP Grid Monitor server or subscription.
- Supports both internet alerts and device SMS.
- Uses event-driven monitoring to keep idle CPU and network use low.
- Stores alert credentials using Android Keystore encryption.

### Limitations

- It detects loss of power to the phone's charger, not the electricity grid directly.
- A charger connected through a UPS, power station, backed-up socket, faulty cable, or switched USB port may give a misleading result.
- Telegram and email cannot arrive until the monitoring device regains internet access. SMS needs a working SIM/mobile network and may cost money.
- Some Android manufacturers aggressively stop background apps. Their battery settings can change between phone models and software versions.
- An old or damaged lithium battery should not be left charging unattended. Inspect the device and battery before using it continuously.
- This is not a certified safety, medical, or emergency alarm system.

## Installation

### Public release APK

A signed public APK is not available yet. When the first release is ready, it will appear on the repository's [Releases page](https://github.com/bennymamo/PowerOutageMonitor/releases). Installation will then be:

1. Download the APK on the Android device.
2. If Android asks, allow that browser or file manager to install unknown apps.
3. Open the APK and choose **Install**.
4. Open **FP Grid Monitor** and follow the guided setup.
5. Return to the Releases page for future updates. Install newer APKs over the existing app so settings and history are retained.

Only install APKs published by this repository. Uninstalling the app removes its local settings, credentials, queue, and history.

### Build the current development version

Developers can build a debug APK from source with Android Studio or PowerShell. The project currently uses Kotlin, Jetpack Compose, Android Gradle Plugin, and the Gradle wrapper included in this repository.

Requirements:

- Windows, macOS, or Linux
- Android Studio with an Android SDK that supports API 37
- JDK 25; Android Studio's bundled JDK is suitable

On Windows PowerShell, from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

The debug APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To run the automated checks:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

The minimum supported Android version is Android 6.0 (API 23). The project currently targets API 37.

## First setup

The app starts with a guided wizard. For a reliable installation:

1. Read the battery-safety note and name the monitoring device, such as `Home power monitor`.
2. Leave the phone connected to the wall charger and complete the real connection/disconnection check.
3. Keep the recommended 60-second outage delay and 30-second restoration delay initially.
4. Allow notifications. Android requires the small ongoing notification while background monitoring is active.
5. Open **Setup & testing** and complete the readiness checklist.
6. Open **Alert channels**, configure at least one destination, and send a test.
7. Use the master switch on the Status dashboard to start or stop all monitoring. Turning it off also removes the ongoing notification and stops local alarm activity.

On phones with strict battery management, open **Settings → Reliability → Keep Power Monitor Running** and follow the device and Android checks. Prefer an **Unrestricted** or equivalent battery setting when the phone offers one.

## Alert options

### Telegram

Telegram is the easiest internet-based option for most users and can alert one or more private chats or groups.

1. In Telegram, create a bot with `@BotFather` and copy its bot token.
2. Send `/start` to the new bot from every private chat that should receive alerts. Add it to a group and send a message there if needed.
3. In FP Grid Monitor, open **Settings → Alert channels → Telegram**.
4. Save the token, check it, find chats, select recipients, and send a test.

The bot token is a password. Do not share it or paste it into issue reports.

### Gmail

Gmail is the default email option and does not require a registered domain. Google normally requires two-step verification and a dedicated App Password; a normal Gmail password should not be entered. The app sends directly through Gmail's SMTP service.

### SMS

SMS can work when home internet fails, provided the Android device has telephony support, an active SIM, mobile signal, and permission to send SMS. Your mobile provider may charge for every message. Distribution through Google Play may impose additional SMS-policy restrictions; direct sideloading does not remove Android's runtime permission requirement.

### Resend

Resend is an advanced email option intended for users who already control a verified sending domain and have a Resend API key. Most home users should choose Gmail or Telegram.

### Audible alarm

The local alarm is off by default. It can use the built-in beep or a sound from Android's alarm picker, repeat at a selected interval, temporarily raise alarm volume, and stop at a chosen battery level. The active alarm can be dismissed from the dashboard or notification. Sound tests stop automatically after five seconds.

## Permissions

| Permission or access | Why it is used |
| --- | --- |
| Notifications | Shows the quiet ongoing monitoring status and outage information. |
| Foreground service | Lets monitoring continue while the app screen is closed. |
| Start after boot | Restarts enabled monitoring after a device reboot. |
| Internet and network state | Sends configured internet alerts and shows connectivity status. |
| Send SMS | Used only when the user configures and enables device SMS. |
| Modify audio settings | Temporarily raises and restores alarm volume when that option is enabled. |
| Alarms and reminders | Optional; used only for user-selected exact audible-alarm repeats on supported Android versions. |

FP Grid Monitor does not request contacts, location, camera, microphone, or storage access.

## Privacy and security

- No analytics, advertising, tracking, or FP Grid Monitor backend.
- Power history, operational history, configuration, and pending delivery state remain on the device.
- Telegram, Gmail, Resend, and similar credentials are encrypted using a non-exportable Android Keystore key.
- Credentials and queued messages are excluded from Android backup and device transfer.
- Alert content is sent only to services and recipients the user configures.
- Diagnostics and copied reports omit secret credentials.

Using an alert provider is also subject to that provider's privacy policy and network handling.

## Reliability notes

Android and phone manufacturers ultimately control background execution. FP Grid Monitor uses a foreground service, event-driven Android power signals, persisted deadlines, reboot recovery, and a durable alert queue, but no Android app can promise uninterrupted operation on every device.

Do not use Android's **Force stop** button for routine closing. Force stop deliberately prevents an app from starting itself, removes its alarms and notifications, and stops boot recovery until Android considers the app started again. Open FP Grid Monitor after a force stop and confirm that monitoring is active.

Before relying on it:

- Send a test through every enabled alert channel.
- Unplug and reconnect the real charger once while watching the configured delays.
- Verify operation with the screen off, after removing the app from Recents, and after a reboot.
- Check it again after Android system updates.
- Review History for repeated app starts without matching stops, which can indicate process killing or crashes.

## Support and development status

Use [GitHub Issues](https://github.com/bennymamo/PowerOutageMonitor/issues) for reproducible bugs and feature requests. Remove email addresses, phone numbers, chat identifiers, bot tokens, passwords, and API keys from screenshots and diagnostic text before posting.

Planned work before the first public release includes longer unattended device testing, Android 6 verification, release signing, update documentation, and final physical checks of alarm dismissal, Do Not Disturb, and battery cutoff behavior.
