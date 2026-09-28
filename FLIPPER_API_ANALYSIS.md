# Flipper API analysis

Verified against official firmware 1.4.3 source revision `8622f1a2b83d8f4918dd5fa3f43de963f6d6f819` and f7 API 87.1.

Selected exported APIs:

- `RECORD_BT`, `bt_profile_start`, `bt_profile_restore_default`, `bt_disconnect`, `bt_set_status_changed_callback`, `bt_forget_bonded_devices`, and the per-app key-storage path functions from `applications/services/bt/bt_service/bt.h`.
- `ble_profile_serial`, callback registration, TX, flow-control notification, and RPC-active state from `targets/f7/ble_glue/profiles/serial_profile.h`.
- `furi_hal_bt_start_advertising` and `furi_hal_random_fill_buf/get`.
- `furi_hal_crypto_gcm_encrypt_and_tag` and `furi_hal_crypto_gcm_decrypt_and_verify` for the established AES-GCM primitive.
- exported GUI modules (`ViewDispatcher`, `Submenu`, `TextInput`, `VariableItemList`, `Widget`) and Storage file APIs.

Firmware inspection confirmed the serial RX/TX GATT characteristics require authenticated access, bonding is enabled, the pairing method shows a PIN, maximum serial data is 486 bytes, and TX splits characteristic updates internally. The application follows the official HID profile lifecycle: disconnect, delay, select per-app bond storage, start profile, advertise, remove callback/disconnect/delay, restore default bond path and profile.

The firmware BT service installs its own RPC serial callback during each connection. The application therefore re-registers its bounded callback from the later public BT status notification and marks RPC inactive. Without that lifecycle step, valid app frames would be consumed by the system RPC decoder.

No private symbol or invented API is used. `ufbt` application checking reports target f7/API 87.1.

