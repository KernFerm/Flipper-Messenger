# Testing

## Automated and build checks

- `ufbt` clean compile, metadata, FAP, and application compatibility check for f7/API 87.1.
- `gradlew testDebugUnitTest assembleDebug` for the Android codec and APK.
- Protocol tests cover encrypted round trip, clear setup frames, replay rejection, and authenticated-tamper rejection.
- Review the manifest with `aapt dump permissions`; forbidden SMS/call-log/call permissions must be absent.

## Required physical checklist

Record phone model/Android version, Flipper firmware version, app versions, and result. Use a consenting recipient and carrier plan.

1. Launch the FAP repeatedly and verify Back restores the standard BLE profile without a crash.
2. Grant and deny each Bluetooth, Contacts, Send SMS, Receive SMS, and notification prompt. Test “don't ask again,” App Settings recovery, and revocation while connected.
3. Scan, bond, compare/confirm PIN, open Flipper's 60-second authorization window, provision, disconnect, and reconnect.
4. Verify an unprovisioned phone cannot authenticate and malformed/wrong-tag/replayed frames do not change state or send.
5. Select one contact, sync, require the **saved on Flipper microSD** acknowledgment, close/reopen the Flipper app, and verify only that entry remains. Interrupt a second transfer and verify the old list remains. Deny Contacts and verify manual-number composition still works.
6. Test invalid and valid manual numbers, 1/160/161/320-character bodies, keyboard cancellation, quick-message selection/edit, draft recovery, and final confirmation cancellation.
7. Send to a consenting phone. Confirm Flipper displays Requested/Accepted, then Sent only after Android's sent callback, and Delivered only if a carrier delivery callback occurs.
8. From a consenting second phone, send a real SMS to Android. Verify it appears in **Inbox / reply**, the sender/body match, reply opens a blank composer addressed to that sender, and the reply follows normal confirmation/status rules. Revoke Receive SMS and verify no new message is forwarded.
9. Re-submit the exact captured request ID and verify no second SMS is sent.
10. Revoke Send SMS permission immediately before send; verify Android returns failure and no SMS is sent.
11. Put Android on its home screen and use another app while composing/sending/receiving on Flipper. Verify the foreground notification shows live state and the bridge remains functional. Then test airplane mode/no SIM/no service, Bluetooth loss during each phase, phone restart, Flipper restart, rapid presses, and 25 repeated runs.
12. Remove microSD or fill it during a save. Verify the prior valid state remains and no success is claimed for a failed save.
13. Forget/clear on both sides and verify reconnection requires fresh bonding/authorization and stored private data is gone.

Do not mark a hardware item passed from compilation, mocks, or inference. `DELIVERED` may legitimately remain unavailable when a carrier does not supply delivery reports.

