# Architecture

Flipper Messenger has two deliberately separate trust domains.

The Flipper application composes a recipient and message, stores only bounded user-selected contacts/drafts/history, requires an explicit final confirmation, and sends an authenticated request. It never claims to contain a cellular modem or Android SMS API.

The Kotlin Android companion is the BLE central and SMS executor. Its foreground connection service discovers the official Flipper serial GATT service, authenticates the app session, validates each request, checks `SEND_SMS` immediately before use, persists the request ID before invoking `SmsManager`, and returns measured platform results.

Data flow:

1. Android and Flipper establish an authenticated BLE bond.
2. During a user-opened 60-second authorization window, Android provisions a random 256-bit link key. Android wraps it with Android Keystore; Flipper stores it in private app data.
3. Each connection gets a random session ID and challenge from Flipper.
4. Android proves key possession with an AES-256-GCM authenticated response.
5. Selected contacts and newly received SMS travel Android → Flipper. Confirmed SMS/reply requests travel Flipper → Android. Accepted/sent/delivered/failed states travel back.

The GUI and BLE callback are decoupled by a four-slot bounded receive queue. The BLE callback only copies validated-size input; the GUI tick drains it. No network, server, cloud, analytics, or telemetry exists.

