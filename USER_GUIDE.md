# CryptoMesh Beta User Guide

## 1. Purpose

CryptoMesh Beta provides offline text messaging between nearby Android devices.
Communication uses Bluetooth Low Energy and does not require mobile data,
Wi-Fi, Internet access, an account server, or a backend.

This beta is intended for controlled physical-device testing. It includes
opportunistic relay between authenticated mesh peers and supports offline
photo, video, and audio transfer. It does not include payments, cloud
synchronization, or background reconnection.

## 2. Tester Requirements

Each tester needs:

- A physical Android device running Android 8.0 or newer.
- Bluetooth Low Energy support.
- Bluetooth enabled.
- CryptoMesh installed on both devices.
- A different local CryptoMesh identity on each device.

At least one device must support BLE advertising. For symmetric discovery and
incoming connections, both devices should support it.

Android emulators are suitable for UI checks but not for validating a real
two-phone BLE exchange.

## 3. Installation

1. Build or obtain `app-debug.apk`.
2. Install it on both Android devices.
3. Allow installation from the selected source if Android requests it.
4. Open CryptoMesh.

The local debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

An existing beta installation can be upgraded without deleting its local
identity:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Create a Local Identity

On each device:

1. Open CryptoMesh.
2. Select **Create identity**.
3. Enter a recognizable display name.
4. Select **Generate identity**.

CryptoMesh creates:

- A P-256 signing key protected by Android Keystore.
- A public signing key stored with local identity metadata.
- A device ID in the form `CM-XXXXXXXX`.

The private signing key is not shown in the UI and is not stored in Room.

Use a different identity on each phone. A device cannot create a secure session
with itself.

## 5. Grant App Permissions

Permission can be granted from either:

- **Home > Permissions**, or
- **Peers > Scan**.

On Android 12 and newer, Android requests Nearby Devices access for scanning,
advertising, and connecting.

On Android 8 through Android 11, Android requires location permission for BLE
scanning. CryptoMesh does not use BLE scan results to calculate or store a
physical location.

On Android 13 and newer, **Home > Permissions** also requests notification
access. Grant it to receive alerts while CryptoMesh is running for:

- Newly discovered peers.
- Incoming and completed secure connections.
- Connection failures and disconnects.
- Incoming messages and message delivery results.
- Bluetooth availability and scan errors.

Selecting a notification opens the relevant Home, Peers, or Chat tab.

If permission is denied, the Peers screen displays a Bluetooth error. Grant the
permission in Android Settings and retry.

## 6. Discover a Peer

On both devices:

1. Open the **Peers** tab.
2. Keep Bluetooth enabled.
3. Select **Scan** on at least one device.
4. Wait for the other CryptoMesh device to appear.

A discovery result initially shows the device ID advertised over BLE. The
display name and final device identity are trusted only after the cryptographic
handshake completes.

Signal labels are derived from BLE RSSI:

| Label | Meaning |
| --- | --- |
| Excellent | Devices are very close |
| Good | Devices are nearby |
| Fair | Device is in usable range |
| Weak | Device is near the edge of range |

RSSI is approximate and changes with walls, cases, orientation, and radio
interference.

## 7. Connect and Authenticate

1. Select a discovered peer.
2. Select **Connect**.
3. Confirm the connection request.
4. Wait for the status to change through **Connecting** and **Verifying**.
5. Continue when the peer shows **Connected**.

During verification, both devices:

- Exchange signed handshake messages.
- Verify that the device ID matches the signing public key.
- Verify the handshake signature.
- Exchange ephemeral ECDH public keys.
- Derive the same temporary AES-256 session key.

If any identity, signature, timestamp, or key check fails, CryptoMesh rejects the
session and displays a failure.

## 8. Send an Encrypted Message

After the peer has completed authentication:

1. Open the **Chat** tab.
2. Select the authenticated conversation.
3. Enter a text message.
4. Select the send icon.

If the peer is connected, CryptoMesh sends the packet directly. If the peer is
offline but still known from a previous authenticated session, CryptoMesh stores
the sealed packet locally and forwards it through any connected authenticated
mesh peer.

Message status icons mean:

| Status | Meaning |
| --- | --- |
| Clock | Packet is queued or being sent through the mesh |
| Single check | Encrypted packet left this device; waiting for peer ACK |
| Double check | Peer decrypted and acknowledged the packet |
| Error | Delivery failed or the packet expired |

The double-check status is an end-to-end application acknowledgement. It is not
just a Bluetooth write result.

Failed messages expose a **Retry** action. Retry requires the peer to have
completed authentication before.

## 9. Send Photo, Video, Or Audio

After the peer has completed authentication:

1. Open the authenticated conversation.
2. Select the attachment icon.
3. Choose a photo, video, or audio file.
4. Optionally enter a text caption.
5. Select the send icon.

CryptoMesh reads the selected media locally, splits it into small encrypted
chunks, stores transfer metadata on this device, and sends the chunks over BLE.
If the destination is offline, authenticated mesh peers can carry sealed chunks
without decrypting them.

Incoming media transfers appear as transfer cards in Chat. A completed incoming
transfer is reconstructed only after all chunks pass integrity verification.

## 10. Keyboard and Scrolling

When the keyboard opens:

- The conversation header remains visible.
- The composer stays above the keyboard.
- The bottom navigation temporarily hides.
- The message list scrolls to the newest message.

After sending, the conversation remains positioned at the latest message.

## 11. Home Screen

Home reports live application state:

- Number of discovered peers.
- Number of authenticated sessions.
- Messages currently awaiting acknowledgement.
- Messages acknowledged during the current process.
- Bluetooth readiness, scanning, or advertising state.

These values come from the live transport repository. They are not seeded or
simulated.

## 11. Local Profile

Open **Home > Profile** to view:

- Display name.
- Device ID.
- Public-key fingerprint preview.
- Android Keystore protection status.

The private key is never displayed.

## 12. Reset Identity

Use **Profile > Reset identity** only when a complete local reset is intended.

After confirmation, CryptoMesh removes:

- The Android Keystore signing key.
- Local identity metadata.
- Stored encrypted packets.
- Active peer sessions.

The app returns to onboarding. The previous device ID cannot be recovered from
the application after reset.

## 13. Troubleshooting

### Peer does not appear

- Confirm Bluetooth is enabled on both phones.
- Confirm both phones granted CryptoMesh nearby-device access.
- Keep CryptoMesh open on both phones during beta testing.
- Open Peers on both phones.
- Stop and restart the scan.
- Move the phones closer.
- Confirm the phone supports BLE advertising.
- Disable battery restrictions temporarily for controlled testing.

### Bluetooth unavailable

- Enable Bluetooth.
- Grant the requested permission in Android Settings.
- Close and reopen CryptoMesh after changing permission.
- Check whether another app or system condition has exhausted BLE advertising
  resources.

### Connection remains on Connecting

- Disconnect and scan again.
- Keep both apps in the foreground.
- Move the phones closer.
- Toggle Bluetooth off and on.

### Verification fails

- Rescan instead of reconnecting to a stale result.
- Ensure the two devices use different identities.
- Reset the affected identity only if its local key and metadata are known to be
  inconsistent.

### Message remains on one check

- Keep both apps open.
- Confirm the peer still shows Connected.
- Move devices closer.
- Retry after reconnecting.

### Conversation disappears after app restart

This beta stores encrypted packet envelopes but does not restore readable
conversation history after process death. Session keys are intentionally
ephemeral. Durable encrypted conversation history is planned for a later
milestone.

## 14. Beta Test Checklist

Run this checklist on two physical devices:

1. Install the same beta APK on both devices.
2. Create two different identities.
3. Grant permissions.
4. Confirm each device ID remains stable after app restart.
5. Discover device B from device A.
6. Connect and reach Verified/Connected.
7. Confirm the authenticated display name replaces the initial discovery label.
8. Send messages in both directions.
9. Confirm every delivered message reaches double-check ACK status.
10. Send multiple messages with the keyboard open.
11. Confirm the header, composer, and latest message remain visible.
12. Disconnect and confirm the composer still accepts queued text for the
    verified peer.
13. Reconnect or connect through a third authenticated peer and send again.
14. Deny permission and confirm the error is understandable.
15. Turn Bluetooth off and confirm the unavailable state.
16. Reset one identity and confirm onboarding returns.

Record for every issue:

- Phone manufacturer and model.
- Android version.
- CryptoMesh version.
- Sender and receiver role.
- Exact status shown.
- Reproduction steps.
- Screenshot or screen recording.
- Whether Bluetooth was toggled or the app restarted.

## 15. Current Beta Limitations

- No automatic reconnect.
- No foreground Bluetooth service.
- No delivery while the process is killed.
- No durable readable conversation history.
- Stored packets cannot be reopened after the ephemeral session key is gone.
- No QR or out-of-band person verification.
- No group messaging.
- Relay forwarding is opportunistic and does not provide relay anonymity.
- No wallet.
- No backend synchronization.
- Physical two-device BLE behavior varies by Android vendor and must be tested
  on target phones.
