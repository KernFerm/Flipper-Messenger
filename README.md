# Flipper Messenger

Flipper Messenger turns a Flipper Zero into a small, smartwatch-style composer for legitimate SMS messages sent by the owner's authorized Android phone. The Flipper has no cellular modem: the included Android companion performs the real SMS operation and returns only actual Android/carrier status.

Current versions: Flipper application **0.0.10** and Android companion **0.0.45**.

## What works

- official-firmware BLE serial transport with PIN bonding;
- a separate on-device, 60-second authorization window;
- AES-256-GCM authenticated sessions, replay rejection, and durable duplicate-request protection;
- up to 50 explicitly selected contacts or manual number entry;
- bounded 320-character composer, quick messages, draft recovery, deliberate final confirmation, and recent outgoing status;
- real Android `SmsManager` single/multipart sending;
- real-time forwarding of newly received SMS to a bounded Flipper inbox, with reply composition;
- distinct Requested, Accepted, Sent, Delivered, and Failed states—no simulated delivery;
- least-privilege Android permissions and local-only data handling.

## Install the Flipper application

The Flipper file is a `.fap` file (not `.fab`). Official Flipper firmware 1.4.3 or newer is required.

### Install with VS Code and uFBT

1. Install Python, VS Code, and the uFBT tooling.
2. Connect the Flipper Zero by USB and close qFlipper so it does not hold the serial port.
3. Open this repository in VS Code.
4. Open **Terminal → New Terminal**, then run:

   ```powershell
   Set-Location .\flipper
   ufbt launch
   ```

5. uFBT builds, copies, and starts `/ext/apps/Tools/flipper_messenger.fap`. If it says the application must be closed manually, exit the old Flipper Messenger instance and run `ufbt launch` again.

### Install by removing the microSD card

1. Turn the Flipper off and remove its microSD card.
2. Insert the card into the computer and open its root directory.
3. Open `apps\Tools`, creating those folders if they do not exist.
4. Copy `flipper_messenger.fap` into `apps\Tools`.
5. Safely eject the card, insert it into the Flipper, and power the Flipper on.
6. Open **Apps → Tools → Flipper Messenger**.

## Install the Android companion

The Android companion is distributed directly as `flipper-messenger-android-0.0.45.apk`; it is not available through Google Play. Android 8.0 or newer is required.

1. Download the APK from this project's GitHub release using the phone, or copy it to the phone by USB.
2. Open the APK from the browser's downloads screen or the Files application.
3. If Android blocks it, open the displayed **Install unknown apps** setting and temporarily allow the browser or Files application that opened the APK.
4. Return to the installer, verify that the application name is **Flipper Messenger**, and select **Install**.
5. Open Flipper Messenger and grant Bluetooth, Send SMS, Receive SMS, and notifications. Contacts is needed only to sync selected contacts. Phone/call and stored-inbox read permissions are not required.
6. After installation, disable **Install unknown apps** again if desired.

Only install APKs downloaded from this project's official release page. Android updates must be signed by the same release key; a differently signed build cannot update the installed application without uninstalling it first.

## First-time setup

1. In Android, choose **Scan for Flipper** and select your device.
2. Confirm the same Bluetooth PIN on both devices.
3. On Flipper, open **Settings → Authorize phone**.
4. Within 60 seconds, press **Authorize this phone** on Android.
5. Wait for both sides to show the phone is authorized/ready.
6. Optionally select and sync contacts. Only checked entries are copied.

Keep the persistent **Flipper Messenger** notification enabled while using the Flipper. Once connected, Android runs the BLE bridge as a connected-device foreground service, so the phone app screen may be closed or left in the background. Android may require battery optimization to be disabled if the phone manufacturer aggressively stops foreground services.

Contact sync is complete only when Android reports **contacts saved on Flipper microSD**. The Flipper rejects incomplete transfers and preserves its previous list if the new list cannot be saved. Synced contacts remain available after the Flipper app is closed and reopened.

The Android bridge reconnects to the saved device when possible. Bluetooth state is real; it will say disconnected when the GATT link is gone.

## Send a message

1. Choose a synced contact or **Manual number** on Flipper.
2. Choose **Compose message** or a Quick Message.
3. Open **Draft** to review the recipient/body and select **SEND NOW**.
4. Watch **Recent outgoing**. Accepted does not mean sent, and Sent does not mean delivered. Delivered appears only when Android receives a carrier delivery callback.

If Contacts is denied, manual numbers continue to work. If SMS is denied or revoked, Android rejects the send. No internet-messaging service (WhatsApp, Signal, etc.) is implemented.

New SMS received while the authorized BLE bridge is active appears under **Inbox / reply**. Select a message to read it, choose **Reply**, compose the response, then use **Review / send** and deliberately confirm. The app does not import or browse previously stored SMS.

## Privacy and removal

There is no cloud, telemetry, inbox access, or call-log access. To remove the relationship, use Android **Forget authorization**, then Flipper **Settings → Forget phone**. Use **Clear private data** on Flipper to erase synced contacts, drafts, authorization key, and outgoing history.

See [BUILDING.md](BUILDING.md), [BLE_PROTOCOL.md](BLE_PROTOCOL.md), [PERMISSIONS.md](PERMISSIONS.md), [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md), and [TESTING.md](TESTING.md).

## License

GNU GPL v3 or later. See [LICENSE](LICENSE). Use this software only with devices, phone service, recipients, and messages you are authorized to use.

