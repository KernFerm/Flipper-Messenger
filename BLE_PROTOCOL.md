# BLE protocol v1

Transport is the official Flipper BLE serial service. The service UUID is `8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000`; Android writes RX `19ed82ae-ed21-4c9d-4145-228e62fe0000` and subscribes to indications on TX `19ed82ae-ed21-4c9d-4145-228e61fe0000`. Android requests MTU 517; frames never exceed the firmware's 486-byte bound.

All integer fields are little-endian. The 40-byte header is:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | magic `FMSG` |
| 4 | 1 | version `1` |
| 5 | 1 | message type |
| 6 | 1 | flags (`1` = encrypted) |
| 7 | 1 | reserved, zero |
| 8 | 2 | plaintext payload length |
| 10 | 2 | reserved, zero |
| 12 | 8 | unique request ID |
| 20 | 8 | per-session sequence |
| 28 | 12 | session ID |

Clear setup frames end with their payload. Authenticated frames end with AES-GCM ciphertext and a 16-byte tag. AES-256-GCM uses a 12-byte nonce of the first four session bytes followed by the sequence. The complete header is additional authenticated data. Receivers reject wrong magic/version/length, unknown sessions, invalid tags, and sequences not strictly greater than the last accepted sequence.

Operations: `HELLO(1)`, `HELLO_REPLY(2)`, `PROVISION_KEY(4)`, `AUTH_RESPONSE(6)`, `PING(7)`, `PONG(8)`, `DEVICE_INFO(9)`, `CONTACT_SYNC_BEGIN(16)`, `CONTACT_ENTRY(17)`, `CONTACT_SYNC_END(18)`, `CONTACT_SYNC_RESULT(19)`, `SEND_SMS(32)`, `REQUEST_ACCEPTED(33)`, `SMS_SENT(34)`, `SMS_DELIVERED(35)`, `SMS_FAILED(36)`, `INCOMING_SMS(48)`, `ERROR(127)`.

`HELLO_REPLY` contains keyed-state byte, 16-byte session material, then a 16-byte challenge. Provisioning is accepted only during the physical 60-second Flipper authorization window. `CONTACT_SYNC_BEGIN` contains the expected entry count. `CONTACT_ENTRY` is name length, phone length, UTF-8 name, phone. The Flipper replaces its list transactionally only when the expected count arrives and the new list is durably written; `CONTACT_SYNC_RESULT` returns the saved count. `SEND_SMS` is phone length, 16-bit body length, UTF-8 phone, UTF-8 body. Bounds are 31 name bytes, 23 phone bytes, 320 body bytes, 50 contacts.

`INCOMING_SMS` uses sender length, 16-bit body length, UTF-8 sender, and UTF-8 body with the same 23/320-byte bounds. It is accepted only inside an authenticated session and retained in a bounded ten-entry Flipper inbox.

Android records a request ID as accepted before calling `SmsManager`. A repeated ID returns its recorded state and never invokes `SmsManager` again. `ACCEPTED` means queued for Android processing, `SENT` is an Android sent-result success, and `DELIVERED` is emitted only from the carrier delivery callback.

