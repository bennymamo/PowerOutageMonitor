# Direct APK releases

The selected first distribution route is a signed APK attached to a GitHub pre-release. This keeps SMS and other sideload-oriented options open and avoids a store account during early physical-device testing. A rehearsal release APK signed with a disposable key is a pipeline test only; it must never be uploaded to GitHub.

## Release prerequisites

1. Use the selected GPLv3 source-code license and the permanent key in the current Windows user's private LocalAppData folder.
2. Save both release passwords in a private Bitwarden Note and attach the `.jks` file to that item. Verify the attachment can be downloaded. Bitwarden attachments are not included in password-protected JSON vault exports, so keep an additional encrypted vault/file backup if you rely on exports.
3. Use `tools/local-release-key.ps1` to load the Windows-protected local passwords only for the signed build. The build script contains no key or password.
4. Use the assigned first preview version `1.0.0-preview.1` (build code `2`), then build and verify its signed APK and SHA-256 checksum. Later updates must increase the build code.
5. On a fresh real device, install that signed APK and a higher-version update signed by the same key. Check that settings, History, monitoring and alerts survive the upgrade.
6. For the current debug-signed Samsung install, first create a password-encrypted `.fpgrid` recovery archive. A release-signed APK cannot replace a debug-signed APK with the same package name; reinstall and restore are required for that one-time migration.
7. Run an unattended physical monitoring window, check History for unexplained starts, and re-test alarm dismissal, charger transitions and provider delivery on the release build.
8. Create a GitHub **pre-release** with a version tag, concise user notes, the signed APK and its SHA-256 checksum. Only then change the README's installation section from “not available yet” to a versioned download link.

The signing key is part of the app's update identity: every later APK installed over the original must use the same key. The permanent key was created after the owner chose Bitwarden Premium for an encrypted file attachment and password storage. The owner has saved the Note and attached the keystore; a download/recovery check is still needed. A permanent-key signed candidate has been built and verified, but the GitHub APK must wait until that recovery check and physical update checks are complete. The current debug-signed APK is never a public release.

## Current Windows signing setup

The working file is `fp-grid-monitor-release.jks` under `%LOCALAPPDATA%\FlossyPickle\FPGridMonitor\signing`. Two 64-character random passwords are stored in separate Windows DPAPI-protected files in that same private folder. This local protection is bound to the current Windows account and is **not** a portable backup; Bitwarden must contain the passwords and a copy of the `.jks` file before publishing. The certificate SHA-256 is saved in `certificate-sha256.txt` there and can be printed safely.

From the repository root, the owner can copy the details for a Bitwarden **Note** without printing the passwords in terminal output:

```powershell
.\tools\local-release-key.ps1 -Action CopyBitwardenNote
```

Paste into a private Bitwarden Note and attach the `.jks` file. The private path printed by the command may be inaccessible from a separate sandbox or browser session. For the initial upload, a checksum-verified temporary copy was placed in the owner's local, non-OneDrive Downloads folder and removed after attachment. Do not copy the key into the OneDrive workspace or GitHub, and do not create a replacement key when a path is inaccessible. After confirming a downloadable Bitwarden attachment, future signed builds on the original Windows account use:

```powershell
.\tools\local-release-key.ps1 -Action Build
```

The script loads passwords only into its process environment, clears them afterward, and invokes `tools/verify-release.ps1` with the permanent certificate fingerprint. Its `Create` action refuses to overwrite an existing key. **Never rerun key creation to replace the first public signing key.**

If the original Windows account or its DPAPI files are unavailable, download the `.jks` attachment from Bitwarden to a local folder outside the Git/OneDrive project. The portable build helper asks for the two passwords from the saved Note in masked PowerShell prompts and verifies the output against the permanent signing certificate:

```powershell
.\tools\build-release-from-keystore.ps1 -KeystorePath `
    (Join-Path $env:USERPROFILE 'Downloads\fp-grid-monitor-release.jks')
```

Keep or remove that downloaded working copy according to the owner's private-key storage plan; the Bitwarden file attachment is the portable recovery copy. The certificate fingerprint is public information, but the keystore and passwords must stay private.

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

Bitwarden references: [encrypted file attachments](https://bitwarden.com/help/attachments/) and [vault exports](https://bitwarden.com/help/export-your-data/).
