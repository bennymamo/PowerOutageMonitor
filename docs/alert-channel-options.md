# Alert channel options

Research checked on 13 September 2026. This note separates Android's technical rules from store-distribution policy so the implementation choice remains explicit.

## Telegram

Telegram is the current first provider. It works without a developer-operated backend, supports multiple chats, and fits the durable network retry queue already in the app. Its limitation is physical: the monitored site can lose internet at the same time as mains power.

## Device SMS

Android still exposes `SmsManager` for direct device-originated SMS. A production provider would need to:

- declare and request the dangerous `SEND_SMS` permission only when the user enables SMS;
- detect `FEATURE_TELEPHONY_MESSAGING` so Wi-Fi tablets and phones without messaging support are reported as unavailable;
- let the user choose an active subscription on dual-SIM devices, because Android warns that background sends using an unresolved default subscription can fail or use the wrong SIM;
- use sent and delivery `PendingIntent` results so the shared queue records a real outcome;
- split long messages and explain possible carrier charges.

These are Android OS requirements. A directly sideloaded APK can request `SEND_SMS` from the user and send after it is granted. Google Play separately classifies SMS access as high risk. Its policy lists physical-safety/emergency alerts as a possible `SEND_SMS` exception, but the app must submit a Permissions Declaration and approval is not guaranteed. See the official [Android runtime-permission guide](https://developer.android.com/training/permissions/requesting), [`SmsManager` reference](https://developer.android.com/reference/android/telephony/SmsManager), and [Google Play SMS policy](https://support.google.com/googleplay/android-developer/answer/10208820).

Recommendation: build device SMS only after choosing whether it belongs in the normal app or in a sideload-only build variant. A separate variant keeps the main artifact free of a high-risk permission and preserves a straightforward future Play Store path.

## Email — Gmail default, Resend advanced

There is no dependable public outgoing-mail service that permits unattended sending without authentication; that would operate as an open relay. A private SMTP relay can deliberately trust a device or network without a username, but it has to be operated and secured by the user and is unsuitable as the default phone setup.

The practical serverless approaches are:

1. Gmail OAuth can send with the narrow `gmail.send` scope, but Google classifies that scope as sensitive and an app offered beyond test users can require OAuth verification. It also covers Gmail rather than arbitrary mail providers.
2. Direct SMTP works with providers that permit password or app-password authentication. The app would need host, port, TLS mode, username, sender and recipients, with the password encrypted by the same Android Keystore layer used for Telegram. Provider support varies. Google says app passwords require 2-Step Verification, are unavailable for some account types, and recommends Sign in with Google when available.
3. A transactional HTTPS email API uses one API key after initial service setup. It avoids SMTP compatibility issues, works through ordinary HTTPS on old Android versions, and maps cleanly to the existing durable alert queue.

See the official [Gmail send guide](https://developers.google.com/workspace/gmail/api/guides/sending), [Gmail scope classification](https://developers.google.com/workspace/gmail/api/auth/scopes), and [Google app-password guidance](https://support.google.com/mail/answer/185833).

The app now provides two independent email adapters:

- **Gmail SMTP is the default.** It uses Google's documented `smtp.gmail.com` endpoint with implicit TLS on port 465, the user's full account address and a 16-character App Password. It needs no purchased domain. Google requires 2-Step Verification for App Passwords, does not expose them for every account type, and recommends OAuth where a normal interactive sign-in can be used. A direct APK using Gmail OAuth would need a Google Cloud project and the sensitive `gmail.send` scope can require public-app verification, so an App Password is the smaller dependable unattended option for this local-first release.
- **Resend HTTPS is advanced.** Its free plan currently includes 3,000 messages each month, limited to 100 per day and three domains. Real delivery to arbitrary recipients requires each user to own a domain and verify its DNS records. Resend's API supports idempotency keys, which narrows the duplicate-send window during retry. The temporary onboarding sender is suitable only for account-owner testing.

Both adapters keep credentials in the Android Keystore-backed secret store, exclude their configuration from backup/device transfer, send to each recipient independently, and return retryable or permanent results to the shared durable queue. A developer-owned Resend key is never shipped in the APK. A future Flossy Pickle relay would keep that key on a server rather than on users' phones.

See Google's official [SMTP configuration](https://support.google.com/a/answer/176600), [App Password guidance](https://support.google.com/mail/answer/185833), [Gmail scope classification](https://developers.google.com/workspace/gmail/api/auth/scopes), plus Resend's official [pricing](https://resend.com/pricing), [send-email API](https://resend.com/docs/api-reference/emails/send-email), [idempotency guide](https://resend.com/docs/dashboard/emails/idempotency-keys) and [verified-domain requirements](https://resend.com/docs/dashboard/domains/introduction).

## Suggested order

1. Use Gmail as the normal personal email setup and Telegram as the simplest bot-based path.
2. Keep Resend available for users who own a verified domain.
3. Add device SMS in a sideload-capable build variant when offline-at-site delivery is the priority.
4. Add a generic HTTPS webhook after those; the existing provider registry and queue already support that shape.

No provider should change outage detection or History. Each provider must expose availability, configuration, destinations, a test action and a delivery result through the existing alert-provider boundary.
