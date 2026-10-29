# OffGridChat production audit

Audit started 2026-10-02. Repository code is the source of truth. This is a living audit, not a production certification. Status: **not release ready**. Initial inspection traced screen → ViewModel → controller → crypto/Room/service for the flows below. Hardware behavior remains unverified unless explicitly recorded.

## Release blockers and root causes

| ID | Severity | Finding and code evidence | Required correction / verification |
|---|---|---|---|
| C01 | Critical | IdentityManager writes `enc:v1<iv>:<ciphertext>`, detects `enc:v1:`, and splits into three components while comparing component zero to `enc:v1`. Restart regenerates identity; restoration exceptions silently replace keys. | One tested envelope format, recover the exact legacy writer output, never silently replace an existing unreadable identity; Android Keystore restart tests. |
| C02 | Critical | Controller.signatureInput covers authenticatedHeader + payload. Header omits ackMessageId, status, sourceName and sourcePublicKey. Relays can alter receipt targets and approval responses without invalidating the signature. | Canonical signature input covering every immutable field; protocol version change and tamper tests. |
| C03 | Critical | requireApprovalFlow and allowNewConnectionsFlow are only consumed by UI; incoming DATA is decrypted/stored without approval or source-block checks. Only the immediate hop is consistently blocked. | Destination authorization before deduplication/ACK/persistence; enforce logical source blocks through relays. |
| H01 | High | First signed packet authenticates a socket with no fresh challenge. A captured signed packet can be replayed on another socket; previousHopId is unsigned. | Fresh challenge/proof of private-key possession bound to the socket session, bounded handshake timeout. |
| H02 | High | Controller dedup cache is populated before DATA decrypt/persistence, then duplicates receive ACK unconditionally. A rejected payload can subsequently be acknowledged. Persistent duplicate lookup checks ID only. | ACK only a matching persisted incoming message; separate successful receipt state from relay deduplication. |
| H03 | High | Controller.send transmits before saving. ACK lookup can race insertion; generic status UPDATE permits READ → DELIVERED and delivery → SENT regressions. | Persist SENDING before transmit; atomic monotonic transitions and timestamps; race tests. |
| H04 | High | sendConnectionRequest is commented out; accept/reject only mutate local Room rows and send no response. Unsolicited ACCEPTED responses change approval. | Complete correlated request/response state machine and persist both sides' decisions. |
| H05 | High | Relay Mode is not consulted in relayForwardIfNeeded or route-announcement flooding. | Enforce policy on every third-party forwarding path. |
| H06 | High | Route announcements only replace/forward when strictly better or expired, so equal routes are not refreshed/propagated. Disconnect does not invalidate routes through a hop. | Refresh chosen equal routes; bounded flood propagation; route-loss and three-device chain tests. |
| H07 | High | Single ConnState represents multiple active sockets; UI enables sending only for the last direct address. Mesh-only conversations and earlier connected peers cannot send through the UI. | Expose per-peer authentication/reachability and connect existing UI controls to it. |
| H08 | High | Controller disconnectPeer can read uninitialized readJob. Old reader finally removes by address and can close a replacement socket. start/stop server share a mutable socket across jobs. Failed/cancelled connect can leak sockets. | Connection-specific ownership, atomic replacement/removal, cancellation closes blocking IO, connect/write timeout tests. |
| H09 | High | BluetoothService starts new collectors on every onStartCommand. Auto-reconnect only starts a listener, never connects. Turning Bluetooth back on does not restart the server independently of NearbyScreen. | Idempotent service lifecycle, enabled-state observation, real bounded reconnect policy. |
| H10 | High | Singleton controller jobs/sockets survive stopping the service; run-in-background off is not enforced when the activity backgrounds. release launches cleanup then immediately cancels its scope. | Explicit foreground/service ownership, awaited teardown, restart-safe lifetime. |
| H11 | High | Adapter enabled state is read before permissions in construction/startScan/startServer. Nearby permission state is not refreshed on resume; discovery failures can be treated as definitive lack of support. | Permission-safe state refresh, revocation and settings-return tests across API levels; distinguish unknown SDP result. |
| H12 | High | Persisted online flags/routes survive process death; no outbox retry/ACK timeout reconciliation. Status can remain SENT forever; retryCount is unused. | Startup reconciliation, durable outbound delivery/retry design and crash injection. |
| H14 | High | Backup XML is template-only despite private identity wrapped by non-transferable Keystore key and plaintext Room messages. | Explicit cloud/device-transfer exclusions and restore tests. |
| H15 | High | Only arithmetic/package-name tests exist. No protocol/security, DAO/migration, controller or lifecycle tests. | Add deterministic regressions and Android integration tests; multi-device release acceptance suite. |
| H16 | High | Peer count, metadata lengths, dedup cache and packet rates are unbounded. Old signed timestamps never expire. Blocking IO and crypto can exhaust resources. | Resource budgets, bounded replay window, protocol limits, adversarial tests. |

## Other findings

| ID | Severity | Finding / root cause | Correction / verification |
|---|---|---|---|
| M01 | Medium | identityVerified=true means a signature validated, not that a human verified the fingerprint. No peer verification workflow; short displayed fingerprint is only 32 bits. | Separate session authentication from explicit identity verification; preserve full fingerprint and define verification UX. |
| M02 | Medium | Static identity ECDH keys also sign packets; no forward secrecy or key rotation protocol. | Document threat model and design versioned session keys before claiming forward secrecy. AES-GCM itself uses random 96-bit IVs, 128-bit tags, HKDF-SHA256 and header AAD; test these properties. |
| M03 | Medium | Message encryption metadata is discarded by repository mapping; deliveredAt/readAt/failedAt remain unset. | Persist actual crypto/delivery metadata and verify exports/DAO state. |
| M04 | Medium | Repository read-modify-REPLACE operations are not transactions. Pending-request uniqueness is a check-then-insert with a nonunique index. Block/approval updates can race conversation updates. | Transactional DAO operations and concurrency regressions. |
| M05 | Medium | Schema export is disabled; only migration 2→3 exists. Legacy empty-ID rewriting plus duplicate deletion can discard distinct records; no migration fixtures. | Export schemas; inspect historical version 1/2 schema before introducing a safe migration; data-preservation tests. |
| M06 | Medium | Read receipts mark every incoming message in the conversation read after scroll, not actual visible items; local READ persists even when receipt send fails. | Lifecycle/visibility-aware reading and durable receipt reconciliation. |
| M07 | Medium | Outbound writes update lastActivity so dead peers can look live; every packet marks conversation online even with presence hidden. | Separate receive liveness from published presence, expiry and privacy policy. |
| M08 | Medium | Notification address extra is never consumed by MainActivity/navigation. Preview hiding still exposes sender; notification channel privacy requires device checks. | Route notification taps into the matching conversation; test lock screen and permissions. |
| M09 | Medium | IdentityManager uses runBlocking and is called from profile composition; unhandled storage/crypto exceptions can terminate controller jobs. | IO initialization with observable failure/recovery state; preserve cancellation. |
| M10 | Medium | Chat exports load full histories into memory and share through Intent extras (size limit); ZIP names contain untrusted peer names/addresses. | Stream file exports and safe entry names; explicit export error state. |
| M11 | Medium | Send failure result is discarded and composer clears immediately. Connected/E2EE label appears before application authentication. | Bind state to completed authentication and retain failed draft/message feedback. |
| M12 | Medium | Release optimization disabled; release signing/distribution configuration and optimized runtime behavior unverified. | Verify actual release variant, manifest and R8 path before distribution; avoid blanket keep rules. |
| L01 | Low | BluetoothRepository is an unused facade, commented-out crypto/request helpers remain, and DAO byte statistic counts characters rather than bytes. | Search usages before cleanup; correct statistic. |
| L02 | Low | Profile says Visible/Mesh Active based only on Bluetooth-on; Chats says 1 Active; bio is local only. | Correct state bindings without redesign. |

## Coverage map and acceptance evidence still needed

- Adapter enable/disable, discoverability, discovery: traced intent/result/receiver paths. Test denied/revoked permissions, Bluetooth off/on, cancelled visibility, location setting on pre-31 devices, SDP timeout and settings return.
- Connections, server/client and multi-device: traced RFCOMM creation, readUTF/writeUTF, socket map and cleanup. Test simultaneous dial, connect cancellation, replacement reader, peer power loss, two simultaneous peers and radio cycling.
- Messaging, conversations, delivery and receipts: traced UI to encryption to routing to Room to UI. Test ordering, unicode, maximum frames, send failure, duplicate/tampered packets, delayed/reordered ACK/read, restart and history preservation.
- Approval, blocking, presence and settings: all stored preferences compared with runtime consumers. Test policy changes live, relayed blocked sender, unsolicited response and pending-request races.
- Mesh/relay: traced announcements, route table expiry, fallback flooding and hop limits. Test A–B–C and diamond topology, relay disabled at B, TTL exhaustion, refresh beyond 120 seconds, lost hop and duplicate suppression. One phone cannot establish this evidence.
- Crypto/identity: inspected AES-GCM, ECDH/HKDF, wrapped private key, signature scope and fingerprints. Test tamper resistance for every immutable field, wrong key/AAD, legacy identity recovery, corrupt storage, Keystore restoration and cold restart.
- Persistence/architecture: inspected Room entities/queries/migration, DI, repositories, ViewModel flow subscriptions, service and activity ownership. Require real SQLite migrations and race/process-death tests.
- Privacy/export/backup: traced local plaintext history, sharing intents, notification settings and XML backup declarations. Require lock-screen, backup/transfer, export size and hostile-filename checks.
- Release/testing: baseline verification launched; results recorded below. Existing tests do not verify features. Production sign-off requires release-device execution, long-running Bluetooth tests and no open critical/high functional/security blockers.

## Safe implementation order

1. Preserve existing work; establish baseline/build/device availability; retain this audit and add regression tests.
2. Repair identity persistence with legacy recovery, then canonical authenticated protocol metadata (versioned incompatibility is explicit).
3. Add session challenge authentication and destination authorization; complete correlated approval/blocks and policy enforcement.
4. Make delivery persistence and deduplication atomic; add outbox, receipt and restart reconciliation.
5. Repair socket/service ownership, permissions, adapter cycles and reconnect behavior.
6. Fix route refresh, relay enforcement and per-peer reachability; exercise real multi-device paths.
7. Validate schema migration, privacy/backup/export, error reporting and all settings; remove only proven dead code.
8. Run critical regressions, Android lifecycle/migration/security tests, release checks and multi-device soak tests. Re-audit each corrected flow. No compile-only sign-off.

## Verification log

- Initial working tree already contained deletions in BluetoothService.kt and BluetoothViewModel.kt; preserved.
- Gradle initially failed in sandbox creating `C:\.gradle` lock directory; rerun requested with approved toolchain access.
- adb reports one attached device, ZA223H8LJN. No multi-device verification yet.
- Connected phone reports API 36. This does not cover minSdk 26 or targetSdk 37 behavior.
- First Gradle invocation completed debug/release assembly, unit tests and debug lint successfully. Source edits overlapped that invocation, so it is not a clean baseline or verification of all final edits. Its unit report contains only the original arithmetic test; a second complete verification is required.
- First lint report: 2 ObsoleteSdkInt and 7 UnusedResources findings. No reported lint errors; this does not negate the functional/security findings above.

## Increment 1

- C01: unified private-key envelope codec; recovers original missing separator, normalizes only after authenticated decryption and public/private-key validation, keeps canonical wrapped data stable, refuses to silently regenerate a stored identity or delete the legacy alias. Missing wrapping key no longer creates a replacement during decrypt. Error presentation and legacy alias migration remain open under M09/C01; preserving corrupt identity is intentional, but needs an actionable recovery flow.
- C02: canonical ordered signature input now covers all immutable packet metadata including ackMessageId/status/name/public key. Protocol bumped to 2; version-1 peers are rejected. Both devices must upgrade. Session replay/challenge work remains open under H01.
- Added JVM identity-envelope, packet tamper/relay/round-trip and ECDH/AES-GCM tests. Added non-destructive Android identity restoration test using fresh manager instances. This is not a process-kill test.
- No screen/design changes. Existing service/ViewModel edits preserved.
- Final edited-source verification: `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:connectedDebugAndroidTest` completed successfully. JVM reports: 8 tests, zero failures/errors (7 new functional/security tests plus the original arithmetic test). Android reports include IdentityRestorationTest and ExampleInstrumentedTest on the API 36 phone. Debug/release APK assembly and lint passed; release APK is not a production signing/distribution sign-off. `git diff --check` passed.
- Next increment: destination approval/block enforcement and replay-safe session authentication, then delivery/deduplication ordering. C01 recovery UX and remaining legacy key-alias migration still require work; C02 packet-field signature regression is covered, not the entire authentication protocol.

## Platform references

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions): use runtime permissions and OS-specific handling when implementing H11.
- [Android Auto Backup](https://developer.android.com/identity/data/autobackup): allowBackup=false alone may not disable manufacturer device-to-device transfer. Explicit extraction rules remain required for H14.

## Increment 2 — delivery persistence and duplicate handling

- H02: destination DATA bypasses the relay/control dedup cache. Every attempt must pass signature validation and AES-GCM decryption; the envelope version must match the authenticated header. A Room transaction inserts once or verifies the existing row's sender, direction, timestamp, plaintext and encryption version before allowing an ACK. Conflicting IDs cannot overwrite content or receive success. Duplicate incoming messages produce no extra notification. Relay/control cache keys now include the source identity, avoiding cross-source suppression.
- C03 (partial): blocked logical sources are rejected even through an unblocked relay, before deduplication/ACK. Approval and allow-new-connection enforcement are still open; this is not a claim that C03 is resolved.
- H03: MessageDelivery persists an encrypted SENDING row before any network write. The DAO atomically prevents late SENT/FAILED/DELIVERED events from reversing READ or established delivery. A delayed authentic receipt may recover a locally failed send. Exceptions/cancellation mark pending sends FAILED without suppressing the original exception/cancellation. Process-death reconciliation/outbox retries remain open under H12.
- M03 (partial): encryption/version metadata is now persisted for real incoming/outgoing wire messages; delivery/read/failure timestamps update with actual state transitions. Old rows are not retroactively relabeled as encrypted.
- Added six Android SQLite tests using an isolated in-memory database: fast receipt during transmit, write failure/exception/cancellation, duplicate outbound IDs, 20 concurrent incoming duplicates plus conflicting content/identities, collision with an outgoing ID, and concurrent reordered status events. All six passed on the API 36 phone; complete run has 8 Android tests and 8 JVM tests, zero failures/errors. Debug/release builds and debug lint passed. These tests exercise the actual repository, delivery coordinator and Room transactions, not physical Bluetooth IO.
- No schema change, database deletion, or UI redesign. Existing migration concerns remain open.
- A concurrent IDE change to `.idea/misc.xml` selected `temurin-25` instead of `jbr-25`. It was not made or reverted by this audit.
- Re-audit: the only destination DATA ACK path now follows successful decrypt plus transactional INSERTED/DUPLICATE classification; the outgoing wire call is inside MessageDelivery after SENDING persistence. Remaining controller/session authorization, bounded retry/outbox, crash recovery and real multi-device acceptance remain open. Comment/import cleanup after the tests does not change these semantics.

## Increment 3 — admission policy and relay enforcement

- C03: incoming DATA admission is now checked transactionally with persistence and duplicate eligibility. Unknown senders cannot persist messages or receive ACKs when new connections are disabled. When approval is required, a single pending request is stored and no message is accepted until a local decision approves that identity. Automatically admitted contacts are remembered, so disabling new connections preserves established approved contacts. Blocking overrides automatic approval, explicit approval and duplicate ACK eligibility.
- Local ACCEPT/REJECT now resolves a persisted pending request by its ID inside a Room transaction, rejecting stale or blocked requests; a concurrent block clears approval and closes pending requests. A previously rejected identity remains rejected on retry, including after automatic admission is enabled. A remote CONNECT_RESPONSE no longer grants local consent or changes local inbound request decisions. H04 remains open: outbound request correlation, responses, rejection recovery and UI feedback still need a complete state machine; responses are currently safely ignored.
- M04: conversation metadata updates merge under a transaction, preserving concurrent consent/block changes. A new node identity cannot inherit approval from an older identity using the same local address. Reconnect no longer forcibly clears approval. Existing conversation addresses are retained when receiving through a different path. Transport-address reuse and multi-device UI remain H07 work.
- H05: both unicast/fallback forwarding and route-announcement forwarding consult Relay Mode. The node can still send its own packets and receive its own messages with relay disabled. Blocked final destinations are rejected before outbound direct/routed/flood selection. Real radio verification of this setting remains required.
- Six isolated SQLite admission tests cover concurrent pending requests, approval withholding/acceptance, disabled new connections with an existing approved peer, blocked duplicate replay, racing block/accept, metadata refresh and identity-change consent, and rejection. The first run passed all 14 Android tests and 8 JVM tests plus debug/release builds and lint. The final rejection-on-retry strengthening is being reverified separately.
- No screen layout/design changes and no schema migration. Corrupted-identity recovery, socket replay authentication, outbox/retries and hardware validation remain open. Admission operates at the chat identity layer; socket admission/lifecycle is still under H01/H08/H10/H11.
- The rejection retry rerun initially failed solely because adb had no attached device. The user reconnected ZA223H8LJN. The subsequent complete build/test/lint run passed: all 14 Android tests (including rejection retries) and 14 JVM tests (including the new handshake tests). No device-verification claim was based on the disconnected run.

## Increment 4 — fresh socket authentication

- H01: every PeerConnection owns a random 256-bit challenge and exchanges signed AUTH_HELLO/AUTH_PROOF packets. A proof binds both challenges plus the signed source/destination identities. A signed route advertisement cannot authenticate a socket. Captured hello/proof pairs fail on a new socket even with the same local identity. Session validation uses a monotonic 15-second deadline; a separate job closes unauthenticated sockets if the peer stalls. Only handshake packets may be written before authentication.
- The UI's existing Connected state is emitted only after proof validation, identity/block checks and direct-route recording. Authenticated route announcements follow proof exchange. No visual redesign. Protocol version is now 3: older peers must upgrade; there is no downgrade path to replayable authentication.
- H08 (partial): reader jobs are assigned before publishing the peer; nullable job ownership avoids uninitialized readJob crashes. Replacement, timeout, reader-finally and write-failure cleanup close their exact peer socket, using compare-and-remove instead of removing an arbitrary current socket at the same address. Send/read processing rejects stale peer instances. Server ownership, blocking-connect cancellation and broader lifecycle/reconnection work remain open.
- M01 (partial): fresh authenticated peers are no longer automatically marked as human-verified identities. Existing verification flags are preserved; a user fingerprint-verification workflow and treatment of legacy flags remain open.
- Six JVM handshake tests passed: two-way proof exchange; captured transcript on a new socket; unsigned/advertisement/self-reflection rejection; wrong challenge/identity/destination/tampered packets; timeout; and mid-handshake identity replacement/repeated proof. An additional Android-provider/JSON wire-round-trip test is in final verification.
- These tests do not establish physical RFCOMM connectivity, multi-device handshake ordering under radio loss, or a multi-hop mesh. One attached phone cannot prove those requirements. Outbound request/response correlation (H04), route refresh (H06), outbox/process death (H12), and the other open audit entries remain release blockers.
- Final increment-4 verification passed: `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:connectedDebugAndroidTest`. Reports show 14 JVM tests and 15 Android tests, zero failures/errors/skips, including Android SessionHandshakeDeviceTest and rejection-on-retry assertions. `git diff --check` passed. The existing UI design was preserved.
- Next dependency-ordered work: complete correlated connection requests/responses and outgoing approval feedback, then route refresh and socket/service lifecycle. Retain all remaining release gates rather than treating these narrow test results as production certification.
