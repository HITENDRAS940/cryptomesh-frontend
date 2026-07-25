# CryptoMesh Frontend

Android frontend for the CryptoMesh project.

This folder contains the frontend implementation in progress.

Implemented so far:

- Kotlin Android project structure.
- Jetpack Compose setup.
- Material 3 theme.
- Bottom navigation app shell.
- Reusable UI components.
- Placeholder screens for Dashboard, Peers, Chat, Wallet, and Sync.
- Identity onboarding flow.
- Local profile screen.
- Runtime permission request screen for nearby device features.
- Simulated nearby peer scanning with idle, scanning, empty, and error states.
- Peer details with trust, encounter history, and privacy-safe resource summaries.
- Connection confirmation, connected, failed, retry, and disconnect states.
- Relay eligibility indicators for direct, eligible, and unavailable peers.

Phase 3 currently uses local sample peers. The ViewModel boundary is ready for a
Bluetooth or nearby Wi-Fi repository when the transport layer is implemented.

## Open In Android Studio

Open this folder:

```text
/Users/hitendrasingh/Desktop/project/cryptomesh-frontend
```

Android Studio should sync the Gradle project and download dependencies if required.

## Next Phase

Phase 4 will add the secure chat conversation list, message thread, composer,
delivery states, retry flow, and packet-delivery details.
