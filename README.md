<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png" alt="Gematik Logo"/> <br/>

# notification-service

Implementation of the **ZETA Notification Service** — the push facade in front of the gematik
Push Gateway for TI 2.0 services.

A resource server (Fachdienst backend) hands the service a notification for a user and a channel.
The service resolves which of that user's devices have that channel enabled, answers `202` right
away, and forwards the push to the Push Gateway asynchronously. Client devices reach the same
service through the ZETA Client SDK to register their push targets, switch channels on and off,
and read their notification history.

**Stack:** Kotlin 2.4 · Quarkus 3.37 · JDK 25 · PostgreSQL / MariaDB / MySQL / SQL Server / Oracle · Gradle

## Contents

- [What it does](#what-it-does)
- [Getting started](#getting-started)
- [API surface](#api-surface)
- [Authentication](#authentication)
- [Configuration](#configuration)
- [Development](#development)
- [Container images and deployment](#container-images-and-deployment)
- [Releasing](#releasing)
- [Current limitations](#current-limitations)
- [License](#license)

## What it does

Inbound, the service is called by two kinds of client: resource servers submitting notifications,
and devices managing their own push targets and channels. Outbound, it talks to exactly two
systems — the gematik Push Gateway, and a relational database holding pushers, channel
configuration, notifications and delivery history. It never reaches APNs or FCM itself; fanning a
push out to the platform services is the Push Gateway's job.

The service exposes two independent inbound APIs — an **RS-facing API** for resource servers and an
**FdV-facing API** for client devices — over one HTTP port. Which of them a given image serves is
fixed [at build time](#api-mode-build-time).

### Submit path (RS API)

1. `POST /notifications` is checked against the static channel registry and the user's registered
   pushers.
2. The service answers `202 Accepted` with a `notification_id` and a status, **before** the Push
   Gateway has been called:
   - `accepted` — at least one device has the channel enabled; the notification is persisted and
     queued for dispatch.
   - `no_active_channel` — the user exists, but no device has this channel enabled. Nothing is
     persisted and nothing is sent.
3. A worker pool drains the in-process dispatch queue and calls the Push Gateway, retrying
   transient failures and the items the Push Gateway reports `failed` with exponential back-off
   until `push-gateway.retry.give-up-after` has passed. Each dispatch ends with one log line
   carrying its outcome — `dispatched` when every device was reached, `failed` when none was,
   `partially_rejected` in between. The outcome is not persisted yet; the writeback to the stored
   notification is deferred to a follow-up ticket. The `202` receipt the resource server already
   holds never changes, and a retransmission with the same `Idempotency-Key` still replays it
   verbatim.

An optional `Idempotency-Key` header makes retransmissions safe: a repeat submission returns the
original receipt without dispatching a second push.

### Management path (FdV API)

Devices register a *pusher* (their push target incl. the Push Gateway URL), toggle channels per
device, and read the notification history for a device. Every operation is bound to the
authenticated subject — a device can only ever see and change its own user's data. An operation on
another user's pusher or pushkey is rejected with `403 M_FORBIDDEN` (A_29978); see "Current
limitations" for what that means for `append` and for pushers registered by an earlier release.

## Getting started

### Prerequisites

- **JDK 25**
- **Docker** — required, not optional: PostgreSQL is provisioned by Quarkus Dev Services for
  `quarkusDev`, the test suite starts its database in a testcontainer, and the local stack runs on
  Docker Compose.

### Run the local stack (recommended)

Brings up the service (JVM image), a PostgreSQL 17 database, a WireMock container standing in for
the Push Gateway, and the HSM proxy simulator that at-rest sealing runs against — its image sits in a
private registry, so `docker login registry.tas-devtools-gitlab.spree.de` once beforehand.
`Dockerfile.jvm` only packages the pre-built application, so build first:

```shell script
./gradlew build
docker compose up --build
```

| What                          | Where                                                   |
|-------------------------------|---------------------------------------------------------|
| Service                       | <http://localhost:8080>                                 |
| Health checks                 | <http://localhost:8080/q/health>                        |
| Remote debug (JDWP)           | `localhost:5005`                                        |
| Push Gateway stub (admin API) | <http://localhost:8081/__admin/requests>                |
| PostgreSQL                    | `localhost:5432`, database/user/password `notification` |
| HSM proxy simulator (gRPC)    | `localhost:50051`                                       |

Push Gateway stub mappings live in `local-dev/push-gateway/mappings/`. A pusher's `data.url` must
match the allowed base URL exactly, trailing slash included: `http://push-gateway:8080/push/v1/`.

To run the same stack against another engine, start its profile and point the same three variables
at it — for example Oracle:

```shell script
NS_DB_URL=jdbc:oracle:thin:@oracle:1521/freepdb1 \
NS_DB_USER=notification NS_DB_PASSWORD=notification \
  docker compose --profile oracle up --build
```

The service waits for whichever engine's profile is enabled before it boots, since Flyway migrates
at startup.

`mariadb` and `mysql` work the same way; see `docker-compose.yaml` for each one's port and
credentials. SQL Server needs two extras in its url — `encrypt=false`, because mssql-jdbc encrypts
by default and validates the certificate that the dev container self-signs, and an explicit
`databaseName`, because the database is created by the `mssql-init` companion rather than by the
image:

```shell script
NS_DB_URL='jdbc:sqlserver://mssql:1433;databaseName=notification;encrypt=false' \
NS_DB_USER=sa NS_DB_PASSWORD='Notification1!' \
  docker compose --profile mssql up --build
```

### Verify the setup

With the stack running, [`local-dev/e2e-demo.sh`](local-dev/e2e-demo.sh) walks the acceptance
criteria end to end — channel aggregation, async `202`, idempotency, retry behaviour, error
mapping — and prints a pass/fail summary:

```shell script
./local-dev/e2e-demo.sh
```

### Run in dev mode

Live reload, with Dev Services starting PostgreSQL automatically. The `%dev` profile ships an
allowed Push Gateway URL and a channel registry, so no extra configuration is needed:

```shell script
./gradlew quarkusDev
```

## API surface

Both APIs are generated from the OpenAPI contracts under [`docs/`](docs/) — the generated models are
compiled into the build, so a spec change that breaks the implementation breaks the build. The
contracts are the endpoint reference:

- [`notification_service_rs_endpoint.yaml`](docs/notification_service_rs_endpoint.yaml) — **RS API:**
  submit a notification, read a user's active channels.
- [`openApi_notification_fachdienst.yaml`](docs/openApi_notification_fachdienst.yaml) — **FdV API:**
  manage pushers, configure channels per device, read notification history.

All three files, the Push Gateway contract included, are copies of gematik specifications;
[`docs/README.md`](docs/README.md) names the official source and version of each and lists the
local deviations.

All errors share one body shape — `{"errorCode": "...", "errorDetail": "..."}`, with the codes
listed per endpoint in the contracts. Error details are deliberately kept free of request-body
values, so device tokens and shared secrets stay out of responses and logs.

Health and readiness are served by SmallRye Health at `/q/health`, `/q/health/live` and
`/q/health/ready`.

## Authentication

The service runs behind the ZETA API Gateway / PEP. Token validation — signature, expiry, issuer,
audience, DPoP proof-of-possession, scopes — happens upstream. By the time a request arrives here,
the caller's identity is already established, and the gateway forwards it in the **`zeta-user-info`
header**: base64url-encoded JSON carrying at least an `identifier`.

The service therefore only:

1. Requires that header on the FdV paths (`/pushers`, `/channels`, `/history` and their
   sub-paths) and answers `401` when it is missing or unusable.
2. Reads `identifier` from it and binds every business operation to that subject.

The RS paths (`/notifications`, `/users/{userId}/channels`) require a client certificate from the trust
store (mTLS, A_29980); without one the answer is `401`.

| Key                                              | Meaning                                                                           |
|--------------------------------------------------|-----------------------------------------------------------------------------------|
| `quarkus.tls.key-store.pem.<name>.cert` / `.key` | Server certificate and key; enables HTTPS on `8443`.                              |
| `quarkus.tls.trust-store.pem.certs`              | The resource server's certificate or CA; required, or the service does not start. |
| `quarkus.http.auth.permission.rs.enabled`        | `false` switches the check off, e.g. when mTLS is terminated upstream.            |

Certificates are managed by the operators (A_29981). One instance serves one Fachdienst, so every trusted
resource server may use every configured channel (A_29983).

See `GatewayAuthenticationMechanism` and `GatewayIdentityProvider` under
`adapter/inbound/security`; the header name is configurable via `app.auth.user-info-header`.

## Configuration

Standard Quarkus configuration: YAML in `application.yaml`, overridable by environment variables
(`push-gateway.allowed-base-urls` → `PUSH_GATEWAY_ALLOWED_BASE_URLS`).

### Required in every deployment


| Key                                                       | Meaning                                                                                                                                                                                                  |
|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `notification.channels.allowed`                           | The static channel registry — there is no runtime admin API. Has **no default**: a missing value fails the boot rather than producing blanket errors later.                                              |
| `push-gateway.allowed-base-urls`                          | Allow-list of Push Gateway base URLs. A pusher's `data.url` must match one of them exactly; anything else is rejected as `UntrustedTarget` before any call is made. Unset/empty → nothing is dispatched. |
| `quarkus.datasource.jdbc.url` / `.username` / `.password` | Database connection. The engine follows from the url's prefix — see [Supported databases](#supported-databases). Flyway migrates the schema at startup.                                                  |
| `notification.datasource.db-kind`                         | Optional; the engine, as one of `postgresql`, `mariadb`, `mysql`, `mssql`, `oracle`. Needed only when the url does not identify it — a MariaDB server reached through a `jdbc:mysql:` url, above all.    |

```yaml
notification:
  channels:
    allowed:
      - epa.documents.new
push-gateway:
  allowed-base-urls: https://push-gateway.example/push/v1/
quarkus:
  datasource:
    jdbc:
      url: jdbc:postgresql://postgresql:5432/notification
    username: notification
    password: notification
```

## Supported databases

The service runs on every engine Keycloak supports, so it can share a deployment's existing
database rather than requiring one of its own:

| Engine                                             | `db-kind`    | Inferred from      | Versions                      |
|----------------------------------------------------|--------------|--------------------|-------------------------------|
| PostgreSQL (incl. Aurora PostgreSQL, EnterpriseDB) | `postgresql` | `jdbc:postgresql:` | 14.x – 18.x                   |
| MariaDB                                            | `mariadb`    | `jdbc:mariadb:`    | 10.6, 10.11, 11.4, 11.8 (LTS) |
| MySQL                                              | `mysql`      | `jdbc:mysql:`      | 8.0, 8.4 (LTS)                |
| Microsoft SQL Server (incl. Azure SQL)             | `mssql`      | `jdbc:sqlserver:`  | 2019, 2022                    |
| Oracle Database                                    | `oracle`     | `jdbc:oracle:`     | 19c (19.3+), 23.x (23.5+)     |

**One image serves all five.** Every driver is compiled in, because Quarkus fixes
`quarkus.datasource.db-kind` at build time. A deployment nevertheless configures its database the
way it would for a single-engine service — the engine comes from the url:

```shell script
QUARKUS_DATASOURCE_JDBC_URL=jdbc:oracle:thin:@db:1521/freepdb1
QUARKUS_DATASOURCE_USERNAME=notification
QUARKUS_DATASOURCE_PASSWORD=...
```

Only an ambiguous url needs the engine spelled out, and `jdbc:mysql:` is the one that matters: it
infers MySQL, so a MariaDB server behind such a url has to say `NOTIFICATION_DATASOURCE_DB_KIND=mariadb`
or it will be handed the MySQL driver and the MySQL migrations.

Internally each engine is a *named* datasource, and exactly one of them ends up with a url; the
other four deactivate themselves and their schema migrations are skipped along with them. Those
per-engine keys can also be set directly — `QUARKUS_DATASOURCE_ORACLE_JDBC_URL` and friends — and
take precedence over the engine-agnostic ones. Configuring two engines that way fails the boot,
which is the intended behaviour: it is ambiguous.

The SQL the service issues is vendor-neutral and identical on all five; only the schema migrations
under `src/main/resources/db/migration/<engine>/` differ. The storage contract runs against all five
engines on every `./gradlew :notification-service:test`, locally and in CI alike — there is no flag
to remember and no engine that only gets exercised on some branches.

### Push Gateway connection

The Push Gateway is an external component and is reached **directly over the internet**, not
through the service mesh — mesh mTLS is reserved for internal ZETA Guard traffic.

| Key                                         | Default | Meaning                                                                                                                                                                                                                                                                                             |
|---------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `push-gateway.connect-timeout`              | `PT3S`  | Connection timeout                                                                                                                                                                                                                                                                                  |
| `push-gateway.read-timeout`                 | `PT10S` | Read timeout                                                                                                                                                                                                                                                                                        |
| `push-gateway.retry.give-up-after`          | `PT1H`  | How long after its acceptance a notification is still retried. Retried are 5xx, 429 and connection failures, and, up to `max-attempts`, the items a response reports `failed` (without their rejected devices). Must be shorter than `notification.persistence.retention`, otherwise the boot fails |
| `push-gateway.retry.initial-delay`          | `PT1S`  | First back-off delay; doubles with every further attempt                                                                                                                                                                                                                                            |
| `push-gateway.retry.max-delay`              | `PT5M`  | Longest back-off delay; the doubling stops there                                                                                                                                                                                                                                                    |
| `push-gateway.retry.max-attempts`           | `3`     | How often an item a response reports `failed` is sent at most. Transport errors do not count; they are retried until `give-up-after`. Below `1` the boot fails. Up to 1.3.2 this key capped the attempts on transport errors instead                                                                |
| `push-gateway.batch.max-count`              | `100`   | MAX_BATCH_COUNT: most push notifications per call on the encrypted path; above `100` the boot fails (A_28215–A_28217)                                                                                                                                                                               |
| `push-gateway.batch.max-time`               | `PT1S`  | MAX_BATCH_TIME: longest a push notification may wait for its batch; outside `PT0S`–`PT10S` the boot fails. Batches are never held back to fill up and the batches for one Push Gateway are sent concurrently, so every push notification leaves within it (A_28212–A_28214)                         |
| `push-gateway.trusted-ca-paths`             | —       | PEM CA files trusted *in addition to* the system trust anchors, for targets signed by a CA the default trust store does not know. Leave empty in production.                                                                                                                                        |
| `push-gateway.mtls.client-certificate-path` | —       | Client certificate (PEM) for mTLS towards the Push Gateway                                                                                                                                                                                                                                          |
| `push-gateway.mtls.client-key-path`         | —       | Client key (PEM); required together with the certificate — setting only one fails the boot.                                                                                                                                                                                                         |
| `push-gateway.mtls.client-key-password`     | —       | Password, if the key is encrypted                                                                                                                                                                                                                                                                   |

Hostname verification stays on in all cases.

### Persistence and retention

| Key                                       | Default | Meaning                                                                                                                                                                                                     |
|-------------------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `notification.persistence.retention`      | `PT24H` | TTL for notification processing data — notifications and idempotency receipts                                                                                                                               |
| `notification.persistence.purge.interval` | `5m`    | How often expired records are deleted. A purge first claims a lease (an ordinary compare-and-set write on a reserved row), so running several instances is safe: only one of them does the work each cycle. |
| `notification.history.enabled`            | `false` | Whether the device notification history is written and served                                                                                                                                               |
| `notification.history.retention`          | `P30D`  | How long a device's message history is kept, counted from its first entry (A_29988)                                                                                                                         |

### Data protection

A_29957 allows HSM-backed at-rest encryption and pseudonymization **only inside a VAU** (variant A) —
that is what the settings below configure. Without a VAU (variant B) they must stay off, which makes
that variant permissible only where no KVNR / Telematik-ID is persisted. See "Identifier handling" in
[`notification_service_rs_endpoint.yaml`](docs/notification_service_rs_endpoint.yaml).

| Key                                                  | Default           | Meaning                                                                                                                                                                                                                                                                                                                                                            |
|------------------------------------------------------|-------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `notification.persistence.sealing.enabled`           | `false`           | Payload encryption plus HMAC pseudonymization of storage ids. Off by default and in the `dev` profile so `quarkusDev` runs without an HSM. **Switch it on in a Variant (A) deployment; leave it off in Variant (B)**, where A_29957 does not allow it.                                                                                                             |
| `notification.persistence.sealing.keychain`          | –                 | The keychain, HSM-wrapped, as a JSON array: `[{"id":"notification-service-keychain-v1","kekId":"notification-service-keychain-kek-aesgcm-v1","activeFrom":"2025-01-01T00:00:00Z","keys":"<base64 of iv ‖ ciphertext ‖ tag>"}]`. Required once sealing is on; supply it from a Kubernetes secret as `NOTIFICATION_PERSISTENCE_SEALING_KEYCHAIN`. See "Record keys". |
| `notification.persistence.sealing.keychain-file`     | –                 | Path of a file holding the same JSON array, as an alternative to `notification.persistence.sealing.keychain`, e.g. written by the keychain generator as an init container into a volume the service mounts. Read once, on first use. Set one of the two, not both.                                                                                                 |
| `notification.persistence.sealing.rotation-interval` | `1h`              | How often the rotation job moves the records of older keychain entries to the current one. The first run starts one minute after boot. See "Key rotation".                                                                                                                                                                                                         |
| `hsm-proxy.endpoint`                                 | `localhost:50051` | `host:port` of the HSM proxy, from `HSM_PROXY_ENDPOINT`.                                                                                                                                                                                                                                                                                                           |
| `hsm-proxy.plain-text-accepted`                      | `false`           | Allows a sealing deployment over an unauthenticated gRPC channel. Implicit in `dev`/`test`, elsewhere the boot fails without it.                                                                                                                                                                                                                                   |

The connection is the `HsmProxyGrpcClient` of the `java-hsm-proxy-provider` artifact, pointed at
`hsm-proxy.endpoint` / `HSM_PROXY_ENDPOINT`. That client speaks **plain-text gRPC only** — its mTLS
support (A_28830) is deferred upstream — so a sealing deployment outside `dev`/`test` boots only with
`hsm-proxy.plain-text-accepted=true`, and the channel has to be protected by the network around it
until the provider gains TLS.

#### Record keys

Records are sealed, and their storage ids derived, with the keys of a keychain. Each entry holds two
independent 32-byte keys: a DEK, from which every seal derives its record key, and a pseudonymization
key, which derives the storage ids. Both are generated inside the VAU, wrapped together by the HSM under
the entry's KEK (`Encrypt`, with the entry id as associated data) and handed to the service in
`notification.persistence.sealing.keychain`, or as a file named by
`notification.persistence.sealing.keychain-file`; neither key is stored in the clear. An instance
unwraps every entry once, on first use, and holds the keys in memory only, overwritten on shutdown
(A_29958). In regular operation a request makes no call to the HSM.

```json
{
  "id": "notification-service-keychain-v2",
  "kekId": "notification-service-keychain-kek-aesgcm-v1",
  "activeFrom": "2026-11-01T00:00:00Z",
  "emergency": true,
  "keys": "<base64 of iv ‖ ciphertext ‖ tag>"
}
```

`emergency` is present only on an emergency entry (see "Key rotation"). For one, the associated data
is the entry id followed by `/emergency`, so the flag cannot be added or removed afterwards without
the entry failing to unwrap.

A new record goes under the newest entry whose `activeFrom` has passed: its storage id is the
HMAC-SHA256 of its logical id under that entry's pseudonymization key (A_29960), and its payload is
sealed under that entry's DEK. The record names the entry in its `keychain_id` column, and an update
keeps it under that entry, so a record needs exactly the one entry it names. The `kvnr:` or `tid:`
label inside the MAC keeps the two identifier types apart (A_29961). The record key is HKDF-Expand
(RFC 5869, SHA-256) of the DEK with the info `notification-service record key` ‖ salt, the salt
being 32 fresh random bytes, so no two seals share a key and the DEK itself never encrypts anything.
The stored payload is `base64(salt ‖ iv ‖ ciphertext ‖ tag)`, with the storage id as associated data;
it carries the record's logical id in an `_lid` field, which is how the rotation job computes the new
storage id.

A lookup tries the storage id under every entry, oldest first, so every entry costs one database read
per lookup; remove an entry once no record is under it any more (see "Key rotation"). A deletion
removes the record under every entry.

Entry ids differ only in their `-v<N>`; sorted by version, `activeFrom` has to rise. Every `kekId`
follows the A_28831 scheme, and every entry unwraps to 64 bytes. With sealing on, the readiness check
unwraps the whole keychain: an instance whose keychain breaks one of these rules, is missing, or holds
an entry the HSM does not unwrap stays out of service, so a rollout stops at its first instance. So
does an instance that finds a live record under an entry its keychain does not hold; it looks at the
records with one query over the table until they are all readable once, and not again after that. An
instance that holds its keys stays ready while the HSM proxy is unreachable.

The keychain generator (module `keychain-generator`, image `$CI_REGISTRY_IMAGE/keychain-generator`)
creates entries. It draws both keys from an SP 800-90A DRBG (GS-A_4367), has the HSM proxy wrap them
under the KEK (`Encrypt`, with the associated data above), overwrites them, and writes the keychain
with the new entry appended. Only the wrapped form ever leaves the process, so the output can go
straight into the keychain secret; the unwrapped keys exist only inside the generator's container,
which therefore has to run as an attested component inside the VAU (A_29962). Grant it `Encrypt` on
the KEK only for the duration of the procedure, in the four-eyes principle, and revoke the grant
afterwards (A_28830); it never needs `Decrypt`, so it cannot unwrap an existing entry. Like the
service, it reaches the HSM proxy over plain-text gRPC only (see above), so the network has to protect
that channel.

It is configured by environment only:

| Variable               | Meaning                                                                                                                                                                                                          |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `HSM_PROXY_ENDPOINT`   | `host:port` of the HSM proxy, default `localhost:50051`.                                                                                                                                                         |
| `HSM_PROXY_KEK_ID`     | The KEK to wrap under, e.g. `notification-service-keychain-kek-aesgcm-v1`. Required for a new keychain; unset when extending one, the KEK of the newest entry.                                                   |
| `KEYCHAIN_INPUT_FILE`  | Optional: the current keychain, to which the entry is appended. Unset or empty, a new keychain is created.                                                                                                       |
| `KEYCHAIN_ENTRY_ID`    | Optional: id of the new entry, which must not be in the keychain yet. Unset, `notification-service-keychain-v1` for a new keychain and the next version of the newest entry otherwise, e.g. `…-v2` after `…-v1`. |
| `KEYCHAIN_ACTIVE_FROM` | UTC instant ending in `Z` from which the entry takes over the writes, e.g. `2026-11-01T00:00:00Z`. Optional for a new keychain, where it defaults to now; required when extending one.                           |
| `KEYCHAIN_EMERGENCY`   | Optional: `true` makes the entry an emergency entry (see "Key rotation"), `false` or unset a regular one. Anything else is an error.                                                                             |
| `KEYCHAIN_OUTPUT_FILE` | Required: where to write the resulting keychain. If the file exists already, nothing is done.                                                                                                                    |

The generator checks `KEYCHAIN_OUTPUT_FILE` first, before calling the HSM: unset is an error, a directory
there ends it with exit code 2, and an existing file means there is nothing to do, so it leaves the file as
it is and ends with exit code 0. That is the behavior of the keychain generator of the Keycloak integrity
provider, and it lets the generator run as an init container on every pod start: it creates the keychain
once and leaves it alone afterwards. A rotation therefore needs a new output file, e.g. a separate run with
the current keychain as input. Missing values are reported together, before the HSM is called. Beyond that
it checks the id scheme and that `activeFrom` is an instant, not whether it is plausible: choosing it for a
rotation is the operator's part (see "Key rotation" below), which is why it has no default there. A new
keychain needs only `HSM_PROXY_KEK_ID` and `KEYCHAIN_OUTPUT_FILE`: its first entry may take over at once,
since no instance holds an older one. Diagnostics go to stderr, and the exit code is non-zero on any error.

```shell
docker run --rm \
  -e HSM_PROXY_ENDPOINT=hsm-proxy:50051 \
  -e HSM_PROXY_KEK_ID=notification-service-keychain-kek-aesgcm-v1 \
  -e KEYCHAIN_ACTIVE_FROM=2026-11-01T00:00:00Z \
  -e KEYCHAIN_INPUT_FILE=/keychain/current.json \
  -e KEYCHAIN_OUTPUT_FILE=/keychain/next.json \
  -v "$PWD/keychain:/keychain" \
  --user "$(id -u):$(id -g)" \
  $CI_REGISTRY_IMAGE/keychain-generator:<version>
```

#### Key rotation (A_29963, A_29960)

A rotation is a new keychain entry, which brings a new DEK and a new pseudonymization key at once, and
a new KEK if the entry is wrapped under one. An entry is never re-wrapped under another KEK, because
after a suspected compromise of a KEK the keys it wrapped count as compromised too.

A new entry takes over the writes at its `activeFrom`, by the clock of each instance. Around that
instant an instance whose clock lags a little may create a record under the old entry once more, which
the job then moves. Should two instances create the same record at that moment, one under each entry,
the copy under the older entry wins, because a lookup tries it first, and the other is lost.

A background job, one minute after boot and then every `notification.persistence.sealing.rotation-interval`,
moves the records of older entries to the current one: it gives each the storage id of the current
pseudonymization key, seals it under the current DEK and replaces the old row in one database
transaction. Records with a retention period (notifications, idempotency receipts, device histories)
stay under their entry until they expire, unless the current entry is an emergency entry: then they
move too and keep their expiry. Only a higher `-v<N>` counts as newer, so the job never moves a record
to an older entry. A record that fails is logged with its pseudonymized storage id and retried on the
next run; after ten failures in a row the run stops and the next one starts over, as it does after a
database error.

In the procedure below, "every instance" means every instance on the database, including a second
deployment such as the RS-only and the FdV-only variant side by side: take each step on all of them
before the next one.

Rotating the keys, regularly or after a suspected compromise of a key or a KEK:

1. For a new KEK, bring it into the HSM first under the next version, e.g.
   `notification-service-keychain-kek-aesgcm-v2`, in the four-eyes principle, grant the service
   `Decrypt` on it (A_28830), and keep the old KEK. Create the next entry with the keychain generator
   (the current keychain as `KEYCHAIN_INPUT_FILE`, `HSM_PROXY_KEK_ID=<KEK id>` for a new KEK; the entry
   id follows as the next version), choosing an `activeFrom` by which every instance will have
   restarted, the next day say, and put the resulting keychain into the keychain secret. It keeps the
   old entry. After a suspected compromise, set `KEYCHAIN_EMERGENCY=true` and `activeFrom` to right
   after the restart of step 2.
2. Roll the deployment. The rollout completes only once every new instance has unwrapped the whole
   keychain, so every instance can now read records under the new entry before any is written.
3. From `activeFrom` on every new record goes under the new entry, and the job moves the rest. Watch
   its log line `Key rotation: … live records per entry: {…}`. Without an emergency entry, records with
   a retention period hold the old entry until they expire: notifications and idempotency receipts for
   `notification.persistence.retention`, device histories for up to `notification.history.retention`.
4. Once the line lists only the new entry, remove the old entry from the secret and roll the deployment
   again, which drops its keys from every instance's memory (A_29958). Not before: a record still under
   it would no longer be found, and the readiness check keeps the restarted instances out of service
   while such a record exists, so the rollout stops at its first instance. Once no entry names the old
   KEK any more, retire it in the HSM, again in the four-eyes principle.

The gRPC contract and its client come from the
`de.gematik.zeta.zeta-guard:java-hsm-proxy-provider` artifact, pinned in `gradle/libs.versions.toml`
and resolved from the GitLab group registry declared as the `gitlab` repository in
`notification-service/build.gradle.kts`. CI authenticates with `CI_JOB_TOKEN`; locally put a
read-registry token into the `gitLabPrivateToken` Gradle property (`~/.gradle/gradle.properties`) or
install the artifact into the local Maven repository. Bump the version there instead of vendoring
the proto.

The artifact is a shaded uber-jar that also bundles kotlin-stdlib, protobuf, guava and
grpc-netty-shaded unrelocated. As published it breaks the packaged application — its older
kotlin-stdlib shadows the application's own, and its shaded netty carries no native-image metadata,
so the native binary finds no gRPC channel provider. `repackageHsmProxyProvider` therefore reduces
the jar to the provider's own classes, and `quarkus-grpc` supplies the gRPC runtime instead. Drop
both once the provider publishes a thin or relocated artifact. `quarkus-grpc` is present for that
runtime only; the channel comes from the provider's client, so `quarkus.grpc.clients.*` has no
effect.

### Encrypted push

| Key                               | Default | Meaning                                                                                                                                                                                                              |
|-----------------------------------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `notification.encryption.enabled` | `false` | Whether push notifications leave for the Push Gateway end-to-end encrypted (A_29968). The decision belongs to the deployment, never to the resource server — **ePA and E-Rezept instances have to run with `true`**. |

With it on, a pusher registration must carry the `encryption` block, devices without key material
are skipped rather than sent in the clear, and a payload above 1018 serialized bytes is rejected
with `413 PAYLOAD_TOO_LARGE`. With it off the block is ignored.

### Dispatch

`notification.dispatch.concurrency` (`4`) sizes the worker pool draining the queue,
`notification.dispatch.queue-capacity` (`10000`) the in-memory queue itself. A full queue makes
submissions fail with `503 SERVICE_UNAVAILABLE`.

A pushkey in the `rejected` list of a Push Gateway response is deregistered at once, from **every**
pusher of that user, whatever app id registered it and whichever dispatch it came from (A_27374).

### Overload protection (A_29987)

- Every `503 SERVICE_UNAVAILABLE` carries `Retry-After: 5`.
- A `payload` above 3072 bytes (1018 with encrypted push) is rejected with `413 PAYLOAD_TOO_LARGE`.
- The RS endpoints can be rate-limited per endpoint and instance (off by default):
  `QUARKUS_FAULT_TOLERANCE_GLOBAL_RATE_LIMIT_ENABLED=true` admits `_VALUE` calls (default `1000`) per
  `_WINDOW` `_WINDOW_UNIT` (default 1 second). Above that: `429 RATE_LIMITED` with `Retry-After`.

### API mode (build-time)

Which inbound APIs are compiled into the image is decided **at build time**:

| Key                            | Default | Meaning                                   |
|--------------------------------|---------|-------------------------------------------|
| `notification.api.rs.enabled`  | `true`  | Compile the RS-facing API into the image  |
| `notification.api.fdv.enabled` | `true`  | Compile the FdV-facing API into the image |

These are plain (non-`quarkus.`) build-time properties, so the Quarkus Gradle plugin does not
forward them as `-D` flags — set them as environment variables:

```shell script
NOTIFICATION_API_FDV_ENABLED=false ./gradlew build
```

## Development

### Build and test

```shell script
./gradlew build                                 # compile, generate OpenAPI sources, test
./gradlew :notification-service:test            # tests only (Docker required)
./gradlew :notification-service:jacocoTestReport
./gradlew :keychain-generator:installDist       # keychain generator distribution, see "Record keys"
```

### Static analysis

Formatting is enforced with Spotless (ktlint), code smells with detekt.

```shell script
./gradlew :notification-service:spotlessApply   # run formatter
./gradlew :notification-service:detekt          # static analysis
./gradlew :notification-service:detektBaseline  # refresh baseline after intended changes
./gradlew :keychain-generator:spotlessApply :keychain-generator:detekt   # same for the generator
```

Violations introduced intentionally go into `config/detekt/baseline.xml` via `detektBaseline`; new
violations still fail the build.

### Project layout

```
notification-service/src/main/kotlin/…/notificationservice/
├── adapter/inbound/{rs,fdv,security,error}   REST resources, auth, exception mapping
├── adapter/outbound/{pushgateway,persistence,dispatch}
├── application/{usecase,repository,port,config}
└── domain/{model,error}

keychain-generator/                           CLI + image that creates keychain entries
```

Hexagonal architecture — dependencies point inward. See
[`notification-service/DESIGN.md`](notification-service/DESIGN.md) for the DAO/repository split.

## Container images and deployment

Images are GraalVM native images built from `Dockerfile.native-micro` and pushed to the project
registry. Because the API mode is fixed at build time, each mode is a separate image:

| Tag                                | Contents            |
|------------------------------------|---------------------|
| `$CI_REGISTRY_IMAGE:<version>`     | Both APIs (default) |
| `$CI_REGISTRY_IMAGE:<version>-rs`  | RS-facing API only  |
| `$CI_REGISTRY_IMAGE:<version>-fdv` | FdV-facing API only |

The keychain generator (see "Record keys") is a plain JVM image from
`keychain-generator/src/main/docker/Dockerfile`, published as
`$CI_REGISTRY_IMAGE/keychain-generator:<version>` in every pipeline and signed on tags and `main`.

Each tag also gets an immutable `-<short-sha>` variant. Merge-request pipelines build only the
default variant to stay fast; pushes to `main` build all three. Published images are scanned with
Trivy and signed. The verify stage of the pipeline runs detekt, Spotless, the compiler and the
tests — see [`.gitlab-ci.yml`](.gitlab-ci.yml).

## Releasing

Versions are plain SemVer git tags, no `v` prefix (e.g. `1.2.0`) — consistent with the other repos
in this GitLab group. There is no automated version bump: the Gradle project version in
`build.gradle.kts` is intentionally left at `1.0.0-SNAPSHOT` and is independent of the release tag.

1. Add an entry to [`ReleaseNotes.md`](ReleaseNotes.md), replacing `## Unreleased` with
   `## Release <version>`.
2. Merge to `main`.
3. Tag and push:
   ```shell script
   git tag <version>
   git push origin <version>
   ```

Pushing the tag is what produces the released image.

## Current limitations

Known gaps:

- **The RS `401` has no body** (A_26662).
- **The HTTPS listener pins no curves or key sizes** (A_29991).
- **No `x-rate-limit-*` headers (A_26668-02).** The requirement covers endpoints reachable by ZETA
  clients, i.e. the FdV paths behind the ZETA Guard ingress, which also sets their limit. Its nginx
  `limit-req` emits none of the three headers; that is open on the chart side. The RS paths are
  called by the Fachdienst, so the RS limit answers with `Retry-After` only, as the RS contract
  specifies.
- **Encrypted push accepts 1018 payload bytes, not the 3 KB the RS contract requires** — the PNM1
  padding (A_27610) leaves no more.
- **At-rest sealing is confined to VAU deployments.** A_29957 allows HSM-backed at-rest encryption
  and pseudonymization only inside a VAU (Variant A). A deployment outside one has to run with
  `notification.persistence.sealing.enabled=false` and must therefore not persist personal user
  identifiers — see [Data protection](#data-protection).
- **Dev and CI seal against the ZETA Guard HSM simulator, not real hardware.** Same gRPC contract
  and real AES-256-GCM, but no substitute for a production HSM; its image is amd64 only.
- **The channel to the HSM proxy is unauthenticated.** The provider's client offers no TLS yet
  (A_28830 is deferred there), so A_29962's attested, authenticated channel is out of reach: a
  sealing deployment has to accept plain text explicitly via `hsm-proxy.plain-text-accepted`.
- **Zeroization is best effort** — the copies `Cipher` and `Mac` take on `init` are out of reach.
- **The dispatch queue is in-process.** Notifications that are queued but not yet delivered are
  lost when the instance stops, and the queue is not shared across instances. gemF_PushNotification
  A_27166 asks for the notification to be buffered and retried "until all push notifications have
  been sent"; this service retries until `push-gateway.retry.give-up-after` (by default one hour)
  and then logs the dispatch as `failed`, but only as long as the instance runs. The period stays
  below the retention, so a late push still finds its notification under `GET /history/{id}`. Items a
  response reports `failed` are sent at most `push-gateway.retry.max-attempts` times (default
  three), since `failed` also covers errors no
  repetition fixes, such as an invalid format. A durable
  queue would need its own table, a lease per instance and a second retention clock; revisit it with
  the first deployment that needs delivery to survive a restart. While a notification waits for its
  next attempt it holds one of the `notification.dispatch.concurrency` consumers, so a Push Gateway
  that stays down delays the notifications for every other Push Gateway too. A retry reuses what the
  first attempt built: it does not check whether a device was deregistered in the meantime, and an
  encrypted item keeps the key of the month it was first encrypted in.
- **`rejected` is the only deregistration signal.** A device the Push Gateway reports as `failed`
  rather than `rejected` stays registered however often it fails. Counting those failures was tried
  and reverted: attributing an item-level `failed` to one device means sending one batch item per
  device, which copies the payload per device and costs the multi-device `partial` answer that
  carries `rejected` in the first place.
- **A rejected pushkey is only resolved within the notified user.** Pushkeys the Push Gateway
  rejects are removed from every pusher of that user, whatever app id registered them and whichever
  dispatch they came from. A pushkey belonging to a *different* user cannot be found: storage ids
  are keyed MACs of the user, so a lookup by pushkey would need a global index, itself a pseudonym
  of the pushkey. Such a pusher is removed the next time its own user is notified.
- **A deactivated history answers `403` behind the PEP, `404 HISTORY_DISABLED` without it**
  (A_29974). In the ZETA Guard deployment the scope `notification.history.read` does not exist
  while the history is off, and the PEP requires it on `/push/v1/history/`, so a client gets the
  PEP's `403` before the request reaches this service. `404 HISTORY_DISABLED` is the second line of
  defence: a client sees it on a direct call, or behind the PEP only when Terraform's
  `notification_history_enabled` is on while the chart's `notificationService.historyEnabled` is
  off — the two are not reconciled automatically. The FdV contract declares only `200` and `400` on
  both paths, the RS contract's description allows `404` or `501`. Decided: `404`, so that a
  configured state does not show up as a server error in error rates and retry rules, and no change
  to the gematik FdV contract. `PUSHER_NOT_FOUND` and `NOTIFICATION_NOT_FOUND` on these paths are
  outside the FdV contract as well.
- **Another user's pusher is answered with `403 M_FORBIDDEN`** (A_29978), on `POST /pushers/set`
  (registration with or without `append`, and deregistration), `GET`/`POST /channels/{pushkey}` and
  `GET /history/device/{pushkey}`. Like `PUSHER_NOT_FOUND`, the answer is outside the gematik FdV
  contract, which is left unchanged. A pusher is its app id and pushkey: the same pushkey under
  another app id is a different pusher and can be registered by another user. A device that changes
  accounts has to deregister its pusher first; the earlier takeover through `append: false`
  (A_27154, which does not apply to this service) is gone, and `append` is accepted but has no
  effect. The answer reveals that someone holds the pushkey, which A_29978 accepts. Two indexes,
  stored pseudonymized and sealed like every other record, tell who holds what: one per app id and
  pushkey for `/pushers/set`, one per pushkey for the channel and history paths. Pushers registered
  by an earlier release enter the second index only when their owner registers them again; until
  then another user gets `404 PUSHER_NOT_FOUND` for them on the channel and history paths, never
  their data. Pushers that two users shared through `append: true` in an earlier release stay
  shared, and each of the two can still update or deregister its own registration.
- **`reference` is not forwarded on the plain dispatch path** — two gematik contracts disagree, and
  until they are reconciled this service follows the schema. The RS OpenAPI describes `POST
  /notifications` as passing the `reference` on as an unencrypted `identifier`, but
  `PlainNotification` in `docs/push_gateway.yaml` has no `identifier` field at all; only
  `EncryptedNotification` has one. gemF_PushNotification A_27532 sharpens the conflict by asking for
  a *random, unique* id as `notification.identifier`, while A_29969 allows the resource server's
  `reference` to repeat. Decided: send no field the Push Gateway contract does not define, and
  propose to gematik that the `identifier` carry the `notification_id` — random, unique, free of
  personal data and already persisted — with the `reference` staying in the history for the resource
  server's own bookkeeping. `reference` is persisted either way.
- **`identifier` carries the resource server's `reference`** on the encrypted path, where the
  contract does define the field. It moves to the `notification_id` with the clarification of
  A_27532 described above.
- **A device's message history expires as a whole**, `notification.history.retention` after its
  first entry, and then starts afresh (A_29988). No entry is ever older than the retention period,
  but younger ones can go early. Counting from the last entry would need a write that moves
  `expires_at`, which the storage contract deliberately does not do.
  `GET /history/{notification_id}` reads the notification record instead and so follows
  `notification.persistence.retention`: once that has passed it answers `404 NOTIFICATION_NOT_FOUND`
  for an entry `GET /history/device/{pushkey}` still lists.

## License

(C) L21s GmbH, 2026, licensed for gematik GmbH

Apache License, Version 2.0

See the [LICENSE](./LICENSE) for the specific language governing permissions and limitations under the License

## Additional Notes and Disclaimer from gematik GmbH

1. Copyright notice: Each published work result is accompanied by an explicit statement of the license conditions for use. These are regularly typical conditions in connection with open source or free software. Programs described/provided/linked here are free software, unless otherwise stated.
2. Permission notice: Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
    1. The copyright notice (Item 1) and the permission notice (Item 2) shall be included in all copies or substantial portions of the Software.
    2. The software is provided "as is" without warranty of any kind, either express or implied, including, but not limited to, the warranties of fitness for a particular purpose, merchantability, and/or non-infringement. The authors or copyright holders shall not be liable in any manner whatsoever for any damages or other claims arising from, out of or in connection with the software or the use or other dealings with the software, whether in an action of contract, tort, or otherwise.
    3. We take open source license compliance very seriously. We are always striving to achieve compliance at all times and to improve our processes. If you find any issues or have any suggestions or comments, or if you see any other ways in which we can improve, please reach out to: ospo@gematik.de
3. Please note: Parts of this code may have been generated using AI-supported technology. Please take this into account, especially when troubleshooting, for security analyses and possible adjustments.
