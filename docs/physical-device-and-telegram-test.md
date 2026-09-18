# Physical-device and Telegram validation

This is the first mandatory test before Flockle Grid Outage Monitor is treated as reliable. An emulator is useful for development, but it does not reproduce a phone maker's battery controls, real charger electronics, mobile service, speaker or long idle periods.

Choose the level of help you want:

- **Guided path:** follow every numbered step below.
- **Experienced path:** enable USB debugging, confirm the phone with `adb devices -l`, install `app-debug.apk` with `adb -d install -r`, then use the validation checklist near the end.

The first test uses the debug APK. A permanent release-signing key is not needed yet.

## Part 1: connect the phone to the development PC

1. On the phone, open **Settings → About phone**.
2. Find **Build number**. On some phones it is under **Software information**.
3. Tap **Build number** seven times. Enter the phone's PIN if Android asks. Android should say that Developer options are enabled.
4. Return to Settings and open **Developer options**. Common locations are:
   - Android 9 and newer: **Settings → System → Developer options**
   - Android 8: **Settings → System → Developer options**
   - Android 7 and older: **Settings → Developer options**
5. Enable **USB debugging**.
6. Connect the phone to the PC with a USB cable that supports data. Some cheap charging cables do not carry data.
7. Unlock the phone. When **Allow USB debugging?** appears, check the computer fingerprint and tap **Allow**. You may choose **Always allow from this computer** on your own trusted PC.
8. If Windows sees only a charging device, change the phone's USB mode to **File transfer**. Some manufacturers also require their USB driver on Windows.

Verify the connection from PowerShell:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
```

Expected result: a physical-device serial followed by `device`. These results need attention:

- `unauthorized`: unlock the phone and accept its USB-debugging prompt.
- no physical device: try another data cable or USB port, choose File transfer, then use **Tools → Troubleshoot Device Connections** in Android Studio.
- `offline`: disconnect and reconnect the cable, then rescan.

The emulator may appear on another line. The `-d` commands below target the one connected physical device and avoid installing to the emulator by mistake.

## Part 2: install the current test build

Build the verified debug APK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = (Resolve-Path ".gradle-user-home").Path
.\gradlew.bat "-Pkotlin.compiler.execution.strategy=in-process" testDebugUnitTest lintDebug assembleDebug
```

Install it without deleting an older debug installation or its app data:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

Open **Flockle Grid Outage Monitor** from the phone's launcher. Its lightning/grid launcher graphic distinguishes it from the emulator tools.

## Part 3: complete the guided app setup

1. Choose **Guided** when the app asks how much help to show.
2. Give the monitor a location-based name such as `Garage`, `Server cabinet` or `Holiday home`. Avoid personal information if the alert recipients do not need it.
3. While monitoring is not yet active, complete the safe charger test:
   1. Connect the charger.
   2. Wait for **Charger connected**.
   3. Disconnect it and wait for **Charger disconnected**.
   4. Reconnect it and wait for **Charger reconnected**.
4. Keep the recommended 60-second outage and 30-second restoration delays for the first real test.
5. Finish setup and allow notifications when Android asks.
6. On Status, confirm:
   - **Grid power online**
   - the correct AC, USB or wireless power input
   - **Monitoring: Active**
   - the small ongoing monitoring notification is present

## Part 4: create and connect a Telegram bot

Treat the bot token like a password. Enter it only into Flockle Grid Outage Monitor. Do not paste it into an issue, Git commit, screenshot, chat message or diagnostic report.

1. In Flockle Grid Outage Monitor, open **Settings → Alert channels → Configure Telegram**.
2. Tap **Open BotFather**. Confirm Telegram opens the official verified `@BotFather` account.
3. Send `/newbot`.
4. Follow BotFather's prompts:
   - Choose a display name, such as `Garage Grid Monitor`.
   - Choose a unique username ending in `bot`, such as `garage_grid_alert_bot`.
5. Copy the token BotFather returns.
6. Return to Flockle Grid Outage Monitor, paste it into **Bot token**, then tap **Check bot token**.
7. In Telegram, open the newly created bot and tap **Start**, or send `/start`. A bot cannot initiate a private chat until the user starts it.
8. Return to Flockle Grid Outage Monitor and tap **Find chats**.
9. Add the discovered chat. For several recipients, each person must first open the bot and send `/start`; then use **Find chats** again.
10. Turn on **Enable Telegram alerts** and tap **Save configuration**.
11. Tap **Send test message**. Confirm the clearly marked test reaches Telegram.
12. Open **Settings → Setup & testing → Test mode**, simulate a confirmed outage, and tap **Send simulated alert**. This second test exercises the durable provider-neutral queue used by real outages.
13. Return to **Setup checklist**. **A test alert was delivered** should change to Complete after Telegram reports success.

If a token is exposed, open BotFather and revoke it, then save the replacement token in the app.

## Part 5: Android reliability settings

1. Open **Settings → Setup & testing → Setup checklist**.
2. Resolve every unfinished Android check.
3. Open **Recommended reliability review → Open reliability guidance**.
4. Follow **Keep Flockle Grid Outage Monitor Running**:
   - allow notifications;
   - allow background activity;
   - set battery use to Unrestricted or the closest manufacturer equivalent;
   - allow auto-start if the manufacturer provides that option.
5. Return to the app. The checklist updates from Android's current readings.

Manufacturer menus change between Android versions. The app links to stable Android settings and explains what outcome to look for instead of relying on brittle menu names.

## Part 6: real outage validation

Use a normal wall charger for this stage. USB power from the development PC counts as external power and can confuse the test.

Record the time and result for each test:

1. **Screen on**
   - Confirm Status says Grid power online.
   - Unplug the wall charger.
   - The app should show a possible outage immediately.
   - After about 60 seconds, it should show a confirmed outage and send exactly one Telegram outage message.
   - Reconnect the charger.
   - After about 30 stable seconds, it should return to Grid power online and send one restoration message.
2. **Screen off**
   - Lock the phone, wait five minutes, and repeat the unplug/reconnect test without opening the app.
   - Confirm both messages arrive once and have sensible local times and battery values.
3. **App removed from Recents**
   - Swipe the Flockle Grid Outage Monitor screen away from Android Recents. Do not force-stop it in Android Settings.
   - Repeat the outage and restoration test.
4. **Reboot recovery**
   - Reboot while monitoring is enabled.
   - Do not manually open Flockle Grid Outage Monitor after startup.
   - Confirm the ongoing notification returns. Unlocking once may be required by the phone or Android version.
   - Repeat the outage and restoration test.
5. **Long idle**
   - Leave the phone connected, screen off and unused overnight.
   - The next day, verify that monitoring is still active and History does not report an unexplained monitoring interruption.

After reconnecting the phone to the PC, inspect it with:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d shell dumpsys activity services com.flossypickle.poweroutagemonitor
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d logcat -d -v time
```

The full logcat may contain information from other apps. Review it locally and do not publish it without checking its contents. The app's copied Diagnostics report deliberately excludes stored Telegram credentials.

## Result record

Record these details in the test notes:

- phone manufacturer and model;
- Android version and API level;
- wired or wireless charging source;
- notification permission result;
- battery/background settings changed;
- Telegram direct test result;
- durable simulated-alert result;
- screen-on outage/restoration result;
- screen-off outage/restoration result;
- removed-from-Recents result;
- reboot result;
- overnight result;
- duplicate, delayed or missing messages;
- any unexplained app or monitoring interruption in History.

Do not move to a public signed APK until the core outage, restoration, boot and screen-off cases work on at least one real spare device.

## Why release signing waits

Every Android APK is signed. Android Studio automatically uses a temporary development key for debug builds, which is enough for this test. A public sideloaded APK needs a private, long-lived release key. Android uses that identity to decide whether a later APK is an authentic update to the installed app.

If the release key is lost, future self-distributed APKs cannot update the existing installation. Users would need to uninstall it, which normally removes local settings and History. If it is stolen, someone could sign a malicious update that appears to have the same identity. We will therefore create it once, keep it outside Git, store its passwords separately and make an encrypted backup after physical reliability testing succeeds.

Official references:

- [Run apps on a hardware device](https://developer.android.com/studio/run/device)
- [Configure on-device developer options](https://developer.android.com/studio/debug/dev-options)
- [Sign your app](https://developer.android.com/studio/publish/app-signing)
- [Telegram: From BotFather to Hello World](https://core.telegram.org/bots/tutorial)
