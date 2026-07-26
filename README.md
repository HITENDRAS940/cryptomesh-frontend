# CryptoMesh Frontend

Android frontend for the CryptoMesh project.

This folder contains the frontend implementation in progress.

Implemented so far:

- Kotlin Android project structure.
- Jetpack Compose setup.
- Material 3 theme.
- Bottom navigation app shell.
- Reusable UI components.
- Placeholder screen for Sync.
- Identity onboarding flow.
- Local profile screen.
- Runtime permission request screen for nearby device features.
- Simulated nearby peer scanning with idle, scanning, empty, and error states.
- Peer details with trust, encounter history, and privacy-safe resource summaries.
- Connection confirmation, connected, failed, retry, and disconnect states.
- Relay eligibility indicators for direct, eligible, and unavailable peers.
- Secure conversation inbox with verified-session and route indicators.
- Chat threads with outgoing and incoming message bubbles.
- Local message composer with direct and relay delivery simulations.
- Readable delivery, ACK, expiry, failure, and retry states.
- Packet delivery details with replica, hop, relay, expiry, and path summaries.
- Android document picker integration with attachment previews and optional captions.
- File transfer cards with chunk progress, verification, retry, and completion states.
- Direct, relay, unavailable-route, and stricter large-file policy simulations.
- Incoming file request decisions and file transfer policy details.
- Prepaid offline wallet dashboard with available and pending balances.
- Signed payment flow with direct, trusted-relay, and unavailable routes.
- Incoming payment confirmation with signature verification.
- Transaction history with synchronized, pending, rejected, duplicate, expired,
  and signature-failure states.
- Separate receiver-delivery, relay-delivery, and backend-settlement details.
- Sync dashboard with backend readiness and last synchronization timestamps.
- Filterable local queues for messages, files, wallet items, own packets, relay
  packets, ACKs, expiry, and failures.
- Manual backend synchronization simulation with peer-delivery items kept local.
- Replica cleanup through the Sync ViewModel with expired-item reporting.
- Detailed duplicate, delivery, and backend rejection explanations.

Phase 3 currently uses local sample peers. The ViewModel boundary is ready for a
Bluetooth or nearby Wi-Fi repository when the transport layer is implemented.

Phase 4 currently uses local sample conversations and simulated delivery events.
The Chat ViewModel is ready to consume repository-provided encrypted messages,
transport states, and acknowledgements.

Phase 5 uses Android's local document picker and simulates encrypted chunk
forwarding, relay storage, verification, failure, and retry states. File bytes are
not uploaded or transmitted by this frontend-only implementation.

Phase 6 uses local sample balances and transactions. It simulates signing,
receiver delivery, trusted-relay delivery, pending credit, expiry, and retry.
Backend settlement remains a separate displayed state and is not performed by
this frontend-only implementation.

Phase 7 uses a StateFlow-backed local queue simulation. Manual synchronization,
ACK state, relay storage, expiry cleanup, duplicate handling, and backend errors
are represented in the frontend. Room, WorkManager, network connectivity, and
the live backend are not connected yet.

## Open In Android Studio

Open this folder:

```text
/Users/hitendrasingh/Desktop/project/cryptomesh-frontend
```

Android Studio should sync the Gradle project and download dependencies if required.

## Next Phase

Phase 8 will add developer diagnostics, packet inspection, demo controls,
sample-data management, responsive layout checks, and presentation polish.
