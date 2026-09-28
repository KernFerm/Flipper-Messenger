# Android permissions

| Permission | API/scope | Reason |
|---|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | max API 30 | Legacy BLE connection and discovery |
| `ACCESS_FINE_LOCATION` | max API 30 | Android's legacy BLE scan requirement |
| `BLUETOOTH_SCAN` | API 31+ | Discover the user's nearby Flipper; declared `neverForLocation` |
| `BLUETOOTH_CONNECT` | API 31+ | Bond, connect, discover GATT, and exchange frames |
| `READ_CONTACTS` | runtime, optional feature | Display contacts for explicit selection and sync |
| `SEND_SMS` | runtime | Send a user-confirmed outgoing SMS request |
| `RECEIVE_SMS` | runtime, optional incoming feature | Forward newly received SMS to an actively connected, authorized Flipper |
| `POST_NOTIFICATIONS` | API 33+ | Show the active connected-device foreground-service notice |
| `FOREGROUND_SERVICE` | normal | Keep an active BLE bridge visible/alive |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | recent Android | Correct foreground-service type for BLE |

Phone permission is displayed as **Not Required**. The manifest does not request `READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, or `READ_SMS`.

`SEND_SMS` is sufficient for outgoing operation. `RECEIVE_SMS` is separately used only for the user-requested real-time inbox/reply feature. `READ_SMS` would expose stored inbox/outbox history and remains unnecessary: no historical messages are queried.

Manual number entry and all Flipper composition features work without Contacts permission. Sending is rejected if SMS permission is absent or revoked. The dashboard always reads current permission state and includes an App Settings button for permanent denial. Bluetooth denial prevents scan/connect without fabricated state.

