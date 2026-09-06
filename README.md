# CryptoMesh Android Beta

CryptoMesh is an offline Android application for discovering nearby CryptoMesh
devices and exchanging authenticated, encrypted text messages over Bluetooth
Low Energy, including opportunistic store-carry-forward relay through trusted
mesh peers.

Version: `0.2.0-beta01`

## Beta Scope

Implemented:

- Persistent local identity backed by Room.
- Non-exportable P-256 signing key in Android Keystore.
- BLE advertising, scanning, GATT connections, and chunked transport.
- Signed ephemeral ECDH peer handshake.
- HKDF-SHA256 per-session key derivation.
- AES-256-GCM encrypted text and media chunks.
- Signed secure-packet envelopes with expiry and duplicate protection.
- Encrypted end-to-end acknowledgements.
- Decentralized queued packet relay across authenticated BLE mesh links.
- Offline photo, video, and audio transfer using chunked BLE packets.
- Foreground local notifications for peer, connection, and message activity.
- Live Home, Peers, Chat, Profile, and Permissions screens.

Not included in this beta:

- Internet or backend synchronization.
- Adaptive replication policy beyond opportunistic relay.
- Wallet or payment functionality.
- Wi-Fi Direct transport.
- Background reconnection or a foreground Bluetooth service.
- Durable plaintext conversation history.

No seeded peers, conversations, transactions, diagnostics, simulations, or
Compose preview data are included in the beta application.

## Documentation

- [Beta User Guide](USER_GUIDE.md)
- [Technical Documentation](TECHNICAL_DOCUMENTATION.md)
- [Logical Module Documentation](MODULE_DOCUMENTATION.md)
- [Frontend Phase Plan](../FRONTEND_PHASE_PLAN.md)

## Requirements

- Android 8.0 (API 26) or newer.
- Bluetooth Low Energy.
- BLE advertising support for incoming discovery.
- Two physical Android devices for end-to-end testing.
- Nearby Devices permission on Android 12 or newer.
- Location permission for BLE scanning on Android 8 through Android 11.
- Notification permission on Android 13 or newer.

Internet access and a backend are not required.

## Build

Open this directory in Android Studio:

```text
/Users/hitendrasingh/Desktop/project/cryptomesh-frontend
```

Or build from the terminal:

```bash
./gradlew :app:assembleDebug
```

Generated APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Verification

Run the complete local gate:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The repository includes unit coverage for identity persistence, packet storage,
BLE framing, signed handshake verification, wire encoding, packet
encryption/tamper rejection, media chunk encryption/reassembly, ViewModel
state, paired two-peer encrypted message/ACK exchange, and three-node offline
relay paths for text and media.
