# API specifications

The OpenAPI files in this directory are copies of gematik
specifications. The build generates code
from them (`generateFdvApi`, `generateRsApi`,
`generatePushGatewayApi`, `generatePushGatewayStubs`),
so a change here changes the service's interface. Take
updates from the official source listed below
rather than editing a copy, and record any local deviation
in this file.

| File                                    | Official source                                                                                                                                                                                    | Copied from                                                 | Local deviations |
|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|------------------|
| `openApi_notification_fachdienst.yaml`  | [gematik/gem-push-notifications-concept](https://github.com/gematik/gem-push-notifications-concept): `docs_sources/fd_openapi.yaml` with the files under `docs_sources/definitions/` it references | Release `1.2.0` (`69f1d6e2e7e1`), all files merged into one | None             |
| `push_gateway.yaml`                     | [gematik/gem-push-notifications-concept](https://github.com/gematik/gem-push-notifications-concept): `docs_sources/push_gateway_openapi.yaml`                                                      | `main` at `e24377cb8711` (spec version `1.2.1`)             | None             |
| `notification_service_rs_endpoint.yaml` | [gematik/zeta](https://github.com/gematik/zeta): `src/openapi/notification-service-rs-endpoint.yaml`                                                                                               | `main` (spec version `1.0.0`), compared at `cc5f122d28a5`   | See below        |

## Local deviations

### `notification_service_rs_endpoint.yaml`

- `413` response of `POST /notifications`: the description
  adds the 1018-byte limit for encrypted
  delivery, and the single example is split into an
  unencrypted (3072 bytes) and an encrypted (1018 bytes)
  one. Upstream says "at least 3 KB" only.
- `payload` description: the limit for encrypted delivery is
  1018 bytes of serialized payload,
  since the `PNM1` marker and the padding length take 6 of
  the 1024 padded bytes (A_27610), and a
  larger payload is rejected with `413`. Upstream says 1024
  bytes.

## Known gaps in the official specifications

These are deliberately *not* patched into the copies; README
"Current limitations" describes how the
service behaves instead.

- `openApi_notification_fachdienst.yaml` declares no `403`
  on `POST /pushers/set`,
  `GET`/`POST /channels/{pushkey}` or
  `GET /history/device/{pushkey}`, although A_29978
  (gemSpec_ZETA) requires `403` for operations on another
  user's pusher. The service answers
  `403 M_FORBIDDEN` there.
- Its `append` description still requires the Matrix
  takeover ("the server must remove any other
  pushers with the same App ID and pushkey for different
  users"), which A_29978 rules out. The
  service accepts `append` but ignores it.
- It declares no `404` either, which the service answers
  with `PUSHER_NOT_FOUND`,
  `NOTIFICATION_NOT_FOUND` and `HISTORY_DISABLED`.

## Updating a copy

1. Fetch the file from the official source at a release tag
   if one exists, otherwise at a commit.
   For the FdV specification, merge `fd_openapi.yaml` and
   the definitions it references into one
   file, since the generator reads a single file.
2. Re-apply the local deviations listed above, or drop the
   ones the new version makes unnecessary.
3. Update the table in this file, run
   `./gradlew :notification-service:build`, and mention the
   change in `ReleaseNotes.md` if the interface changes.
