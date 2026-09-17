# FP Grid Monitor

Turn a spare Android phone or tablet into a grid-outage monitor. It watches its charger, confirms a sustained power loss, and sends alerts to the destinations you choose.

## Quick start

1. Download the APK from [GitHub Releases](https://github.com/bennymamo/PowerOutageMonitor/releases) and install it on an Android **6.0 or newer** device.
2. Connect the charger to a socket that **loses power when the grid fails**. A charger behind a UPS cannot detect grid loss by itself.
3. Follow the setup steps. Choose **Guided** for step-by-step help or **Experienced** for expandable setup sections.
4. Open **Settings → Alerts & sound → Alert channels**. Set up Telegram, Gmail or device SMS, send a test, then enable and save the channel. You can enable several together.
5. Check **Settings → Monitoring → Reliability → Open reliability diagnostics**. Allow notifications and follow the phone-specific background-running guidance.
6. Turn on the **Monitoring** switch on Status. Unplug, wait for the confirmation delay, check the alert, then reconnect and check restoration.

**Keep the router, Wi-Fi access point and any network adapters on a UPS or other backup supply.** Internet alerts and optional cloud grid readings need a working network during the outage. Mobile data is another option; SMS needs a working SIM and mobile signal.

EcoFlow is optional. The ordinary charger monitor needs no EcoFlow equipment, account or internet connection to detect power loss.

## What you get

- Charger-based detection, configurable outage/restoration delays and brief-interruption logging.
- A Status dashboard with a master switch, compact battery bar and expandable connection information.
- Telegram, Gmail, device SMS and optional Resend alerts; multiple recipients and enabled channels work together in parallel.
- Optional trusted-chat Telegram remote control, with status, sound acknowledgment, quiet time and monitoring commands.
- An optional dismissible audible alarm, built-in beep or Android alarm tone.
- Grid-event history and app/monitoring start, stop, reboot and unexpected-interruption records.
- Optional low-battery warnings, source-unavailable warnings, heartbeats and long-outage updates.
- Password-encrypted backups and selective restore, including credentials, history and pending delivery state.
- Automatic backups to a chosen folder, configurable retention, and an advanced backup editor.
- Light, dark and system themes; guided and experienced setup modes.
- Optional experimental EcoFlow PowerOcean assistance for compatible, installation-tested equipment.

The minimum Android version is **Android 6.0 (API 23)**. Broad support is deliberate: an unused older phone can stay connected as a dedicated monitor. The app uses event-driven charger signals and lightweight local storage. Older phones may have weaker security and battery condition; use a healthy device and test its background operation.

## Full guide

### Install or update

Open [GitHub Releases](https://github.com/bennymamo/PowerOutageMonitor/releases), download the APK and open it on the phone. If Android asks, allow installation from the browser or file manager you are using. You can revoke that permission afterward.

For updates, install the new APK over the existing app to preserve configuration and history. Do not uninstall first. A backup is still a good precaution. A development/debug build may use a different signing key and cannot be updated by the published APK; export a backup before replacing it.

### First setup and everyday use

The welcome flow introduces charger detection, names the monitor, checks charging behavior, sets timing and covers battery safety. Guided users then get a checklist for alert tests and background readiness. You can return to it through **Settings → Help & app → Setup & testing**.

The Status switch controls monitoring globally. Turning it off stops power monitoring and alarms. Its ongoing notification disappears unless optional Telegram remote control remains enabled. The app waits for a first charger connection before arming ordinary charger detection.

| Status | Meaning |
| --- | --- |
| Power available | The selected monitoring policy currently reports power available. |
| Suspected outage | Power-loss evidence is waiting for the confirmation delay. |
| Confirmed outage | Loss has lasted long enough to trigger configured alerts. |
| Restoring / grid appears back | Return evidence is being confirmed, or the inverter is still reconnecting. |
| Power restored | A recent restoration; shown briefly, then normal status resumes. |
| Unknown | The source cannot currently establish grid state. This is not proof of an outage. |

Tap dashboard source, alert-channel or diagnostic rows to open their settings. **Back returns directly to Status when you entered through a dashboard shortcut.** If you entered through Settings, Back follows the containing settings pages, then Status, then exits.

- **Settings → Monitoring:** outage timing, restoration, battery alerts, scheduled updates and reliability.
- **Settings → Alerts & sound:** destinations and the local alarm.
- **Settings → Data & recovery:** History retention, create backup, automatic backups and restore.
- **Settings → Appearance & device:** theme, device name and setup guidance.
- **Settings → Help & app:** checklist, test mode, diagnostics, safety/privacy and About.

### Choose alert channels

Every enabled channel receives each alert independently. Save its configuration, send a test to every intended recipient, then enable it. Internet messages can wait in the delivery queue while connectivity is unavailable; Diagnostics shows delivery results and retry controls.

<details>
<summary><strong>Telegram — a bot you control</strong></summary>

1. Open **Alert channels → Configure Telegram**. The guided steps link to BotFather.
2. In Telegram, message the official `@BotFather` with `/newbot`. Follow the naming prompts and paste its token into **Bot token**.
3. Tap **Check bot token** and read the result.
4. Open your new bot in every receiving Telegram account and send `/start`. For a group, add the bot and send a message there.
5. Tap **Find chats**, then **Add** for the intended chats. If none appear, send a fresh message and retry. Experienced users can enter chat IDs directly.
6. Save, send a test, confirm receipt, then enable Telegram alerts and save again.

The bot token is a password. Never share it in screenshots or bug reports.

</details>

<details>
<summary><strong>Gmail — default email option, no domain needed</strong></summary>

1. Open **Alert channels → Configure email → Gmail**.
2. Use the in-app links to turn on Google **2-Step Verification** and open **Google App Passwords**.
3. Create an App Password for FP Grid Monitor. Enter the sending Google account email and that App Password, **not your normal Google password**.
4. Enter recipient email addresses, one per line. Save and send a test; check inboxes and spam folders.
5. Enable Gmail alerts and save.

Some accounts do not offer App Passwords. See [Google's App Password help](https://support.google.com/accounts/answer/185833) or use another channel. Changing your main Google password revokes existing App Passwords; replace and test the saved one.

</details>

<details>
<summary><strong>Device SMS — an alternative when internet fails</strong></summary>

Open **Alert channels → Configure device SMS**, allow SMS sending and enter recipients with country codes, one per line. Save, send a test and confirm receipt before enabling SMS.

The device needs SMS-capable telephony, an active SIM and mobile signal. Carrier charges can apply to each message. A Wi-Fi-only tablet cannot send device SMS. Android permission requirements still apply to sideloaded APKs; a future Google Play distribution also has separate SMS-policy requirements.

</details>

<details>
<summary><strong>Resend — advanced email with your own verified domain</strong></summary>

Choose **Resend** from **Configure email** and follow the steps to verify a sending domain, enter your API key, sender and recipients, and send a test. Enable only after testing. Resend is mainly for people who already control a domain; Gmail or Telegram is usually simpler.

</details>

### Optional Telegram remote control

For a monitoring phone you rarely reach, open **Settings → Alerts & sound → Telegram remote control**. It is off by default.

1. Set up Telegram alerts, open a private chat with your bot and send `/start`. Save that private chat in Telegram setup.
2. Follow **Next**, select the private chat allowed to control the phone, and enable remote control. Groups can receive alerts but cannot control monitoring.
3. Keep **Long polling** selected, or choose a 2–60 second polling interval. Set your default quiet time and optional EcoFlow check warnings.
4. **Save**, then **Install menu**. Open your bot and send `/status`; the bot's command menu lists the available actions.

| Command | What it does |
| --- | --- |
| `/status` | Grid, monitoring, charger, battery, alarm and EcoFlow check/readings information. |
| `/check_ecoflow` | Start a configured EcoFlow check now; use `/status` shortly afterward for its result. |
| `/stop_sound` | Acknowledge the current audible alarm and stop its repeats. |
| `/quiet` or `/quiet 30` | Skip automatic Telegram alerts for the default time or 30 minutes. |
| `/unquiet` | Resume automatic Telegram alerts. Quiet-period messages are not replayed. |
| `/monitor_on`, `/monitor_off` | Enable/disable power monitoring. Remote control remains available. |
| `/charger_on`, `/charger_off` | Enable charger watching, or use ready EcoFlow monitoring alone. |
| `/ecoflow_on`, `/ecoflow_off` | Resume configured EcoFlow, or pause it while charger watching continues. |
| `/help` | List commands and their effects. |

Monitoring and remote control share **one small ongoing notification**, showing **Monitoring active** or **Monitoring inactive**. Disable both to remove it. Monitoring off stops power checks and alarms. Quiet affects automatic Telegram alerts only: command replies, other alert channels and sound continue. Skipped deliveries are recorded in delivery history.

Long polling returns when a command arrives, with an idle request lasting up to 25 seconds. No webhook server or router port forwarding is needed. Unlock the phone once after a reboot so protected credentials become available. To find a new Telegram chat later, disable remote control and save before sending a fresh `/start` and using Find chats; then allow that chat and re-enable control. Use **one command receiver per bot**; another receiver or webhook can conflict. Android can delay background networking, so check Diagnostics and battery restrictions. Commands require internet even when other alerts use SMS.

Only explicitly trusted private senders can act. Forwarded/edited messages and stale commands cannot control the phone. Queued commands are discarded after initial enable or restore, and handled commands are not replayed. Protect the bot token and your Telegram account. Remote controls cannot change credentials, backups or inverter electrical settings.

Outage/restoration alerts include the charger state and available grid/meter observations with their receipt times. A charger-based fallback is identified when EcoFlow could not verify grid loss. Trusted Telegram chats also receive short command hints.

EcoFlow check warnings notify enabled alert channels once when a completed check fails or lacks current grid evidence, and when a later completed check recovers. Intentional time between checks does not trigger this warning.

### Audible alarm and scheduled messages

Open **Settings → Alerts & sound → Audible alarm**. Choose the built-in beep or **Choose Android alarm sound**, then **Play 5-second test**. Available custom sounds depend on the phone's picker. Expand **Repeats and timing** or **Battery and volume** to adjust repeats, optional exact timing, loudness and battery cutoff.

To silence an active outage, expand the speaker notification **Outage alarm active** and tap **Stop sound**, or dismiss it on Status. This also stops repeats for that outage. Monitoring and message alerts continue; a future outage can sound again. Power return or switching monitoring off also stops sound.

Under **Settings → Monitoring → Scheduled updates**, expand each independent option:

- **Source unavailable:** warning after a configurable delay; describes lost monitoring evidence, not a confirmed outage.
- **Monitor heartbeat:** periodic confirmation that monitoring and message delivery are working.
- **Long outage updates:** repeated status during a confirmed outage; default interval six hours.

Intervals can be set in minutes, hours or days. These messages use all enabled channels, including chargeable SMS.

### History and reliability

History has **All**, **Grid** and **App & monitor** filters. Grid entries show loss/return times, duration and alert results; **Details** opens battery and temperature information. App/monitoring entries record normal starts and stops, updates, reboot recovery and starts without a matching stop. Unexpected gaps remain visibly marked even with details closed; they can indicate a crash or Android stopping the app.

Use Diagnostics to check the foreground service, notification permission, battery restrictions, delivery queue and backup status. Follow the manufacturer-specific guidance there. Removing an app from Recents and Android's **Force stop** are different: after force stop, open the app again and confirm monitoring is active.

Before relying on the monitor, test charger loss/restoration with the screen off, after removing it from Recents, and after rebooting. Repeat after important Android updates. No Android app can guarantee uninterrupted operation on every phone.

### Optional PowerOcean assistance

Use this only if you have compatible EcoFlow equipment and want grid evidence even while backup power keeps the home running. Open **Settings → Power sources → Set up PowerOcean account**. Other users can leave this module unused.

1. Follow the guided steps. Enter your normal EcoFlow account email/password, inverter serial, actual model and account region. No developer keys or installer access are needed for this connection.
2. **Save account**, then **Connect and read device**. A successful account read alone does not verify outage detection.
3. In **Verify and monitor**, verify grid loss and return on your installation using the **Live-feed test**. For an installation you already successfully tested, use **I already tested this installation** and its confirmation instead of repeating a grid cut.
4. Enable **Charger-first assistance**, review schedules, then choose **Use charger + EcoFlow assistance**. The Status master switch still controls monitoring.

Background account monitoring currently requires the tested **Single Phase** profile: grid code `0` connected, `1` off-grid, and meter 1 behavior verified against utility loss. Codes and meter behavior can differ by installation. Other models can expose read-only data without being supported outage sources.

With automatic charger-first assistance enabled, unplugging triggers an immediate EcoFlow check before outage confirmation. Current grid-connected evidence cancels the suspected outage. If the check fails or ends without usable evidence, ordinary charger confirmation takes over. A stalled check cannot hold detection indefinitely: the limit is the configured listening time plus up to one minute for connection setup. Paused assistance or manual-only outage checks use the charger directly.

In charger-first mode, charger loss can alert independently if EcoFlow is unreachable. Verified EcoFlow loss can also trigger an outage when a backed-up charger stays on. A powered charger cannot veto an EcoFlow-detected outage. Grid return can be recognized from validated changing meter activity while the inverter reconnects, even if the charger remains off.

**How scheduled checks work:**

- Normally, open a connection **once an hour**; during an outage, default to **once a minute**. Both schedules are configurable, including manual-only.
- Send one reading request and activate live reporting, then collect the first power report and **two extra reports**. End early if values change and usable grid/meter evidence is available; otherwise listen for up to **two minutes**. Renew temporary live reporting every 20 seconds only while that check is collecting data.
- **Close the connection** after the check. Reuse saved login/broker access on later checks rather than log in each time. Checks never overlap; elapsed schedule slots are skipped.
- Use **Check now** on Status for a manual check. **Pause EcoFlow** closes/suspends EcoFlow checks while charger monitoring continues.

Expand **Check duration & updates** to change the listening limit and extra-report count. Short intervals can leave little time disconnected. Status shows last/next check; expand **EcoFlow readings** for device receipt time, update counts, data health, grid code and meter explanations.

Changing device power values support apparent freshness even when the grid code stays unchanged. Fresh nonzero device meter reports can also confirm an unchanged connected grid code while home loads stay steady. A received reply alone does not prove a new transition. Identical power readings across three checks can mean a steady load or a stalled feed: the dashboard warns, an optional warning goes through enabled channels, and **Stuck-reading safeguards** can temporarily ignore that evidence until it changes.

This is an **experimental, unofficial account interface**. It reads data and requests temporary reporting; it does not change charging, reserve or output settings. EcoFlow has not confirmed usage limits or approved this access. Low activity cannot guarantee account acceptance. The app cannot guarantee a manufacturer measurement timestamp or that every value is freshly measured.

<details>
<summary>Advanced alternatives</summary>

**Other EcoFlow connections** contains a local Modbus TCP module and an approved Developer API preview. Local monitoring requires enabled Modbus, compatible equipment and a successful read-only connection test. Developer API device permission varies; its preview does not drive outage alerts. These alternatives are not required for the account setup above.

The source and alert interfaces are designed for future modules. Huawei, Tesla, UPS devices, energy meters and home-automation connections are possibilities, **not currently available integrations**.

</details>

### Backup and restore

Open **Settings → Data & recovery**. A `.fpgrid` backup can include settings, alert channels and credentials, power sources, history, live monitoring state and pending deliveries. You select sections separately for backup and restore. The creating app version is recorded. Alert-channel backups include trusted Telegram control settings and quiet choices; pending remote commands are never transferred.

**Create backup:** choose included data, enter/confirm a password of at least ten characters, then choose where to save. Use **Show/Hide** to check each password field. Password keyboards are requested with suggestions disabled and, on Android 8 or newer, no personalized learning; the keyboard ultimately decides whether to honor these requests. Keep the password in your password manager and a copy of the archive away from the phone.

**Automatic backups:** choose a destination folder, expand **Frequency & copies**, **Included data** and **Backup password**, enable backups and **Save automatic backup plan**. Run **Create an automatic backup now** and verify its result. A cloud app can sync the chosen folder if it supports Android folder access; FP Grid Monitor does not need your cloud login. If cloud folders are unavailable, use a local folder and your own sync/copy arrangement.

**Restore:** turn monitoring off, open **Restore backup**, enter the password and **Choose backup to unlock**. On a new installation, use **Restore an existing backup** on the welcome screen. When **Ready to restore** appears, review version/data, select sections and **Restore selected data**. Include app settings to recover completed setup. Only choose **Resume monitoring after restore** if the old device is offline, to avoid duplicate pending alerts.

Remote control remains off after restore unless you choose to resume monitoring; otherwise enable it again after the old receiver is offline. After restore, reconnect Android permissions, backup-folder access and custom sound access; these device grants cannot transfer. Review Diagnostics and send provider tests before relying on the replacement device. History receives a **Backup restored** marker.

The archive uses password-strengthening and authenticated **AES-256-GCM** encryption. It is not a password-protected ZIP and cannot be opened by an unzip tool. Forgotten passwords cannot be recovered. The advanced backup editor lets you view/edit the unlocked structured document and save a separately encrypted, validated copy; it can expose credentials, so keep that screen private.

## Common problems

| Problem | Try this |
| --- | --- |
| Waiting for first connection | Connect the watched charger once to arm charger detection. |
| Backup keeps the charger powered | Use a socket that loses grid power, or an installation-tested optional grid source. |
| Telegram finds no chats | Send a fresh `/start` to your bot, then tap Find chats. Check the saved token and internet. |
| Telegram commands do not arrive | Check trusted private chat, saved/enabled remote control and its Receiver status; use one receiver per bot. |
| Messages missing | Check saved/enabled channels and recipients, send their tests and inspect Diagnostics. |
| Notification hidden | Allow FP Grid Monitor notifications in Android settings. |
| Monitoring restarts unexpectedly | Check History and Diagnostics, then follow battery/background guidance. |
| No alarm sound | Play its test; check alarm volume, Do Not Disturb and custom-tone access. |
| EcoFlow values not changing | Check the backed-up network, inspect data health, and review stuck-reading safeguards. |
| EcoFlow check time not advancing | Check master switch, pause/manual-only settings and the next check time; use Check now. |
| Backup fails | Check password, storage/folder access and the last automatic-backup result. |

## Privacy, permissions and limits

No analytics, advertising, tracking or FP Grid Monitor backend. History and configuration stay on the phone. Credentials are encrypted at rest with Android Keystore and are excluded from Android automatic backup; selected credentials transfer through password-encrypted `.fpgrid` files. Alerts go only to your configured services and recipients. Diagnostics omit secret credentials.

The app uses notifications, a foreground service, reboot startup and network access for their stated features. Optional SMS needs SMS permission; exact alarm repeats can need Alarms & reminders access. File/folder pickers grant only selected backup access. Optional local network monitoring may require Android's local-network permission. It does not request contacts, location, camera, microphone or broad storage access.

Charger detection cannot distinguish a grid outage from an unplugged cable, failed charger or switched-off socket. A powered UPS can hide an outage. Internet providers need connectivity; SMS needs mobile service. Android/manufacturers control background execution. Test your actual setup, maintain recovery copies and use a healthy phone battery.

## License, support and source

FP Grid Monitor is free software under [GPLv3](LICENSE). Distributed modified versions must provide corresponding source under GPLv3. Flossy Pickle branding does not imply endorsement of unofficial forks. Third-party attributions are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Report reproducible bugs through [GitHub Issues](https://github.com/bennymamo/PowerOutageMonitor/issues). Remove emails, serials, phone numbers, chat IDs, passwords, tokens and API keys from screenshots before posting.

Developers: open this project in Android Studio with SDK API 37 and its bundled JDK 25, or run the included Gradle wrapper:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`. Minimum API 23; target API 37. Release builds require your own signing material.

Future work may add richer optional telemetry dashboards and graphs. Telemetry-history backup would be a separate opt-in section, off by default, to avoid large routine backups.

<!-- Five quiet cycles, one field inspection, seven night sparks. Hold the first note only after the others. -->
