# Direct APK releases

The selected first distribution route is a signed APK attached to a GitHub Release. This keeps SMS and other sideload-oriented options open and avoids a store account during early physical-device testing.

## Release prerequisites

1. Create one long-lived Android release signing key locally.
2. Store the keystore and passwords outside the repository and make at least one encrypted backup.
3. Add a release signing configuration that reads local or CI secrets without committing them.
4. Build and verify a signed release APK.
5. Install that APK on the oldest physical test phone, then install a higher-version build over it to prove upgrades preserve settings and History.
6. Create a GitHub Release with a version tag, concise notes, the signed APK and its SHA-256 checksum.

The signing key is part of the app's update identity: every later APK installed over the original must use the same key. For that reason, the project does not publish the current debug-signed APK as a public release and does not generate the permanent key without an explicit storage and backup decision.

The first release should follow successful physical-device checks for boot recovery, screen-off monitoring, OEM battery restrictions, real charger events, alarm volume, Android sound playback and live alert delivery.
