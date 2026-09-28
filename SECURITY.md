# Security

Report vulnerabilities privately to the repository owner. Do not include real phone numbers, messages, contacts, link keys, or device dumps in public reports.

Controls implemented:

- authenticated BLE characteristics and user-confirmed platform bonding;
- a separate physical authorization window on Flipper;
- a random 256-bit link key wrapped by Android Keystore at rest;
- fresh random session/challenge material and AES-256-GCM authenticated frames;
- strictly increasing per-session sequences and session matching;
- fixed maximum frame, contact, number, message, queue, quick-message, draft, and history sizes;
- explicit length parsing without raw C-struct serialization;
- durable request-ID deduplication before the SMS side effect;
- runtime SMS permission recheck immediately before sending;
- atomic Flipper state writes through temporary/backup promotion;
- zeroing of link-key memory on forget/free where practical;
- no telemetry, cloud endpoint, embedded credential, real test data, or release signing key.

Threat boundary: a Flipper is an open development device and Android handsets are not HSMs. Physical compromise, rooted phones, malicious replacement firmware/APKs, OS-level Bluetooth compromise, and carrier behavior are outside the guarantee. `ACCEPTED` is not delivery. Exactly-once is enforced for received request IDs; a power loss in the unavoidable interval after durable acceptance and before the platform SMS call favors no duplicate over automatic retry.

Native review covered integer/length bounds, queue capacity, allocation results, storage promotion, callback lifetime, profile teardown, replay state, and secrets. Dependency and platform security scans should be rerun for every release.

