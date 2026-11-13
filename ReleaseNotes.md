# Release Notes

## Release 2.0.2

### fixed
- With sealing off, a pushkey longer than about 300 characters (the API allows 512 bytes) made every
  notification to that user fail with `400`: the device history's storage id contained the pushkey and
  exceeded the 450-character id column. The history is now stored under a SHA-256 of user and pushkey.
  Histories under the old id are still read, moved to the new id by the next notification (keeping their
  retention deadline) and deleted with the device. No migration step is needed.
- **Breaking for clients:** an operation on another user's pusher is rejected with
  `403 M_FORBIDDEN` (A_29978) instead of succeeding. Registering an app ID and pushkey another user
  holds, with or without `append`, no longer moves the pusher to the caller and answers `403`;
  deregistering another user's pusher answers `403` instead of `200`; `GET`/`POST
  /channels/{pushkey}` and `GET /history/device/{pushkey}` for a pushkey another user holds answer
  `403` instead of `404 PUSHER_NOT_FOUND`. A pushkey nobody holds still answers `404`, and
  deregistering a pusher nobody holds still `200`. A device that changes accounts has to deregister
  its pusher before the new account registers it; the takeover of 2.0.0-rc1 followed A_27154, which
  does not apply to this service. `append` is still accepted but has no effect. The FdV contract is
  unchanged; see README "Current limitations".
- A new index of the users per pushkey tells the `403` from the `404` on the channel and history
  paths. Pushers registered by an earlier release enter it when their owner registers them again;
  until then another user still gets `404` for them there. Pushers two users shared through
  `append: true` stay shared, and each user keeps managing its own registration. A pushkey the Push
  Gateway rejects is now also removed from the owner index, so it no longer blocks another user from
  registering it.

## Release 2.0.1

### added
- With sealing on, records are sealed and their storage ids derived with the keys of a keychain the
  operator supplies HSM-wrapped in the new `notification.persistence.sealing.keychain` (JSON; from a
  Kubernetes secret as `NOTIFICATION_PERSISTENCE_SEALING_KEYCHAIN`, or as a file named by
  `notification.persistence.sealing.keychain-file`). Each entry wraps a DEK and a pseudonymization key
  together under its KEK. Each instance unwraps the entries once, on first use, so reads and writes no
  longer call the HSM. Every seal derives its own key from the DEK and a random salt from the pinned
  DRBG (HKDF-Expand); the service no longer generates or wraps keys itself. See README "Record keys".
- Keychain generator image `$CI_REGISTRY_IMAGE/keychain-generator`: draws a DEK and a pseudonymization
  key, has the HSM proxy wrap them and writes the keychain with the entry appended. Configured by
  environment only: `KEYCHAIN_OUTPUT_FILE` is required, and `HSM_PROXY_KEK_ID` for a new keychain
  (extending one, it defaults to the KEK of the newest entry). `HSM_PROXY_ENDPOINT`,
  `KEYCHAIN_INPUT_FILE`, `KEYCHAIN_ENTRY_ID` (defaults to `…-v1` for a new keychain and to the next
  version otherwise) and `KEYCHAIN_EMERGENCY` are optional, and `KEYCHAIN_ACTIVE_FROM`
  defaults to now for a new keychain but is required when extending one. An existing output file is left
  alone and the run ends successfully, so the image can run as an init container.
- Key rotation (A_29963, A_29960): a new keychain entry takes over the writes at its `activeFrom`, and a
  rotation job moves the records of older entries to it, with new storage ids and sealed under the new
  DEK, one minute after boot and then every `notification.persistence.sealing.rotation-interval`
  (default `1h`). Records with a retention period expire under their entry, unless the new entry is an
  emergency entry (`KEYCHAIN_EMERGENCY=true`): then they move too and keep their expiry. A record that
  fails is logged and retried on the next run; ten failures in a row stop the run. The job logs the live
  records per entry as its progress. See the runbook in README "Key rotation".

### changed
- **Breaking, with sealing on:** records sealed by an earlier build no longer open, and their storage
  ids no longer match; start from an empty database. Migration `V4` drops `sealed_key`, renames
  `storage_record.kek_id` to `keychain_id` (the entry a record is under) and drops the unused
  `storage_record.dek`. `hsm-proxy.kek.dek-key-id`, `hsm-proxy.kek.pseudonym-key-id` and
  `notification.persistence.sealing.dek-cache-size` are gone.
- With sealing on, readiness reports whether the keychain is unwrapped and holds the entry of every live
  record, instead of whether the HSM proxy answers: a broken keychain, or an old entry removed while
  records are still under it, stops a rollout at its first instance, and an HSM proxy outage no longer
  takes instances that hold their keys out of service.
  The readiness check is now named `keychain` instead of `hsm-proxy`.

## Release 2.0.0

### added
- `notification.history.retention` (default `P30D`): a device's message history expires that long
  after its first entry and is purged with the other expired records (A_29988).
- Optional rate limit on the RS endpoints (A_29987), off by default: `QUARKUS_FAULT_TOLERANCE_GLOBAL_RATE_LIMIT_ENABLED`,
  `_VALUE` (default `1000`), `_WINDOW`, `_WINDOW_UNIT` (default one second). Over the limit the answer is
  `429 RATE_LIMITED` with `Retry-After`.
- Every `503 SERVICE_UNAVAILABLE` carries `Retry-After: 5` (A_29987).

### changed
- **Breaking:** the RS paths require an mTLS client certificate (A_29980); see README "Authentication".
- A plain (unencrypted) notification payload above 3072 serialized bytes is now rejected with
  `413 PAYLOAD_TOO_LARGE` (A_29987); before, plain mode had no limit beyond the HTTP body size.
- Disabled access log and tracing to prevent logging and tracing of KVNRs and Telematik-IDs. (A_29990)
- **Breaking:** `POST /notifications` for a channel outside `notification.channels.allowed` is rejected
  with `403 CHANNEL_NOT_ALLOWED` instead of `422 UNKNOWN_CHANNEL`, following the successor to A_29985;
  the service no longer answers `422`.

### fixed
- The native image boots with an unencrypted EC or RSA client key for the Push Gateway mTLS
  (A_29982); it used to fail with `no such algorithm: EC for provider BC`.

## Release 2.0.0-rc1

### added
- Each dispatch logs its outcome once: `dispatched` when every device was reached, `failed` when
  none was, `partially_rejected` in between. Not persisted yet; the writeback to the stored
  notification is deferred to a follow-up ticket.
- `push-gateway.batch.max-count` (MAX_BATCH_COUNT, default `100`) and `push-gateway.batch.max-time`
  (MAX_BATCH_TIME, default `PT1S`) for the encrypted batches, gemF_PushNotification A_28212 to
  A_28217. A value above `100` or `PT10S` fails the boot.
- Set up export of logs, metrics, and traces using OTLP. (A_27492-02, A_26988)

### changed
- The Push Gateway client picks the JVM proxy by the target's scheme: `https://` targets read
  `https.proxyHost`, `https.proxyPort` and `https.nonProxyHosts` (not the JDK's shared
  `http.nonProxyHosts`), `http://` targets the `http.*` ones. Before, an `https://` target without
  `push-gateway.trusted-ca-paths` or mTLS used the `http.*` ones. The released native image does not
  translate `HTTPS_PROXY` / `HTTP_PROXY`: pass the properties as `-D` arguments to `./application`,
  or set `QUARKUS_PROXY_HOST`, `QUARKUS_PROXY_PORT` and `QUARKUS_PROXY_NON_PROXY_HOSTS`, which apply
  to both schemes.
- TLS towards the Push Gateway is pinned for gemSpec_Krypt (A_29991): the client offers only TLS 1.2
  and 1.3, the ECDHE-GCM suites (ECDSA first) plus TLS 1.3 AES-GCM, and only the secp256r1 and
  secp384r1 groups. The gateway's leaf RSA key must have at least 3000 bits, so **a Push Gateway with
  an RSA-2048 certificate now fails the handshake**.
- DEKs, the pseudonymization key and the GCM IVs come from a pinned SP 800-90A DRBG (256-bit)
  instead of the platform's default `SecureRandom` (GS-A_4367 / GS-A_4368).
- `push-gateway.retry.max-attempts` changes its meaning: it now caps how often an item a Push
  Gateway response reports `failed` is sent (default `3`); such items are retried without their
  rejected devices. A value below `1` fails the boot instead of being raised to `1`. Transport errors
  (5xx, 429, connection failures) no longer count against it: they are retried with exponential
  back-off until the new `push-gateway.retry.give-up-after` has passed (default `PT1H`, shorter than
  `notification.persistence.retention` or the boot fails), with the wait capped by the new
  `push-gateway.retry.max-delay` (default `PT5M`) (A_27166).
- The batches for one Push Gateway are sent concurrently, so a later batch no longer waits for the
  answer to an earlier one.
- A pushkey the Push Gateway rejects is now removed from **every** pusher of the notified user,
  whatever app id registered it and whichever dispatch the answer belongs to (A_27374), instead of
  only from the devices of the batch that was just sent.
- Two contract questions are answered in the README under "Current limitations" instead of being
  carried as open gaps: why `reference` is not forwarded on the plain dispatch path
  (`PlainNotification` has no `identifier` field, gemF_PushNotification A_27532), and why the
  dispatch queue stays in-process rather than buffering until delivery (A_27166).
- `POST /pushers/set` now honours `append`. Without it (the contract's default `append: false`),
  registering an app ID and pushkey removes that pusher from every other user who had registered it,
  so a device that changes accounts stops receiving the previous account's notifications.
  `append: true` keeps the other users' registrations, as before. Pushers registered before this
  release are not yet known to the lookup that finds other users, and are removed only after their
  owner registers them again.
- While `notification.history.enabled` is off, both `/history/*` endpoints answer
  `404 HISTORY_DISABLED` (A_29974). `GET /history/{notification_id}` used to answer
  `404 NOTIFICATION_NOT_FOUND`, `GET /history/device/{pushkey}` `200` with no messages. Behind the
  ZETA Guard PEP a client still gets `403`, since the `notification.history.read` scope is not
  issued while the history is off; see "Current limitations" in the README.
- The RS API checks `id_type` against the value: a KVNR-shaped value declared as `telematik-id`, or
  the reverse, is answered with `400 M_INVALID_PARAM` (A_29967). Neither that answer nor
  `404 USER_NOT_FOUND` echoes the identifier any more.
- Deregistering a pusher, a pushkey the Push Gateway rejects, and a pusher taken over by another
  user without `append` now delete the device's message history once the user holds that pushkey
  under no other app ID; registering a pushkey the user does not hold yet starts with an empty
  history (A_29989).
- Added trace information to logs. (A_28796)
- Redacted decrypted notifications in error logs. (A_29990)

### fixed
- A password-protected mTLS client key (`push-gateway.mtls.client-key-password`) made the service
  fail to start with `UnrecoverableKeyException`.

## Release 1.3.2

### added
- Support for every database Keycloak supports — PostgreSQL, MariaDB, MySQL, Microsoft SQL Server
  and Oracle. A single image carries all five drivers, and the engine is selected at deploy time by
  the JDBC url alone: `QUARKUS_DATASOURCE_JDBC_URL` / `_USERNAME` / `_PASSWORD` as before, with the
  engine taken from the url's prefix. Only an ambiguous url needs
  `NOTIFICATION_DATASOURCE_DB_KIND` (`postgresql`, `mariadb`, `mysql`, `mssql`, `oracle`) — a
  MariaDB server reached through a `jdbc:mysql:` url, above all. See "Supported databases" in the
  README.

### changed
- An existing PostgreSQL deployment needs no configuration change: its
  `QUARKUS_DATASOURCE_JDBC_URL` / `_USERNAME` / `_PASSWORD` keep working unchanged, and
  `jdbc:postgresql:` identifies the engine on its own. Schema migration `V2` converts the table in
  place on first start. The per-engine keys (`QUARKUS_DATASOURCE_POSTGRESQL_*`) are also accepted
  and take precedence, should a deployment prefer to be explicit.
- The scheduled purge now claims a lease with an ordinary compare-and-set write instead of taking a
  PostgreSQL advisory lock. Running several instances remains safe.

## Release 1.3.0 / Release 1.3.1

Initial release of the ZETA Notification Service.

### added
- RS-facing API: resource servers can submit push notifications for delivery.
- FdV-facing channel management API: registration and management of push channels.
- Dispatch (Versand) engine: forwards notifications to the push gateway (APNs/FCM), with exponential backoff for failed push gateway connections.
- Cleanup of sent push notifications, their idempotency data, and invalid pushers.
- Persistent storage on PostgreSQL for notifications, delivery history and idempotency, with Flyway-managed schema migrations.
- Split run modes selectable at build time (both APIs, RS-only, FdV-only) via `notification.api.rs.enabled` / `notification.api.fdv.enabled`.
- Support for configuring additional trusted root CAs and PEM certs for use in mTLS towards the push gateway.
- Health endpoints via SmallRye Health.
- Acceptance test coverage based on the gematik acceptance criteria (AFOs).
- Local development setup with Docker Compose and a mocked push gateway.
- Tagged release pipeline: native-image container images are built, scanned (Trivy) and signed on tag push.

## Release 0.1.2
## Release 1.3.0-rc1

### added
- Support for configuring PEM certs for use in mTLS towards the push gateway
- Exponential backoff for failed push gateway connections

### fixed
- Cleanup of sent push notifications and their idempotency data
- Remove invalid pushers

## Release 0.1.1

### added
- Support for configuring additional trusted root CAs.

### fixed
- Correct decoding of the `zeta-user-info` header.

### removed
- Dropped the `pg_cron` dependency.

## Release 0.1.0

Initial release of the ZETA Notification Service.

### added
- RS-facing API: resource servers can submit push notifications for delivery.
- FdV-facing channel management API: registration and management of push channels.
- Dispatch (Versand) engine: forwards notifications to the push gateway (APNs/FCM).
- Persistent storage on PostgreSQL for notifications, delivery history and idempotency, with Flyway-managed schema migrations.
- Split run modes selectable at build time (both APIs, RS-only, FdV-only) via `notification.api.rs.enabled` / `notification.api.fdv.enabled`.
- Health endpoints via SmallRye Health.
- Local development setup with Docker Compose and a mocked push gateway.
- Tagged release pipeline: native-image container images are built, scanned (Trivy) and signed on tag push.
