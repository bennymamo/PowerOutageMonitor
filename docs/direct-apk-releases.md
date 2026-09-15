# Direct APK releases

The selected first distribution route is a signed APK attached to a GitHub pre-release. This keeps SMS and other sideload-oriented options open and avoids a store account during early physical-device testing. A rehearsal release APK signed with a disposable key is a pipeline test only; it must never be uploaded to GitHub.

## Release prerequisites

1. Choose the source-code license and the permanent release-key storage/backup location.
2. Create one long-lived Android release signing key locally. Store the keystore and both passwords outside the repository and back them up separately.
3. Provide the four `FP_GRID_RELEASE_*` environment variables shown below. The build script contains no key or password.
4. Give the first public build an explicit version name/code, then build and verify its signed APK and SHA-256 checksum.
5. On a fresh real device, install that signed APK and a higher-version update signed by the same key. Check that settings, History, monitoring and alerts survive the upgrade.
6. For the current debug-signed Samsung install, first create a password-encrypted `.fpgrid` recovery archive. A release-signed APK cannot replace a debug-signed APK with the same package name; reinstall and restore are required for that one-time migration.
7. Run an unattended physical monitoring window, check History for unexplained starts, and re-test alarm dismissal, charger transitions and provider delivery on the release build.
8. Create a GitHub **pre-release** with a version tag, concise user notes, the signed APK and its SHA-256 checksum. Only then change the README's installation section from “not available yet” to a versioned download link.

The signing key is part of the app's update identity: every later APK installed over the original must use the same key. For that reason, the project does not publish the current debug-signed APK as a public release and does not generate the permanent key without an explicit storage and backup decision.

## Build configuration already prepared

`app/build.gradle.kts` accepts these environment variables only for release signing:

| Variable | Meaning |
| --- | --- |
| `FP_GRID_RELEASE_STORE_FILE` | Absolute path to the long-lived keystore outside Git. |
| `FP_GRID_RELEASE_STORE_PASSWORD` | Keystore password. |
| `FP_GRID_RELEASE_KEY_ALIAS` | Alias of the app signing key. |
| `FP_GRID_RELEASE_KEY_PASSWORD` | Password of that key. |

Without all four values, `assembleRelease` can produce an **unsigned** build output. Never distribute it. `tools/verify-release.ps1` asks Android's `apksigner` to validate the APK down to API 23, checks its certificate against the permanent fingerprint, refuses the disposable rehearsal signer, and prints the APK's SHA-256 checksum for GitHub. The current disposable rehearsal key lives in ignored `app/build` output and is not the permanent app identity.

When the permanent key is ready, verify a candidate from the repository root with:

```powershell
.\tools\verify-release.ps1 -ApkPath .\app\build\outputs\apk\release\app-release.apk `
    -ExpectedCertificateSha256 'YOUR_PERMANENT_CERTIFICATE_FINGERPRINT'
```

The first public release can ship the reliable Android-charger path, modular Telegram/Gmail/Resend/SMS destinations, local alarms, History, diagnostics and encrypted recovery. EcoFlow Cloud can remain explicitly labeled a read-only preview until Developer API approval and a controlled unattended grid-loss test prove fresh voltage readings. The local EcoFlow Modbus source remains optional and requires port 502 to be enabled and physically tested.

Google's Android developer verification begins for participating stores in four countries on 30 September 2026; direct APK sideloading is unaffected at that date. The global certified-device rollout in 2027 makes registration through the Android Developer Console a later distribution task. Keep the permanent signing key because package ownership may need to be proved.

The first release should follow successful physical-device checks for boot recovery, screen-off monitoring, OEM battery restrictions, real charger events, alarm volume, Android sound playback and live alert delivery.

Official references: [Android release signing](https://developer.android.com/studio/publish/app-signing), [command-line APK verification](https://developer.android.com/build/building-cmdline), [GitHub releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), and [Android developer verification](https://developer.android.com/developer-verification).
