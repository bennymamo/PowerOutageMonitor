# Alert channel options

Research checked on 10 September 2026. This note separates Android's technical rules from store-distribution policy so the implementation choice remains explicit.

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

## Email

There are two realistic serverless approaches:

1. Gmail OAuth can send with the narrow `gmail.send` scope, but Google classifies that scope as sensitive and an app offered beyond test users can require OAuth verification. It also covers Gmail rather than arbitrary mail providers.
2. Direct SMTP works with providers that permit password or app-password authentication. The app would need host, port, TLS mode, username, sender and recipients, with the password encrypted by the same Android Keystore layer used for Telegram. Provider support varies. Google says app passwords require 2-Step Verification, are unavailable for some account types, and recommends Sign in with Google when available.

See the official [Gmail send guide](https://developers.google.com/workspace/gmail/api/guides/sending), [Gmail scope classification](https://developers.google.com/workspace/gmail/api/auth/scopes), and [Google app-password guidance](https://support.google.com/mail/answer/185833).

Recommendation: if email is next, start with user-supplied SMTP over TLS and label it as an advanced setup. It remains provider-neutral and needs no Flossy Pickle server. Gmail OAuth can be a separate provider later rather than complicating the first email implementation.

## Suggested order

1. Keep Telegram as the default proof of delivery.
2. Add device SMS in a sideload-capable build variant when offline-at-site delivery is the priority.
3. Add SMTP when broad internet-based email compatibility is the priority.
4. Add a generic HTTPS webhook after those; the existing provider registry and queue already support that shape.

No provider should change outage detection or History. Each provider must expose availability, configuration, destinations, a test action and a delivery result through the existing alert-provider boundary.
