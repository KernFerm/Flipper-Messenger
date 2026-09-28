# Android architecture

The companion is Kotlin, minSdk 26, target/compile SDK 36.

- `MainActivity`: actual permission state, scan/device chooser, authorization controls, SIM/default selection, contact sync, privacy/about.
- `ContactPickerActivity`: reads contacts only after permission and returns at most 50 explicitly selected name/number pairs.
- `MessengerService`: started/bound sticky BLE central, GATT lifecycle, live foreground notification and saved-device reconnection while the UI is backgrounded, session authentication, request validation, SMS execution, platform result callbacks, and listener updates.
- `Protocol`: strict binary codec shared with the C implementation and AES-256-GCM authentication.
- `SecureStore`: Android-Keystore wrapping for the link key and local request-ID ledger.
- `PermissionManager`: API-level Bluetooth, contacts, SMS, and notification runtime permission set.
- `SmsReceiver`: system-protected receiver for new SMS only; it forwards to the already-running authorized bridge and never queries stored SMS.

The service reconnects to a previously selected bonded address when the UI binds. It does not pretend a disconnected device is connected. BLE writes are serialized. Android's GATT callbacks never update views directly; listener calls are moved to the main thread by the activity.

SMS multipart sent/delivery `PendingIntent` results are aggregated. One failed part produces `FAILED`; all sent parts produce `SENT`; all delivery callbacks produce `DELIVERED`. The protected incoming-SMS receiver forwards only newly received messages to an already-running, authenticated bridge.

