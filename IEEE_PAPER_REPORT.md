# CryptoMesh: An Offline Authenticated BLE Mesh for Secure Text and Media Exchange

## Report purpose

This report is structured as source material for an IEEE-style conference or
journal paper. It describes the implemented CryptoMesh Android beta
(`0.2.0-beta01`) and separates repository-verified facts from experiments that
must still be performed before publication. The report should be converted to
the target IEEE template (`IEEEtran`) only after the missing quantitative
results and literature citations have been added.

## Proposed paper metadata

**Proposed title:** CryptoMesh: An Offline Authenticated BLE Mesh for Secure
Text and Media Exchange

**Authors:** *[Insert authors, affiliations, and corresponding author]*

**Paper type:** Systems prototype and experimental evaluation

**Keywords:** Bluetooth Low Energy, offline communication, opportunistic
forwarding, store-carry-forward networking, authenticated key exchange,
Android Keystore, AES-GCM, ECDH, edge messaging, delay-tolerant networks

## Abstract

Communication systems commonly assume access to cellular infrastructure,
Internet connectivity, or a backend service. This paper presents CryptoMesh, an
Android reference implementation for authenticated, encrypted communication
over nearby Bluetooth Low Energy (BLE) devices without Internet or backend
dependencies. Each device creates a local P-256 signing identity protected by
Android Keystore. During peer establishment, devices exchange signed ephemeral
elliptic-curve Diffie-Hellman (ECDH) parameters, verify key-derived device
identifiers, and derive a 256-bit session key using HKDF-SHA-256. Application
messages are represented as signed secure-packet envelopes and encrypted with
AES-256-GCM. Packets are persisted locally and can be relayed opportunistically
through authenticated peers using a store-carry-forward model. The prototype
also supports acknowledgement-driven, chunked transfer of photos, video, audio,
and PDF media over BLE. The Android implementation uses Jetpack Compose,
coroutines, Room, Android Keystore, and a repository-based architecture that
isolates the user interface from transport, cryptographic, and persistence
concerns. Unit tests cover identity persistence, framing, handshake
verification, encryption and tamper rejection, media reassembly, UI state,
two-peer exchange, and three-node relay paths. The current beta demonstrates
functional feasibility; controlled physical-device experiments are required to
quantify latency, throughput, energy consumption, delivery probability, and
scalability.

## 1. Introduction

Many messaging applications depend on an always-available network service.
That assumption fails during infrastructure outages, in remote locations, at
large events, and in environments where devices must exchange information
locally. BLE is widely available on mobile devices and has low power
requirements, but its limited payload size, intermittent connectivity, and
short-range links make reliable secure messaging a systems challenge.

CryptoMesh addresses this challenge with an offline-first Android application
that discovers nearby peers, authenticates them cryptographically, exchanges
encrypted messages, and stores packets for later forwarding. The system
provides direct peer communication and opportunistic multi-hop delivery while
avoiding a central account server, Internet connection, or cloud
synchronization service.

The main contributions of this work are:

1. An Android BLE communication architecture that combines discovery, GATT
   transport, authenticated sessions, persistence, and Compose-based state
   management without a backend.
2. A signed handshake that binds a short device identifier to a long-term
   P-256 signing key while using ephemeral ECDH keys for per-session secrecy.
3. A secure-packet and acknowledgement design supporting duplicate protection,
   expiry, local persistence, opportunistic relay, and end-to-end delivery
   status.
4. A BLE-compatible chunked media-transfer pipeline with encrypted chunks,
   per-chunk integrity checks, reassembly, progress state, and failure
   persistence.
5. A functional prototype and test suite covering cryptographic, transport,
   repository, UI-state, media, and multi-node relay behavior.

The paper should make clear that CryptoMesh is a prototype evaluation, not a
claim of production-grade anonymity, global routing, or formally verified
security.

## 2. Background and related work

The final paper should position CryptoMesh against the following bodies of
work:

- Bluetooth Low Energy application-layer protocols and GATT throughput
  constraints.
- Delay-tolerant and disruption-tolerant networking, especially
  store-carry-forward routing.
- Opportunistic and device-to-device mobile communication.
- Authenticated key exchange using signatures and ephemeral Diffie-Hellman.
- Secure messaging protocols using authenticated encryption and replay
  protection.
- Offline-first and infrastructure-independent communication systems.

**Required literature work:** Replace this paragraph with a comparative table
containing at least 8--12 peer-reviewed or standards-based references. The
repository does not contain a bibliography, so citations must be selected by
the authors rather than fabricated from implementation documentation.

## 3. System requirements and design goals

### 3.1 Functional requirements

The system must:

- create and persist a local device identity;
- discover nearby CryptoMesh devices over BLE;
- authenticate a peer before accepting application traffic;
- send and receive encrypted text messages;
- queue packets when a direct path is unavailable;
- forward packets through authenticated peers;
- acknowledge successful end-to-end delivery;
- transfer media using BLE-compatible fragments;
- expose peer, connection, message, transfer, and failure state to the UI.

### 3.2 Non-functional requirements

The design targets:

- **Offline operation:** no Internet, backend, account server, or cloud
  synchronization is required.
- **Confidentiality:** application payloads are encrypted with AES-256-GCM.
- **Authentication and integrity:** identities and packet metadata are
  signature-protected, and encrypted payloads are authenticated.
- **Recoverability:** packets, transfers, and chunks are persisted locally.
- **Layering:** UI code does not directly access BLE, Room, or cryptographic
  primitives.
- **Platform compatibility:** Android API 26 or newer, with runtime permission
  handling for newer Android releases.

### 3.3 Threat-model boundary

The prototype is designed to reject forged identities, invalid signatures,
tampered ciphertext, malformed frames, stale handshakes, and duplicate packet
processing. The current implementation does not claim protection against a
compromised endpoint, traffic analysis, radio jamming, denial of service,
malicious flooding, global metadata correlation, or a fully specified
anonymous-routing adversary. These limitations must be stated explicitly in
the final paper.

## 4. Architecture

CryptoMesh is organized as a single Android application module with logical
boundaries:

```text
Compose screens
    -> ViewModels and StateFlow
        -> Direct mesh and identity repositories
            -> Handshake, wire, and secure-packet codecs
                -> BLE transport and GATT framing
            -> Room persistence
            -> Android Keystore
```

The application composition root creates the Room database, identity
Keystore, repositories, BLE transport, media file store, notification
coordinator, and application coroutine scope. ViewModels expose immutable UI
state. Repository interfaces isolate testable domain behavior from Android
transport and storage implementations.

The principal logical modules are:

| Module | Responsibility |
|---|---|
| App shell | Dependency composition and Android lifecycle integration |
| UI and navigation | Compose screens, routes, user actions, and state rendering |
| Identity and crypto | Keystore-backed signing, verification, ECDH, HKDF, and AES-GCM |
| Wire protocol | Handshakes, packet envelopes, direct messages, acknowledgements, and media payloads |
| BLE transport | Advertising, scanning, GATT connections, framing, and link events |
| Persistence | Room identity, secure packets, media transfers, and media chunks |
| Mesh repository | Session lifecycle, packet queues, relay, delivery state, and media orchestration |

## 5. Security and communication protocol

### 5.1 Local identity

On identity creation, the Android Keystore generates a non-exportable P-256
elliptic-curve key pair. The private key remains in Keystore and is used with
`SHA256withECDSA` for signing. Room stores the display name, public signing
key, key-derived device identifier, fingerprint preview, and creation time.

The device identifier is:

```text
CM- + uppercase hexadecimal(first four bytes of SHA-256(public signing key))
```

This identifier is a compact locally derived name, not a global account
identifier. If persisted metadata exists but the Keystore key is unavailable,
the repository creates replacement key material and reconciles the stored
identity metadata.

### 5.2 Discovery and transport

The application advertises a connectable BLE service with UUID
`7dd65a20-18ed-4bb7-84df-34d447b008a1`. A manufacturer-data field carries the
compact device identifier. The advertised identifier is treated as untrusted
until handshake verification. Scanning filters for the service UUID and
reports the BLE link identifier, advertised device identifier, and RSSI.

GATT transport frames application bytes so larger protocol messages can be
split across BLE writes and reconstructed at the receiver. The final paper
should report the exact negotiated MTU, effective frame payload size, write
mode, and connection parameters from the implementation and device traces.

### 5.3 Authenticated handshake

Each handshake hello contains:

- protocol version;
- device identifier and display name;
- long-term signing public key;
- ephemeral ECDH public key;
- 32-byte random nonce;
- creation timestamp;
- ECDSA signature over all preceding fields.

The receiver validates the protocol version, rejects self-connections, checks
the display name, enforces a five-minute clock-skew window, recomputes the
device identifier from the public key, optionally compares it with the
discovered identifier, and verifies the ECDSA signature. It then performs
ephemeral ECDH on `secp256r1`.

### 5.4 Session-key derivation

The ECDH shared secret is processed with HKDF-SHA-256. The salt is the
SHA-256 digest of the two nonces in deterministic device-ID order. The info
string includes the protocol label and sorted device IDs:

```text
CryptoMesh direct session v1|<sorted-device-id-1>|<sorted-device-id-2>
```

The output is a 32-byte session key used by AES-256-GCM. Deterministic ordering
allows both endpoints to derive the same key without an additional key
confirmation message, while fresh ephemeral keys and nonces produce a new
session key for each handshake.

### 5.5 Secure packets and acknowledgements

Application messages are encoded as signed secure-packet envelopes. The packet
path verifies the envelope, decrypts the payload with the authenticated
session, persists accepted packets, rejects duplicates, and enforces expiry.
An encrypted end-to-end acknowledgement is generated after successful
processing. Delivery states distinguish queued, sending, awaiting
acknowledgement, acknowledged, and failed outcomes.

### 5.6 Store-carry-forward relay

When a destination is not directly reachable, a sealed packet can remain in
local persistence and be forwarded over another authenticated BLE link. Relay
is opportunistic: the implementation does not require a route-discovery
service or centralized directory. Forwarding state prevents repeated
forwarding over the same link, while packet identity, expiry, and duplicate
checks bound reprocessing.

The current beta should be described as an opportunistic relay prototype. It
does not implement adaptive replication, congestion control, route scoring,
global delivery guarantees, or a formal routing protocol.

### 5.7 Media transfer

Media is represented as an offer followed by encrypted chunks and completion
or acknowledgement messages. The media engine prepares a transfer, divides
the payload into BLE-compatible fragments, records chunk hashes and statuses,
and reassembles verified chunks at the receiver. Sending runs asynchronously
so a large transfer does not block text-message interaction. Interrupted
transfers remain visible as failed records and can be retried when the peer is
authenticated.

## 6. Implementation

The prototype uses Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Java 17
bytecode, Android API 26 as the minimum SDK, and API 35 as the target SDK.
Jetpack Compose and Material 3 implement the UI; ViewModel and StateFlow
provide reactive state; Room persists local data; Kotlin coroutines manage
asynchronous transport and transfer work; Kotlin serialization encodes wire
objects; Android JCA and Keystore provide cryptographic operations.

The application includes Home, Peers, Chat, Profile, Permissions, onboarding,
and identity-creation screens. Notifications expose peer discovery,
connection, message, acknowledgement, failure, and Bluetooth availability
events while the application is running.

## 7. Verification and experimental methodology

### 7.1 Repository verification

The repository documents a local verification gate:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The test suite includes coverage for:

- identity persistence and reset;
- secure-packet persistence;
- BLE frame encoding and malformed-frame rejection;
- signed handshake verification and rejection;
- direct-wire encoding;
- authenticated encryption and tamper rejection;
- media preparation, chunking, and reassembly;
- group and broadcast cryptographic behavior;
- repository message, acknowledgement, and failure state;
- Compose ViewModel state transitions;
- paired two-peer encrypted exchange;
- three-node offline text and media relay paths;
- **DOP-Handshake (`DOP-v1`) validation, protocol transcript binding, relay cache management, multi-sender pre-key concurrency, Room v3-to-v4 migration, and 6 multi-node end-to-end offline initiation scenarios.**

### 7.1A Reproducible JVM Benchmark Comparison: Direct Handshake vs. DOP-Handshake (`N = 30` trials)

Measured via `DopMetricsCollector` in `DopMeshIntegrationTest.scenario6_sideBySideEvaluationDirectVsDopHandshake`:

| Metric | Direct Handshake (`AuthenticatedHandshake`) | DOP-Handshake (`DOP-v1` Initiation + Completion) |
|---|---|---|
| Requires Simultaneous BLE Presence | **Yes** (both peers must be online & connected) | **No** (recipient can be completely offline at initiation) |
| Pre-Key Record Generation Latency (median / p95) | N/A | $2,646\ \mu\text{s}$ / $2,725\ \mu\text{s}$ ($1\text{ SPK} + 4\text{ OPKs}$, ECDSA-signed) |
| Pre-Key Record Validation Latency (median) | N/A | $1,471\ \mu\text{s}$ |
| Sender Initiation Latency (median / mean / p95) | $4,535\ \mu\text{s}$ / $4,596.6\ \mu\text{s}$ / $5,296\ \mu\text{s}$ (2-way RTT crypto) | $3,419\ \mu\text{s}$ / $3,507.7\ \mu\text{s}$ / $4,180\ \mu\text{s}$ (1-shot offline seal) |
| Recipient Completion Latency (median / mean / p95) | Included in 2-way handshake | $1,736\ \mu\text{s}$ / $1,827.9\ \mu\text{s}$ / $2,531\ \mu\text{s}$ |
| Signed Pre-Key Bundle Size (bytes / BLE frames) | N/A | $1,898\text{ B}$ / $12\text{ frames}$ ($180\text{ B}$ MTU, $171\text{ B}$ payload/frame) |
| Handshake / Initiation Wire Size (mean bytes / BLE frames) | $1,670\text{ B}$ ($2 \times 835\text{ B}$ `HandshakeHello`) / $10\text{ frames}$ | $1,386.9\text{ B}$ (includes encrypted initial payload) / $9\text{ frames}$ |
| Relay Cache Hit Rate (`N = 30` lookups) | N/A | $100\%$ ($30$ hits / $0$ misses) |

### 7.1B Patent and Research Novelty Summary (DOP-Handshake)

1. **Decentralized Relay-Cached Pre-Key Distribution Without Central Servers:** Unlike Signal X3DH (which relies on an online central server to atomically pop one-time pre-keys and serve pre-key bundles), `DOP-Handshake` distributes self-authenticating, epoch-versioned `DopPreKeyRecord` bundles across untrusted opportunistic BLE relays (`DopRelayCacheManager`) using two-phase catalog/digest synchronization (`DopPreKeySyncMessage`) with validation-first ingestion and deterministic conflict resolution.
2. **Coalesced Grace-Window One-Time Pre-Key State Machine for Uncoordinated Offline Senders:** Because multiple disconnected senders may obtain the same cached `DopPreKeyRecord` from different BLE relays while the recipient is offline, strict single-use deletion of a one-time pre-key upon the first arriving packet would cause all other concurrent offline senders' stored-and-forwarded packets to fail decryption. `DOP-Handshake` introduces a bounded `ACTIVE -> CONSUMED_GRACE -> RETIRED` state machine (`OPK_COALESCING_GRACE_MILLIS = 24h`, `MAX_CONCURRENT_OPK_USES = 4`) combined with per-sender ephemeral keys $EK_A$ and 32-byte nonces $N_A$, guaranteeing distinct session keys ($K_{sess}$) for every concurrent sender while enforcing deterministic private-key zeroization upon grace expiration.
3. **Dual-Layer Store-Carry-Forward Envelope with Transcript-Bound Confirmation Tag:** `DopInitiation` encapsulates an inner AES-256-GCM payload (bound via AAD to the full length-prefixed cryptographic transcript $H_{tr}$) inside an outer ECDSA-signed `SecurePacketEnvelope` so existing CryptoMesh relays can route, deduplicate, and expire session-initiation packets without inspecting inner session parameters, while the recipient returns an HMAC-SHA256 confirmation tag (`dopConfirmationTagBase64`) piggybacked on the encrypted mesh `Acknowledgement`.

**Publication requirement:** Run the gate on a clean checkout and record the
date, Android Gradle Plugin version, JDK version, test count, pass/fail count,
lint result, and APK size. Do not convert the documented test categories into
performance claims.

### 7.2 Recommended physical-device evaluation

Use at least three physical Android devices with BLE advertising support. Record
Android version, device model, chipset, battery level, BLE state, distance,
orientation, and radio environment. Repeat each experiment at least 30 times
and report mean, median, standard deviation, 95th percentile, and confidence
interval where appropriate.

Recommended experiments:

| Experiment | Independent variables | Metrics |
|---|---|---|
| Discovery | Distance, orientation, interference | Discovery probability, time to first discovery |
| Authentication | Device pair, cold/warm start | Handshake latency, failure rate |
| Direct text | Payload size, distance, RSSI | End-to-end latency, delivery ratio, goodput |
| Relay | Hop count, contact duration | Delivery ratio, latency, duplicate rate |
| Media | File type, size, chunk count | Throughput, completion ratio, retry count |
| Reliability | Disconnect timing, packet loss | Recovery time, failed-transfer visibility, eventual delivery |
| Energy | Screen state, scan/connect/send mode | Battery current or energy per delivered kilobyte |
| Scalability | Number of peers and queued packets | Memory, CPU, queue growth, relay overhead |

### 7.3 Baselines

At minimum, compare:

1. direct one-hop CryptoMesh delivery;
2. two-hop or three-node store-carry-forward delivery;
3. a non-relayed direct-send configuration;
4. a simple plaintext or cryptography-disabled transport only in a controlled
   benchmark, never as a deployment mode.

The comparison must preserve payload size, device pair, BLE configuration,
distance, and trial count. A baseline should not be presented as a security
alternative unless its threat model is equivalent.

## 8. Results template

The following values must be populated from physical-device experiments:

| Metric | Configuration | Result |
|---|---|---|
| Median discovery time | *[distance/device pair]* | *[value]* |
| Handshake latency | *[cold/warm]* | *[value]* |
| Direct text delivery ratio | *[payload size]* | *[value]* |
| Median one-hop latency | *[payload size]* | *[value]* |
| Median two-hop latency | *[contact schedule]* | *[value]* |
| Media throughput | *[file type/size]* | *[value]* |
| Media completion ratio | *[trial count]* | *[value]* |
| Energy per delivered MB | *[device/mode]* | *[value]* |
| Maximum tested queued packets | *[device/storage]* | *[value]* |

Do not claim that the prototype is faster, more energy-efficient, or more
scalable than another system until a controlled comparison supports the claim.

## 9. Discussion

CryptoMesh demonstrates that a mobile application can combine local identity,
authenticated BLE sessions, encrypted application packets, persistence, and
opportunistic relay in a single offline-first architecture. The repository
boundaries make the protocol and relay logic testable without requiring a
physical radio for every unit test, while the Android transport remains behind
an interface.

The principal engineering trade-off is between resilience and resource usage.
Persistence improves eventual delivery but consumes storage and may increase
duplicate-processing and relay overhead. Chunking enables media over BLE but
adds acknowledgements, metadata, reassembly state, and failure modes. The
current opportunistic strategy is intentionally simple and suitable for a beta,
but larger deployments require congestion control, relay prioritization,
adaptive replication, and resource-aware scheduling.

The security design authenticates peers and protects message contents in
transit. It does not hide communication timing, BLE presence, packet size,
device identifiers, or the fact that a device participates in the mesh.
Compromise of an endpoint exposes its local plaintext and active session
material. These boundaries should be reflected in both the threat model and
the conclusions.

## 10. Limitations and future work

The beta does not include Internet synchronization, adaptive replication,
wallet or payment functionality, Wi-Fi Direct, background reconnection, a
foreground Bluetooth service, or durable plaintext conversation history.
Additional work should investigate:

- formal protocol review and independent cryptographic analysis;
- replay, flooding, and denial-of-service resistance under malicious peers;
- congestion control and bounded packet retention;
- adaptive relay selection and delivery prediction;
- background execution and reconnection policies;
- larger-scale multi-hop experiments;
- battery and thermal characterization;
- metadata minimization and privacy-preserving peer discovery;
- key rotation, recovery, and user-visible trust management;
- production release hardening, instrumentation, and crash recovery.

## 11. Conclusion

CryptoMesh is a functional Android prototype for secure offline messaging over
BLE. It combines Keystore-backed local identities, signed ephemeral ECDH
handshakes, HKDF-derived AES-256-GCM sessions, signed persistent packet
envelopes, encrypted acknowledgements, opportunistic relay, and chunked media
transfer. The implemented test coverage supports a feasibility claim for the
core protocol and application paths. A publishable IEEE paper should augment
this implementation report with a scholarly related-work section, a precise
threat model, reproducible physical-device methodology, quantitative results,
and a comparison against clearly defined baselines.

## References to repository artifacts

These are implementation artifacts for traceability in the paper draft; they
are not substitutes for external IEEE references:

[R1] CryptoMesh repository, `TECHNICAL_DOCUMENTATION.md`.

[R2] CryptoMesh repository, `MODULE_DOCUMENTATION.md`.

[R3] CryptoMesh repository, `README.md`.

[R4] `app/src/main/java/com/cryptomesh/frontend/protocol/AuthenticatedHandshake.kt`.

[R5] `app/src/main/java/com/cryptomesh/frontend/crypto/AndroidIdentityKeyStore.kt`.

[R6] `app/src/main/java/com/cryptomesh/frontend/data/repository/DirectMeshRepository.kt`.

[R7] `app/src/test/java/com/cryptomesh/frontend/`.

## IEEE conversion checklist

- Select the official IEEE conference or journal template.
- Replace the proposed metadata and author placeholders.
- Add peer-reviewed related-work citations and standards references.
- Insert an architecture figure and a sequence diagram for the handshake and
  relay paths.
- Add a table of exact frame, MTU, chunk-size, timeout, and expiry constants.
- Run the complete verification gate and record reproducible software versions.
- Conduct physical-device experiments and populate Section 8 with statistics.
- Report ethical, privacy, and threat-model boundaries.
- Remove repository-only wording and convert `[R1]`--`[R7]` to the venue's
  citation style or retain them only as software artifacts.
