# Flipper Messenger Privacy Policy

Effective date: September 28, 2026

Flipper Messenger is provided by KernFerm. It is a local companion application that connects a user-owned Android phone to a user-owned Flipper Zero. It has no developer-operated server, cloud account, advertising, analytics, tracking, or crash telemetry.

## Data the application handles

The Android application handles nearby Bluetooth device information, contact names and phone numbers explicitly selected by the user, SMS recipient numbers and message bodies the user chooses to send, newly received SMS sender numbers and message bodies, Android SMS sent/delivery results, and notification state required for the active connection.

It does not request permission to read stored SMS history, call logs, place or manage phone calls, use the microphone or camera, or access general files. On modern Android it does not use location data.

## How data is used

The data is used only to establish the authorized Bluetooth connection, copy contacts selected by the user to the Flipper, send carrier SMS explicitly confirmed by the user, forward newly received SMS to the authorized Flipper, display real delivery state, and prevent duplicate SMS requests.

## Storage and retention

The Android application stores a Keystore-protected authorization key, the selected Flipper address, the selected SIM preference, and a bounded request-status ledger in application-private storage. Incoming message content and synced contacts are not retained in Android application storage.

Selected contacts, drafts, quick messages, a bounded ten-entry incoming inbox, recent outgoing records, and the Flipper-side authorization key are stored locally in private application storage on the user's Flipper microSD card. The application does not upload this information to KernFerm or any third party.

## Sharing and transfer

The application does not sell, rent, or share personal data with KernFerm, advertisers, data brokers, analytics providers, or other third parties. User-requested SMS content is necessarily provided to Android's SMS service, the user's mobile carrier, and the intended recipient to perform the requested message delivery. Selected contacts and message data are transferred locally between the user's phone and authorized Flipper over an encrypted Bluetooth session.

## User control and deletion

Users can choose which contacts to sync and can use manual number entry without granting Contacts permission. **Forget authorization** removes the Android authorization relationship. On the Flipper, **Forget phone** removes its link key and **Clear private data** removes locally stored contacts, drafts, message history, and authorization data. Uninstalling the Android application removes its application-private data.

## Security

Bluetooth bonding, a physical authorization window, authenticated encryption, replay rejection, bounded storage, and duplicate-request protection are used to protect the local connection. No system can be guaranteed completely secure, and users should protect physical access to both devices.

## Children

Flipper Messenger is not directed to children under 13 and does not knowingly collect children's personal information.

## Changes

Material changes will be published on this page with a revised effective date.

## Contact

For privacy questions or requests, open an issue at <https://github.com/KernFerm/Flipper-Messenger/issues>.

