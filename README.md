# FP Grid Monitor

## Quick start

Turn a spare Android phone into a power-outage monitor:

1. **[Download and install the APK](https://github.com/bennymamo/PowerOutageMonitor/releases/download/1.0.0-preview.1/fp-grid-monitor-1.0.0-preview.1.apk)** on a device running **Android 6.0 or newer**. Allow installation from your browser or file manager if Android asks.
2. Connect a healthy phone and reliable charger to the wall socket you want to monitor. **That charger must lose power during an outage**, so keep it outside a UPS or other backup supply.
3. Open FP Grid Monitor and choose **Guided** setup. Follow the charger check and initially keep the recommended **1-minute outage** and **30-second restoration** delays. Already have a recovery archive? Choose **Restore an existing backup** instead.
4. Open **Settings → Setup & testing**, complete the checklist, allow notifications and review the battery/background checks in Diagnostics.
5. Open **Settings → Alert channels**, configure your chosen destinations and send a test. Telegram is a straightforward first option; Gmail is the default email option. Add a local sound under **Settings → Audible alarm** if wanted.
6. Turn on the **Monitoring** master switch on the **Status dashboard**. Unplug the charger, wait for confirmation and check your alerts. Reconnect, wait for stable restoration and check the recovery message.

**Keep your modem/router, Wi-Fi access points and HomePlug/network equipment on UPS or battery backup for internet alerts and EcoFlow readings.** Working mobile data can provide another route for internet alerts. Whole-house backup can hide an outage from the charger detector; [read the EcoFlow limitations](#ecoflow-powerocean-detection) before choosing that optional source.

For step-by-step help, see the [full user guide](#full-user-guide), including [Telegram](#telegram), [Gmail](#gmail), [audible alarms](#set-up-the-audible-alarm), [backup and restore](#backup-and-restore), and [troubleshooting](#troubleshooting).

## Purpose

FP Grid Monitor turns a spare Android phone or tablet into a simple grid-power monitor. It can watch Android's external-power signal from a normal wall charger or, for supported battery-backup homes, use an optional EcoFlow PowerOcean module. If the selected source reports that grid power has disappeared long enough to count as an outage, the app records the event and can alert you. When stable power returns, it can send a restoration message linked to the same outage.

The app is being developed by [Flossy Pickle](https://flossypickle.com). It has no advertising, analytics, or required cloud account.

## Android devices

The current minimum is **Android 6.0 (API 23)**. A spare older phone can therefore act as a dedicated monitor instead of sitting unused. The basic charger-based detector uses Android's power-connection events, and the app keeps its outage decisions and alert queue on the device. A small ongoing notification supports monitoring while the screen is off. Android 6 emulator testing has passed setup, monitoring, and power transitions.

An old Android version does not guarantee that a particular phone will keep the app running: manufacturer battery rules, a worn battery, unreliable Wi-Fi, or a charger on the wrong circuit can still cause missed alerts. Before using a spare phone as a monitor, check its battery condition and test an unplug/reconnect cycle, screen-off operation, reboot recovery, and each enabled alert destination on that actual device.

> **Development status:** FP Grid Monitor is under active development. Physical testing on a Samsung Galaxy S10 running Android 12 has passed screen-off, removal-from-Recents, reboot, Telegram outage/restoration, and built-in alarm checks. Its foreground service also stayed active without a new History interruption during a more-than-12-hour check with no APK installs. Android 6 emulator testing has passed setup, foreground monitoring, and power-transition checks. The first signed preview APK is available below. Longer unattended and old-device physical testing continue; test reliability on your actual device before depending on alerts.

## What it can do

- Detect external power loss and stable restoration without constant polling.
- Optionally read grid-connected/islanded state directly from a local EcoFlow PowerOcean inverter.
- Preview a read-only EcoFlow Cloud connection without installer access or local port 502.
- Ignore brief cable movement with configurable outage and restoration delays.
- Continue monitoring with the screen off and resume after a reboot, as far as the device manufacturer allows.
- Keep a local history of outages, brief interruptions, app starts, monitoring starts/stops, expected update/reboot resumes, and possible unclean shutdowns.
- Send alerts through Telegram, Gmail, Resend, or the device's own SMS service.
- Queue internet alerts while offline and retry them in the correct order when connectivity returns.
- Sound an optional repeating local alarm using a built-in beep or an Android alarm sound, with a speaker notification and **Stop sound** action for the current outage.
- Warn once when the monitoring device's battery becomes low during an outage.
- Warn when the selected power source stops giving trustworthy readings, with a configurable delay.
- Send configurable monitor heartbeats and repeated updates during long outages.
- Show diagnostics, setup checks, internet status, and provider failures without exposing credentials.
- Offer System, Dark, and Light themes and Guided or Experienced setup instructions.
- Create and restore selective, password-encrypted recovery archives, including credentials and history when selected.
- Schedule encrypted recovery copies to a user-chosen local folder or a folder exposed by a cloud-storage app.

## How detection works

FP Grid Monitor treats the charger's connection as evidence of grid power:

1. It waits until the device has seen external power at least once.
2. When Android reports that the charger has disconnected, it starts the outage-confirmation delay.
3. If power returns during that delay, the interruption is recorded but no outage alert is sent.
4. If the delay expires, the outage is confirmed and enabled alerts are queued.
5. When power returns and remains stable for the restoration delay, the outage is completed and restoration alerts are queued.

The app checks whether Android considers an external power source connected. It does not use the battery's *charging/not charging* label as proof of an outage, because many devices stop charging when their battery is full.

### Keep the network powered during an outage

**We strongly recommend a UPS or similar battery backup for the modem/router, every Wi-Fi access point the monitoring phone uses, and any HomePlug/powerline adapters or network switches along the connection.** Otherwise grid loss can cut the network at the same moment the app needs to send an alert or read the inverter.

Telegram and email need internet access; working mobile data on the phone can provide an alternative. Local EcoFlow readings need a working home network. EcoFlow Cloud also depends on the inverter retaining internet access, so mobile data on the phone alone is not enough. Backup power does not guarantee that your internet provider or EcoFlow's cloud stays available. Offline internet alerts are queued and retried when connectivity returns. Device SMS instead needs a working SIM and mobile network; charger detection, local history and the audible alarm work offline.

**Keep the monitored charger outside the backup supply when using Android charger detection.** It must lose power with the socket or circuit you want to monitor. If whole-house backup keeps that charger powered, use a compatible source that reads actual grid state, such as the optional EcoFlow local module.

### EcoFlow PowerOcean detection

Homes with whole-house battery backup need a different source because the phone charger can remain powered during a grid outage. FP Grid Monitor includes an optional, read-only local PowerOcean connection under **Settings → Power sources**.

The connection reads the inverter's grid operating mode, grid-side voltage, and frequency over Modbus TCP on the home network. It checks them together: contradictory, malformed, timed-out, or stale values become **Unknown** and cannot confirm an outage. No EcoFlow cloud login is stored, and the app contains no Modbus command that changes inverter settings.

Requirements:

1. An EcoFlow installer or partner must enable Modbus TCP on the inverter; it is normally disabled.
2. The inverter needs a stable private IPv4 address, preferably reserved in the router.
3. The usual connection is TCP port `502`, unit `1`.
4. The phone and network equipment must stay powered during an outage. Back up the router, Wi-Fi access points, and any HomePlug/powerline adapters or switches with a UPS or battery supply.
5. On Android 17 and newer, allow Local network access when the EcoFlow setup page asks for it.
6. Run the app's read-only connection test, activate EcoFlow as the grid source, then perform a controlled real grid-loss and restoration test before relying on alerts.

Router names are not reliable device identities. PowerOcean communication hardware may appear as an `ESP`, `lwIP`, `wlan`, or unnamed client, and a HomePlug bridge can place it in either a Wi-Fi or wired list. Expand candidate entries to obtain their private IPv4 addresses and use **Test read-only connection** in the app. A timeout or closed TCP port `502` usually means Modbus is disabled, although a different subnet or Wi-Fi client isolation can produce the same result. Failed tests remain **Unknown** and cannot confirm an outage.

EcoFlow does not publicly document this local register interface. Support for standard PowerOcean hardware and the register map come from the community-maintained [EF-PowerOcean-TcpModbus project](https://github.com/MaxGrmm/EF-PowerOcean-TcpModbus). Inverter firmware could change the behavior, so FP Grid Monitor fails closed to **Unknown** rather than guessing. EcoFlow mode checks every five seconds and keeps the device CPU and Wi-Fi awake, so it uses more energy than charger-based monitoring.

### EcoFlow Cloud preview

**Settings → Power sources → EcoFlow Cloud** provides a separate, optional setup page for homes where local Modbus is unavailable. It uses EcoFlow's documented Developer API and the user's own API credentials; it never asks for the normal EcoFlow account password. Credentials are encrypted with Android Keystore and excluded from Android's automatic device backup. A user-created, password-encrypted recovery archive can include them when **Power sources** is selected.

The guided page opens the [EcoFlow Developer Platform](https://developer-eu.ecoflow.com/), explains its developer review (EcoFlow says this can take up to five working days), then explains how to create an application. Once approved, it securely saves the application's Access Key and Secret Key, finds owned EcoFlow devices, and inspects documented PowerOcean phase voltage, grid flow, home load, solar power, and battery readings. It sends read-only `GET` requests and contains no cloud command that changes inverter settings.

Cloud monitoring remains a preview rather than a selectable outage source. Some PowerOcean cloud values have been reported to stop refreshing when the EcoFlow app and web portal are closed. A successful request is therefore not yet sufficient proof that its data is current. Activation will require a controlled grid-loss and restoration test that proves the selected system's phase-voltage updates remain fresh unattended. Until then, uncertain cloud data cannot enter the outage state machine.

## Is it suitable for your setup?

### Advantages

- Reuses an old Android device and its battery as a small built-in backup supply.
- Detects and records locally even when the internet is unavailable.
- Requires no FP Grid Monitor server or subscription.
- Supports both internet alerts and device SMS.
- Charger monitoring is event-driven, keeping idle CPU and network use low.
- Stores alert credentials using Android Keystore encryption.

### Limitations

- The default charger source detects loss of power to the phone, not the electricity grid directly.
- A charger connected through a UPS, power station, backed-up socket, faulty cable, or switched USB port may give a misleading result.
- EcoFlow monitoring depends on a community-discovered local interface, compatible inverter firmware, and working backed-up network equipment.
- Telegram and email cannot arrive until the monitoring device regains internet access. SMS needs a working SIM/mobile network and may cost money.
- Some Android manufacturers aggressively stop background apps. Their battery settings can change between phone models and software versions.
- An old or damaged lithium battery should not be left charging unattended. Inspect the device and battery before using it continuously.
- This is not a certified safety, medical, or emergency alarm system.

## Full user guide

### Install the app

#### Download and install

**[Download FP Grid Monitor 1.0.0-preview.1](https://github.com/bennymamo/PowerOutageMonitor/releases/download/1.0.0-preview.1/fp-grid-monitor-1.0.0-preview.1.apk)** — Android 6.0 or newer. This is a preview release; read the [release notes](https://github.com/bennymamo/PowerOutageMonitor/releases/tag/1.0.0-preview.1) and [SHA-256 checksum](https://github.com/bennymamo/PowerOutageMonitor/releases/download/1.0.0-preview.1/fp-grid-monitor-1.0.0-preview.1.apk.sha256). Installation:

1. Download the APK on the Android device.
2. If Android asks, allow that browser or file manager to install unknown apps.
3. Open the APK and choose **Install**.
4. Open **FP Grid Monitor** and follow the guided setup.
5. Return to the Releases page for future updates. Install newer APKs over the existing app so settings and history are retained.

Only install APKs published by this repository. Uninstalling the app removes its local settings, credentials, queue, and history.

The APK is the Android installation file. If your browser downloads it without opening it, find it in the phone's **Downloads** app or file manager and tap it. Android's wording varies by manufacturer; its [installation guidance](https://support.google.com/pixelphone/answer/7391672) explains allowing the selected browser/file manager to install an app.

If an update reports a signing conflict, you may have an earlier development/debug build. Create an all-category encrypted backup and save a copy away from the phone before uninstalling that build. Install the public APK and restore the archive. Public preview updates use the permanent signing key and normally install over the existing public app without uninstalling.

### First setup

The app starts with a setup wizard. Choose **Guided (recommended)** for explanations and links, or **Experienced** for shorter instructions. Change this later under **Settings → Help & guidance**. To bring an existing monitor onto a replacement device, use **Restore an existing backup** on the first setup screen and follow [restore instructions](#restore-on-this-phone-or-a-replacement).

For a reliable new installation:

1. Read the battery-safety note and name the monitoring device, such as `Home power monitor`.
2. Leave the phone connected to the wall charger and complete the real connection/disconnection check.
3. Keep the recommended 60-second outage delay and 30-second restoration delay initially.
4. Allow notifications. Android requires the small ongoing notification while background monitoring is active.
5. Open **Setup & testing** and complete the readiness checklist.
6. Open **Alert channels**, configure at least one destination, and send a test.
7. Use the master switch on the Status dashboard to start or stop all monitoring. Turning it off also removes the ongoing notification and stops local alarm activity.

For the normal wall-charger detector, leave **Android charger** selected under **Settings → Power sources**. Keep the phone connected long enough to establish its first power baseline. A full battery that says *Not charging* can still have external power available; the detector uses the connection signal.

On phones with strict battery management, open **Settings → Reliability → Open reliability diagnostics** and follow the device and Android checks. Prefer an **Unrestricted** or equivalent battery setting when the phone offers one. Allow the quiet ongoing monitoring notification so you can see whether monitoring is active.

Change the outage delay under **Settings → Outage timing** and the restoration delay or restoration messages under **Settings → Restoration**. Longer delays reduce alerts from brief interruptions; they also mean waiting longer before a real outage or restoration is announced.

### Choose alert destinations

#### Telegram

Telegram is the easiest internet-based option for most users and can alert one or more private chats or groups.

1. Open **Settings → Alert channels → Configure Telegram** and tap **Open BotFather**. A Telegram bot is an account that this app uses to send your messages.
2. Send `/newbot` to the verified `@BotFather` account and follow its prompts for the bot's name and username. Copy the token it returns into FP Grid Monitor's **Bot token** field.
3. Tap **Check bot token** and read the result shown on the setup page.
4. Open your newly created bot in Telegram and tap **Start** or send `/start`. Do this from every private account that should receive alerts. For a group, add the bot and send a message in that group.
5. Return to FP Grid Monitor, tap **Find chats**, then **Add** beside each intended chat. If none appear, send a fresh message to your bot and try again. Advanced users can enter chat IDs directly, one per line.
6. Tap **Save configuration**, then **Send test message**. Check that it arrives in each intended chat; saving alone does not send a message.
7. Turn on **Enable Telegram alerts** and save the configuration. Return to the Status dashboard and confirm that monitoring is on.

The bot token is a password. Do not share it or paste it into issue reports.

#### Gmail

Gmail is the default email option and does not require a registered domain. Google normally requires two-step verification and a dedicated App Password; a normal Gmail password should not be entered. The app sends directly through Gmail's SMTP service.

1. Open **Settings → Alert channels → Configure email** and choose **Gmail**.
2. Sign in to the sending Google account in your browser and turn on **2-Step Verification** if needed.
3. Tap **Open Google App Passwords** in the app. Create an App Password named `FP Grid Monitor`; this is a separate password for the monitor, not your regular Google password.
4. Enter the full **Google account email**, the generated App Password and the recipient **Email addresses**, one per line.
5. Tap **Save configuration**, then **Send test email**. Check the inbox and spam folder of every intended recipient.
6. Turn on **Enable Gmail alerts** and save the configuration.

Some Google accounts do not offer App Passwords. Follow [Google's App Password help](https://support.google.com/accounts/answer/185833) or choose another alert destination. If you change the main Google password, Google revokes existing App Passwords; create and save a replacement, then test again.

#### SMS

SMS can work when home internet fails, provided the Android device has telephony support, an active SIM, mobile signal, and permission to send SMS. Your mobile provider may charge for every message. Distribution through Google Play may impose additional SMS-policy restrictions; direct sideloading does not remove Android's runtime permission requirement.

Open **Settings → Alert channels → Configure device SMS**, allow SMS sending when asked and enter recipients one per line with their country codes, such as `+356…`. Save the configuration, send a test and confirm receipt before enabling SMS alerts and saving again. A Wi-Fi-only tablet cannot send device SMS just because a messaging app is installed.

#### Resend

Resend is an advanced email option intended for users who already control a verified sending domain and have a Resend API key. Most home users should choose Gmail or Telegram.

Choose Resend from **Configure email** and follow its guided instructions to verify a domain, supply the API key and enter the sender and recipients. Save and send a test before enabling the provider. Each destination is configured separately; enable only the ones you want to use.

### Set up the audible alarm

The local alarm is off by default. It can use the built-in beep or a sound from Android's alarm picker, repeat at a selected interval, temporarily raise alarm volume, and stop at a chosen battery level. The active alarm can be dismissed from the dashboard or notification. Sound tests stop automatically after five seconds.

1. Open **Settings → Audible alarm** and choose the built-in beep or tap **Choose Android alarm sound**. Available tones and custom-file choices depend on the phone's sound picker.
2. Tap **Play 5-second test** and confirm that you hear it. Review the phone's alarm volume and Do Not Disturb settings if it is silent.
3. Select the repeat interval, repeat timing, battery cutoff and optional maximum alarm volume. If you choose exact repeats, follow the **Allow exact alarms** instruction when shown; ordinary repeats can be delayed by Android.
4. Turn on **Enable audible outage alarm** and test a real charger disconnection after the confirmation delay.
5. To silence an active outage, expand the speaker notification titled **Outage alarm active** and tap **Stop sound**, or dismiss the alarm from the dashboard. This silences the current outage and its repeats. Monitoring and message alerts continue; a future outage can sound again.

### Configure reminders and warnings

Under **Settings → Scheduled updates**, source-unavailable alerts default to a five-minute delay, monitor heartbeats default to once per day, and long-outage updates default to every six hours. Each can be disabled or changed independently using minutes, hours, or days. These messages use every enabled alert channel, so normal SMS charges may apply. A source-unavailable message means the app cannot determine grid state; it is deliberately separate from a confirmed outage alert.

### Backup and restore

Open **Settings → Data & backup** and choose **Create encrypted backup**, **Automatic backups**, or **Restore backup**. On a replacement phone, tap **Restore an existing backup** on the first setup screen instead of repeating the five-step setup. A `.fpgrid` archive can include app settings, alert channels and keys, power sources, history, and live state with pending deliveries. It also records the app version that created it.

Every archive is encrypted in full with a password of at least ten characters. FP Grid Monitor uses a password-strengthening step followed by authenticated AES-256-GCM encryption, so the contents are unreadable and changes or corruption are detected. The file is not a ZIP and cannot be opened with an unzip tool. FP Grid Monitor cannot recover a forgotten password.

#### Create a recovery copy

1. Open **Settings → Data & backup → Create backup**.
2. Choose the sections to include. For recovery after a dead phone, select everything: settings, alert channels/keys, power sources, History and live state/pending deliveries.
3. Enter and confirm a strong password of at least ten characters. Save it in your password manager so it survives loss of the phone.
4. Create the encrypted backup and use Android's file picker to save the `.fpgrid` file.
5. Keep a copy away from the monitoring phone, such as on your computer or in your own cloud storage. A backup stored only on a dead phone will not help.

#### Restore on this phone or a replacement

For a replacement phone, enter the archive password, choose the file in Android's picker, review its version and available sections, then tap **Restore selected data**. Include **App settings** to recover completed setup. A successful restore adds a **Backup restored** marker to History, separating imported records from activity on the new device. Monitoring must be off during restore. Restored pending alerts stay paused unless **Resume monitoring after restore** is explicitly selected; this avoids duplicate alerts while the old device may still be active.

1. On an existing installation, turn off the dashboard's Monitoring switch and open **Settings → Data & backup → Restore backup**. On a fresh installation, choose **Restore an existing backup** on the first setup screen.
2. Enter the backup password and tap **Choose backup to unlock**. Select the `.fpgrid` file in Android's file picker.
3. When **Ready to restore** appears, check the archive's creation time, app version and included sections. Select the sections you want; choose everything for a full replacement-device recovery.
4. Decide whether to resume monitoring after restore. Keep it paused until the old device is stopped if both phones might otherwise send the same pending alerts.
5. Tap **Restore selected data** and wait for confirmation. Review History, settings and alert destinations, reconnect Android permissions/folders/sound access, and send fresh provider tests.
6. Connect the replacement phone to its monitored charger and turn on the dashboard switch when ready.

#### Inspect or edit an archive

The advanced backup editor can show the decrypted structured document, including credentials, after the user unlocks it. It creates a separate encrypted copy and validates every edited value before saving. Use it for controlled testing and keep the screen private while secrets are visible.

#### Schedule automatic recovery copies

Automatic backups use Android's folder picker. A user can choose a local folder or, when its Android app supports folder access, a Google Drive, OneDrive, or Dropbox folder. FP Grid Monitor receives access only to that selected folder and never receives the user's cloud login. Frequency, retained-copy count, password, and included sections are configurable. After restoring onto another device, Android requires the user to reconnect the destination folder before scheduling can resume.

Open **Settings → Data & backup → Automatic backups**, choose a destination folder, select the frequency, retained-copy count and included sections, and enter/confirm the backup password. Enable automatic backups, tap **Save automatic backup plan**, then **Create an automatic backup now**. Check **Last successful copy** and verify the file actually reaches your chosen storage. If a cloud app does not offer folder selection, choose a local destination and arrange your own copy/sync; cloud-folder support varies by provider.

Android permissions, manufacturer battery settings, cloud-account sessions, and access grants to folders or custom sound files cannot be transferred. Review Diagnostics and reconnect those items on a replacement device.

### Daily use and a complete first test

The Status dashboard focuses on grid state. A suspected outage is still waiting for its confirmation delay; a confirmed outage has met that delay. A restored/recovered message is shown briefly before returning to the normal power-available state. **Unknown** means the selected source cannot provide trustworthy evidence, rather than proof of an outage. Battery percentage describes how much reserve the monitoring phone has left.

The dashboard master switch controls all monitoring. Use it to pause the monitor for maintenance or moving the charger. Change themes under **Settings → Appearance**, the friendly name under **Settings → Device**, and retention under **Settings → History**. Android Back returns from a settings subsection to Settings, then to Status before exiting.

For the first complete test:

1. With the real charger connected, enable monitoring and send a test through every enabled destination.
2. Unplug, wait for your outage-confirmation delay and verify the confirmed status, History entry, messages and optional sound.
3. If sound is enabled, tap the notification's **Stop sound** while it is playing and check that it stops and does not repeat for that outage.
4. Reconnect and wait for the restoration delay. Check the restoration messages and completed outage in History.
5. Repeat a real unplug/reconnect check with the screen off, after removing the app from Recents, and after a reboot. Keep the network powered and check the quiet ongoing notification.
6. Review History later for unexplained monitoring restarts or missing intervals, and verify a backup can be unlocked before you depend on it.

**Test mode** under **Setup & testing** is useful for learning the state sequence without touching a cable. Follow its explicit send controls if you want simulated messages to reach real destinations. Simulation does not replace physical screen-off, charger and reboot tests.

### Troubleshooting

| What you see | What to check |
| --- | --- |
| Waiting for a first connection | Connect the real charger once with Android charger selected, then confirm that external power is shown. |
| No outage when house backup takes over | The watched charger is still powered. Use a socket that loses power with the grid or validate a compatible direct grid source. |
| Telegram finds no chats | Open your bot, send a fresh `/start` or message, then return and tap **Find chats**. Check the token result and internet access. |
| Email or Telegram messages do not arrive | Confirm that the channel is saved and enabled, send its test, check recipients/provider feedback and review Diagnostics. Offline internet messages wait for connectivity. |
| Monitoring notification is hidden | Allow notifications for FP Grid Monitor in Android settings and review Diagnostics. The app's own sound notification also needs notification permission on newer Android versions. |
| Monitoring stops or History shows unexplained restarts | Review Android battery optimization, manufacturer auto-start/background controls and the phone's memory/battery condition. Open the app again after a force stop. |
| No local sound | Enable the alarm, play its test, review alarm volume/Do Not Disturb and reselect a custom tone if its file access was lost. |
| Backup cannot unlock or restore | Check the password, use the original `.fpgrid` file, wait for **Ready to restore**, and turn monitoring off before applying it. A forgotten password cannot be recovered. |
| Scheduled backup fails | Reconnect the destination folder, check storage/provider availability and inspect the last attempt shown in Automatic backups. |
| EcoFlow readings are Unknown | Check the chosen source's connection test and backed-up network. Local Modbus needs enabled port 502 and compatible firmware; Cloud preview cannot drive outage detection. |

## Permissions

| Permission or access | Why it is used |
| --- | --- |
| Notifications | Shows the quiet ongoing monitoring status and outage information. |
| Foreground service | Lets monitoring continue while the app screen is closed. |
| Start after boot | Restarts enabled monitoring after a device reboot. |
| Internet and network state | Sends configured internet alerts and shows connectivity status. |
| Local network | Android 17 and newer require this runtime permission for the optional direct EcoFlow connection. |
| Wi-Fi state and wake lock | Keeps optional local EcoFlow monitoring responsive while the screen is off. |
| Send SMS | Used only when the user configures and enables device SMS. |
| Modify audio settings | Temporarily raises and restores alarm volume when that option is enabled. |
| Alarms and reminders | Optional; used only for user-selected exact audible-alarm repeats on supported Android versions. |

FP Grid Monitor does not request contacts, location, camera, microphone, or broad storage access. Android's system file and folder pickers grant access only to the backup file or folder the user chooses.

## Privacy and security

- No analytics, advertising, tracking, or FP Grid Monitor backend.
- Power history, operational history, configuration, and pending delivery state remain on the device.
- Telegram, Gmail, Resend, EcoFlow, and automatic-backup credentials are encrypted at rest using a non-exportable Android Keystore key.
- Credentials and queued messages are excluded from Android's automatic device backup. They move only when the user includes them in a password-encrypted `.fpgrid` archive.
- Alert content is sent only to services and recipients the user configures.
- Diagnostics and copied reports omit secret credentials.

Using an alert provider is also subject to that provider's privacy policy and network handling.

## License

Copyright © 2026 Flossy Pickle. FP Grid Monitor is free software under the [GNU General Public License, version 3](LICENSE). You may use and modify it; if you distribute a modified version, you must also make its corresponding source available under GPLv3. The license allows people to charge for copies or services. The **Flossy Pickle** name and FP Grid Monitor branding identify this project and are not permission to imply an unofficial fork is endorsed.

## Reliability notes

Android and phone manufacturers ultimately control background execution. FP Grid Monitor uses a foreground service, event-driven Android power signals, persisted deadlines, reboot recovery, and a durable alert queue, but no Android app can promise uninterrupted operation on every device.

Do not use Android's **Force stop** button for routine closing. Force stop deliberately prevents an app from starting itself, removes its alarms and notifications, and stops boot recovery until Android considers the app started again. Open FP Grid Monitor after a force stop and confirm that monitoring is active.

Before relying on it:

- Send a test through every enabled alert channel.
- Unplug and reconnect the real charger once while watching the configured delays.
- Verify operation with the screen off, after removing the app from Recents, and after a reboot.
- Check it again after Android system updates.
- Review History for repeated app or monitoring starts without matching stops. On Android 11 and newer, the phone may explain a recent interruption as a crash, low memory, or app update; older phones mark the cause as unknown. Repeated unexplained starts need a closer look.

## Build from source

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

## Support and development status

Use [GitHub Issues](https://github.com/bennymamo/PowerOutageMonitor/issues) for reproducible bugs and feature requests. Remove email addresses, phone numbers, chat identifiers, bot tokens, passwords, and API keys from screenshots and diagnostic text before posting.

The first preview has passed encrypted recovery onto a fresh signed installation, signed updates preserving data, Telegram outage/restoration delivery, custom alarm playback and Stop sound on the physical test phone. Ongoing validation includes longer unattended monitoring, older physical devices, Do Not Disturb and battery cutoff behavior. EcoFlow integrations remain experimental.

The optional EcoFlow source also requires validation against the actual inverter during one controlled grid outage before it should be treated as production-ready.

<!-- Five quiet cycles, one field inspection, seven night sparks. Hold the first note only after the others. -->
