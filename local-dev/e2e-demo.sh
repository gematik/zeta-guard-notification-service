#!/usr/bin/env bash
set -uo pipefail

NS_URL="${NS_URL:-http://localhost:8080}"
PGW_ADMIN_URL="${PGW_ADMIN_URL:-http://localhost:8081}"
PUSHER_URL="http://push-gateway:8080/push/v1/"
CHANNEL="epa.documents.new"
CHANNEL_INACTIVE="epa.consent.changed"

BASE=$(( $(date +%s) % 100000000 ))
USER_ACTIVE=$(printf 'X%09d' "$BASE")
USER_NO_CHANNEL=$(printf 'X%09d' $((BASE + 1)))
USER_REJECTED=$(printf 'X%09d' $((BASE + 2)))
USER_RETRY=$(printf 'X%09d' $((BASE + 3)))
USER_UNKNOWN="X999999999"
RUN="run-$BASE"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; BOLD='\033[1m'; NC='\033[0m'
PASS=0; FAIL=0

STATUS=""; BODY=""

ok()      { PASS=$((PASS+1)); printf "  ${GREEN}✔ OK${NC} %s\n" "$1"; }
planned() { PASS=$((PASS+1)); printf "  ${YELLOW}✔ GEPLANTER FEHLER${NC} %s\n" "$1"; }
bad()     { FAIL=$((FAIL+1)); printf "  ${RED}✘ FEHLGESCHLAGEN${NC} %s\n     Status=%s Body=%s\n" "$1" "$STATUS" "$BODY"; }
section() { printf "\n${BOLD}== %s ==${NC}\n" "$1"; }

request() {
  local method="$1" url="$2"; shift 2
  local response
  response=$(curl -s -X "$method" "$url" -w $'\n%{http_code}' "$@")
  STATUS="${response##*$'\n'}"
  BODY="${response%$'\n'*}"
}

user_info() {
  printf '{"identifier":"%s","professionOID":"1.2.276.0.76.4.16","commonName":"AK Demo"}' "$1" \
    | base64 | tr -d '\n=' | tr '+/' '-_'
}

setup_failed() {
  printf "${RED}Setup fehlgeschlagen: %s (Status=%s Body=%s)${NC}\n" "$1" "$STATUS" "$BODY"
  exit 1
}

register_pusher() {
  local identifier="$1" pushkey="$2"
  request POST "$NS_URL/pushers/set" \
    -H "zeta-user-info: $(user_info "$identifier")" \
    -H "Content-Type: application/json" \
    -d "{\"pushkey\":\"$pushkey\",\"app_id\":\"de.gematik.zeta.demo\",\"kind\":\"http\",
         \"app_display_name\":\"AK Demo App\",\"device_display_name\":\"AK Demo Device\",
         \"lang\":\"de\",\"data\":{\"url\":\"$PUSHER_URL\"}}"
  [ "$STATUS" = "200" ] || setup_failed "Pusher $pushkey für $identifier"
}

configure_channel() {
  local identifier="$1" pushkey="$2" channel="$3" status="$4"
  request POST "$NS_URL/channels/$pushkey" \
    -H "zeta-user-info: $(user_info "$identifier")" \
    -H "Content-Type: application/json" \
    -d "{\"channels\":[{\"id\":\"$channel\",\"status\":\"$status\"}]}"
  [ "$STATUS" = "200" ] || setup_failed "Channel $channel ($status) für Pusher $pushkey"
}

submit_notification() {
  local identifier="$1" channel="$2"; shift 2
  request POST "$NS_URL/notifications" \
    -H "Content-Type: application/json" \
    -d "{\"user\":{\"id_type\":\"kvnr\",\"value\":\"$identifier\"},\"channel\":\"$channel\",
         \"prio\":\"normal\",\"payload\":{\"event\":\"document.created\",\"document_id\":\"doc-$RUN\"},
         \"reference\":\"ak-demo-$RUN\"}" "$@"
}

add_mapping() {
  curl -s -X POST "$PGW_ADMIN_URL/__admin/mappings" -H "Content-Type: application/json" -d "$1" \
    | grep -o '"id" *: *"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/'
}

delete_mapping() { [ -n "$1" ] && curl -s -X DELETE "$PGW_ADMIN_URL/__admin/mappings/$1" >/dev/null; }

journal_contains() { curl -s "$PGW_ADMIN_URL/__admin/requests" | grep -c "$1" >/dev/null; }

journal_count() {
  curl -s -X POST "$PGW_ADMIN_URL/__admin/requests/count" -H "Content-Type: application/json" \
    -d "{\"method\":\"POST\",\"urlPathPattern\":\".*/notify/batch\",\"bodyPatterns\":[{\"contains\":\"$1\"}]}" \
    | grep -Eo '[0-9]+' | head -1
}

await_count() {
  local needle="$1" expected="$2"
  for _ in $(seq 1 30); do
    [ "$(journal_count "$needle")" = "$expected" ] && return 0
    sleep 0.5
  done
  return 1
}

await_journal() {
  local needle="$1"
  for _ in $(seq 1 20); do
    journal_contains "$needle" && return 0
    sleep 0.5
  done
  return 1
}

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

section "Setup: Warte auf den Notification Service ($NS_URL/q/health)"
for i in $(seq 1 30); do
  curl -sf "$NS_URL/q/health" >/dev/null && break
  [ "$i" -eq 30 ] && { printf "${RED}Service nicht erreichbar — läuft 'docker compose up'?${NC}\n"; exit 1; }
  sleep 1
done
echo "Service ist up. Testnutzer: $USER_ACTIVE (aktiv), $USER_NO_CHANNEL (ohne aktiven Channel), $USER_REJECTED (rejected-Szenario), $USER_RETRY (Retry-Szenario), $USER_UNKNOWN (unregistriert)"

register_pusher "$USER_ACTIVE" "ak-$RUN-device-1"
register_pusher "$USER_ACTIVE" "ak-$RUN-device-2"
configure_channel "$USER_ACTIVE" "ak-$RUN-device-1" "$CHANNEL" "enabled"
configure_channel "$USER_ACTIVE" "ak-$RUN-device-1" "$CHANNEL_INACTIVE" "disabled"
register_pusher "$USER_NO_CHANNEL" "ak-$RUN-device-3"
register_pusher "$USER_REJECTED" "ak-$RUN-device-4"
configure_channel "$USER_REJECTED" "ak-$RUN-device-4" "$CHANNEL" "enabled"
register_pusher "$USER_RETRY" "ak-$RUN-device-5"
configure_channel "$USER_RETRY" "ak-$RUN-device-5" "$CHANNEL" "enabled"

section "AK 1: GET /channels aggregiert den Status über alle Geräte (enabled auf 1 von 2 Geräten)"
request GET "$NS_URL/users/$USER_ACTIVE/channels?id_type=kvnr"
if [ "$STATUS" = "200" ] \
  && echo "$BODY" | grep -q "\"id\":\"$CHANNEL\",\"status\":\"enabled\"" \
  && echo "$BODY" | grep -q '"registered_device_count":2' \
  && echo "$BODY" | grep -q '"has_registered_devices":true'; then
  ok "200: '$CHANNEL' enabled (nur auf Gerät 1 aktiv), registered_device_count=2"
else
  bad "erwartet: 200 mit '$CHANNEL' enabled und registered_device_count=2"
fi

section "AK 2: include_inactive steuert die Sichtbarkeit inaktiver Channels"
request GET "$NS_URL/users/$USER_ACTIVE/channels?id_type=kvnr"
DEFAULT_COUNT=$(echo "$BODY" | grep -o '"status":"' | wc -l | tr -d ' ')
if [ "$STATUS" = "200" ] && [ "$DEFAULT_COUNT" = "1" ] && ! echo "$BODY" | grep -q '"status":"disabled"'; then
  ok "ohne Parameter: nur der eine enabled-Channel"
else
  bad "erwartet: genau 1 Channel (enabled) ohne include_inactive, bekommen: $DEFAULT_COUNT"
fi
request GET "$NS_URL/users/$USER_ACTIVE/channels?id_type=kvnr&include_inactive=true"
if [ "$STATUS" = "200" ] \
  && echo "$BODY" | grep -q "\"id\":\"$CHANNEL_INACTIVE\",\"status\":\"disabled\""; then
  ok "include_inactive=true: '$CHANNEL_INACTIVE' erscheint als disabled"
else
  bad "erwartet: 200 mit '$CHANNEL_INACTIVE' als disabled"
fi

section "AK 3: GET /channels für unregistrierten Identifikator"
request GET "$NS_URL/users/$USER_UNKNOWN/channels?id_type=kvnr"
if [ "$STATUS" = "404" ] && echo "$BODY" | grep -q '"errorCode":"USER_NOT_FOUND"'; then
  planned "404 USER_NOT_FOUND für $USER_UNKNOWN"
else
  bad "erwartet: 404 USER_NOT_FOUND"
fi

section "AK 4: POST /notifications antwortet 202 accepted, BEVOR das Push Gateway antwortet"
STUB_ID=$(curl -s -X POST "$PGW_ADMIN_URL/__admin/mappings" -H "Content-Type: application/json" -d '{
  "priority": 1,
  "request": {"method": "POST", "urlPathPattern": ".*/notify/batch"},
  "response": {"status": 200, "fixedDelayMilliseconds": 3000,
    "headers": {"Content-Type": "application/json"},
    "jsonBody": {"results": [{"id": "any", "status": "success", "rejected": []}],
                 "summary": {"total": 1, "successful": 1, "failed": 0, "partial": 0}}}
}' | grep -o '"id" *: *"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
START=$(now_ms)
submit_notification "$USER_ACTIVE" "$CHANNEL"
ELAPSED=$(( $(now_ms) - START ))
NOTIFICATION_ID=$(echo "$BODY" | grep -o '"notification_id":"[^"]*"' | cut -d'"' -f4)
RESP_STATUS=$(echo "$BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [ "$STATUS" = "202" ] && { [ "$RESP_STATUS" = "accepted" ] || [ "$RESP_STATUS" = "queued" ]; } && [ -n "$NOTIFICATION_ID" ]; then
  ok "202 mit status=$RESP_STATUS und notification_id=$NOTIFICATION_ID"
else
  bad "erwartet: 202 mit status accepted/queued und notification_id"
fi
if [ "$ELAPSED" -lt 2000 ]; then
  ok "Antwort nach ${ELAPSED}ms, obwohl das Push Gateway 3000ms verzögert war → asynchron"
else
  bad "Antwort dauerte ${ELAPSED}ms — hat auf das Push Gateway gewartet?"
fi
[ -n "$STUB_ID" ] && curl -s -X DELETE "$PGW_ADMIN_URL/__admin/mappings/$STUB_ID" >/dev/null
if [ -n "$NOTIFICATION_ID" ] && await_journal "$NOTIFICATION_ID"; then
  ok "Zustellung asynchron im Push-Gateway-Journal angekommen (payload unverändert: AK 11 siehe unten)"
else
  bad "notification_id nicht im Push-Gateway-Journal angekommen"
fi

section "AK 5: POST /notifications für Nutzer ohne registriertes Gerät"
submit_notification "$USER_UNKNOWN" "$CHANNEL"
if [ "$STATUS" = "404" ] && echo "$BODY" | grep -q '"errorCode":"USER_NOT_FOUND"'; then
  planned "404 USER_NOT_FOUND"
else
  bad "erwartet: 404 USER_NOT_FOUND"
fi

section "AK 6: POST /notifications für Channel außerhalb der Registry"
submit_notification "$USER_ACTIVE" "epa.unknown"
if [ "$STATUS" = "422" ] && echo "$BODY" | grep -q '"errorCode":"UNKNOWN_CHANNEL"'; then
  planned "422 UNKNOWN_CHANNEL für 'epa.unknown'"
else
  bad "erwartet: 422 UNKNOWN_CHANNEL"
fi

section "AK 7: POST /notifications für registrierten, nirgends aktivierten Channel"
submit_notification "$USER_NO_CHANNEL" "$CHANNEL"
NAC_ID=$(echo "$BODY" | grep -o '"notification_id":"[^"]*"' | cut -d'"' -f4)
RESP_STATUS=$(echo "$BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [ "$STATUS" = "202" ] && [ "$RESP_STATUS" = "no_active_channel" ]; then
  ok "202 mit status=no_active_channel"
else
  bad "erwartet: 202 mit status=no_active_channel"
fi
sleep 2
if [ -n "$NAC_ID" ] && ! journal_contains "$NAC_ID"; then
  ok "kein Versand: notification_id=$NAC_ID taucht im Push-Gateway-Journal nicht auf"
else
  bad "no_active_channel darf keinen Push-Gateway-Aufruf auslösen"
fi

section "AK 8: Fehlerhafte/fehlende id_type- oder userId-Werte"
request GET "$NS_URL/users/$USER_ACTIVE/channels"
if [ "$STATUS" = "400" ] && echo "$BODY" | grep -q '"errorCode":"INVALID_PARAM"'; then
  planned "fehlendes id_type → 400 INVALID_PARAM"
else
  bad "erwartet: 400 INVALID_PARAM bei fehlendem id_type"
fi
request GET "$NS_URL/users/$USER_ACTIVE/channels?id_type=iban"
if [ "$STATUS" = "400" ] && echo "$BODY" | grep -q '"errorCode":"INVALID_PARAM"'; then
  planned "id_type=iban → 400 INVALID_PARAM"
else
  bad "erwartet: 400 INVALID_PARAM bei ungültigem id_type"
fi
request GET "$NS_URL/users/.invalid/channels?id_type=kvnr"
if [ "$STATUS" = "400" ]; then
  planned "userId '.invalid' → 400 (errorCode=$(echo "$BODY" | grep -o '"errorCode":"[^"]*"' | cut -d'"' -f4))"
else
  bad "erwartet: 400 bei Pattern-verletzender userId"
fi

section "AK 11: payload wird unverändert ans Push Gateway durchgereicht"
if journal_contains "document.created" && journal_contains "doc-$RUN"; then
  ok "payload-Felder (event, document_id=doc-$RUN) unverändert im Batch-Request"
else
  bad "payload-Inhalt nicht im Push-Gateway-Journal gefunden"
fi

section "AK 12: Transiente Push-Gateway-Fehler werden mit Back-off wiederholt (A_29971)"
RETRY_PUSHKEY="ak-$RUN-device-5"
retry_stub() {
  local state="$1" next="$2" response="$3"
  add_mapping "{
    \"priority\": 1,
    \"scenarioName\": \"retry-$RUN\",
    \"requiredScenarioState\": \"$state\",
    \"newScenarioState\": \"$next\",
    \"request\": {\"method\": \"POST\", \"urlPathPattern\": \".*/notify/batch\",
                  \"bodyPatterns\": [{\"contains\": \"$RETRY_PUSHKEY\"}]},
    \"response\": $response
  }"
}
RETRY_OK='{"status": 200, "headers": {"Content-Type": "application/json"},
           "jsonBody": {"results": [{"id": "any", "status": "success", "rejected": []}],
                        "summary": {"total": 1, "successful": 1, "failed": 0, "partial": 0}}}'
RS1=$(retry_stub "Started" "zweiter-versuch" '{"status": 503}')
RS2=$(retry_stub "zweiter-versuch" "dritter-versuch" '{"status": 503}')
RS3=$(retry_stub "dritter-versuch" "abgeschlossen" "$RETRY_OK")
submit_notification "$USER_RETRY" "$CHANNEL"
RETRY_ID=$(echo "$BODY" | grep -o '"notification_id":"[^"]*"' | cut -d'"' -f4)
RESP_STATUS=$(echo "$BODY" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [ "$STATUS" = "202" ] && [ "$RESP_STATUS" = "accepted" ]; then
  ok "202 accepted trotz initialer Gateway-Störung"
else
  bad "erwartet: 202 accepted"
fi
if [ -n "$RETRY_ID" ] && await_count "$RETRY_ID" 3; then
  ok "genau 3 Zustellversuche im Journal (503, 503, 200) → Exponential Back-off greift"
else
  bad "erwartet: 3 Zustellversuche, gezählt: $(journal_count "$RETRY_ID")"
fi
for id in "$RS1" "$RS2" "$RS3"; do delete_mapping "$id"; done

section "AK 13: Abgelehnte Pushkeys werden deregistriert, Nutzer ohne Geräte unbekannt (A_29971/A_27374)"
REJECT_PUSHKEY="ak-$RUN-device-4"
REJECT_STUB=$(add_mapping "{
  \"priority\": 1,
  \"request\": {\"method\": \"POST\", \"urlPathPattern\": \".*/notify/batch\",
                \"bodyPatterns\": [{\"contains\": \"$REJECT_PUSHKEY\"}]},
  \"response\": {\"status\": 200, \"headers\": {\"Content-Type\": \"application/json\"},
                 \"jsonBody\": {\"results\": [{\"id\": \"any\", \"status\": \"partial\", \"rejected\": [\"$REJECT_PUSHKEY\"]}],
                                \"summary\": {\"total\": 1, \"successful\": 0, \"failed\": 0, \"partial\": 1}}}
}")
submit_notification "$USER_REJECTED" "$CHANNEL"
if [ "$STATUS" = "202" ]; then
  ok "202 — die Ablehnung ändert die Annahme nicht (A_29965)"
else
  bad "erwartet: 202"
fi
STILL_REGISTERED=1
for _ in $(seq 1 20); do
  request GET "$NS_URL/pushers" -H "zeta-user-info: $(user_info "$USER_REJECTED")"
  echo "$BODY" | grep -q "$REJECT_PUSHKEY" || { STILL_REGISTERED=0; break; }
  sleep 0.5
done
if [ "$STILL_REGISTERED" = "0" ]; then
  ok "Pusher $REJECT_PUSHKEY wurde nach rejected-Antwort entfernt"
else
  bad "Pusher $REJECT_PUSHKEY ist nach rejected-Antwort noch registriert"
fi
submit_notification "$USER_REJECTED" "$CHANNEL"
if [ "$STATUS" = "404" ] && echo "$BODY" | grep -q '"errorCode":"USER_NOT_FOUND"'; then
  planned "Folge-Submit → 404 USER_NOT_FOUND (letztes Gerät entfernt → Nutzer unbekannt)"
else
  bad "erwartet: 404 USER_NOT_FOUND nach Entfernung des letzten Pushers"
fi
delete_mapping "$REJECT_STUB"

section "AK 14: Idempotency-Key liefert dieselbe notification_id ohne zweiten Versand (A_29992)"
submit_notification "$USER_ACTIVE" "$CHANNEL" -H "Idempotency-Key: idem-$RUN"
IDEM_ID1=$(echo "$BODY" | grep -o '"notification_id":"[^"]*"' | cut -d'"' -f4)
if [ "$STATUS" = "202" ] && [ -n "$IDEM_ID1" ] && await_count "$IDEM_ID1" 1; then
  ok "Erstübermittlung: 202, notification_id=$IDEM_ID1, einmal versendet"
else
  bad "erwartet: 202 mit notification_id und genau einem Versand"
fi
submit_notification "$USER_ACTIVE" "$CHANNEL" -H "Idempotency-Key: idem-$RUN"
IDEM_ID2=$(echo "$BODY" | grep -o '"notification_id":"[^"]*"' | cut -d'"' -f4)
if [ "$STATUS" = "202" ] && [ "$IDEM_ID2" = "$IDEM_ID1" ]; then
  ok "Wiederholung: 202 mit identischer notification_id"
else
  bad "erwartet: identische notification_id, bekommen: $IDEM_ID2"
fi
sleep 2
IDEM_COUNT=$(journal_count "$IDEM_ID1")
if [ "$IDEM_COUNT" = "1" ]; then
  ok "weiterhin genau ein Versand ans Push Gateway"
else
  bad "erwartet: 1 Versand, gezählt: $IDEM_COUNT"
fi

section "AK 15: Versand nur an Geräte mit aktiviertem Channel (A_29966)"
if journal_contains "ak-$RUN-device-1" && ! journal_contains "ak-$RUN-device-2"; then
  ok "device-1 (enabled) im Journal, device-2 (nie aktiviert) taucht nie auf"
else
  bad "device-2 ohne aktivierten Channel darf im Push-Gateway-Journal nicht vorkommen"
fi

section "AK 16: FdV-Endpunkte verlangen Authentisierung (A_29975)"
request GET "$NS_URL/pushers"
if [ "$STATUS" = "401" ]; then
  planned "ohne zeta-user-info → 401"
else
  bad "erwartet: 401 ohne zeta-user-info"
fi
request GET "$NS_URL/pushers" -H "zeta-user-info: {\"identifier\":\"$USER_ACTIVE\"}"
if [ "$STATUS" = "401" ]; then
  planned "unkodierter (nicht Base64url) Header → 401"
else
  bad "erwartet: 401 bei unkodiertem Header"
fi

section "AK 17: Strikte Nutzerbindung — fremde Pusher unsichtbar und unveränderbar (A_29978)"
request GET "$NS_URL/pushers" -H "zeta-user-info: $(user_info "$USER_NO_CHANNEL")"
if [ "$STATUS" = "200" ] && echo "$BODY" | grep -q "ak-$RUN-device-3" && ! echo "$BODY" | grep -q "ak-$RUN-device-1"; then
  ok "GET /pushers zeigt nur die eigenen Pusher"
else
  bad "erwartet: nur device-3, keinesfalls fremde Pusher"
fi
request POST "$NS_URL/channels/ak-$RUN-device-1" \
  -H "zeta-user-info: $(user_info "$USER_NO_CHANNEL")" \
  -H "Content-Type: application/json" \
  -d "{\"channels\":[{\"id\":\"$CHANNEL\",\"status\":\"enabled\"}]}"
if [ "$STATUS" = "403" ] && echo "$BODY" | grep -q '"errorCode":"M_FORBIDDEN"'; then
  planned "Channel-Konfiguration auf fremdem Pusher → 403 M_FORBIDDEN"
else
  bad "erwartet: 403 M_FORBIDDEN auf fremdem Pusher"
fi
request POST "$NS_URL/pushers/set" \
  -H "zeta-user-info: $(user_info "$USER_NO_CHANNEL")" \
  --json "{\"pushkey\":\"ak-$RUN-device-1\",\"app_id\":\"de.gematik.zeta.demo\",\"kind\":\"http\",
           \"app_display_name\":\"Fremd\",\"device_display_name\":\"Fremd\",
           \"lang\":\"de\",\"data\":{\"url\":\"$PUSHER_URL\"}}"
if [ "$STATUS" = "403" ] && echo "$BODY" | grep -q '"errorCode":"M_FORBIDDEN"'; then
  planned "Registrierung eines fremden Pushers → 403 M_FORBIDDEN"
else
  bad "erwartet: 403 M_FORBIDDEN bei Registrierung eines fremden Pushers"
fi

section "AK 18: Keine Nutzeridentifikatoren Richtung Push Gateway (A_29984)"
if ! journal_contains "$USER_ACTIVE" \
  && ! journal_contains "$USER_RETRY" \
  && ! journal_contains "$USER_REJECTED"; then
  ok "keine KVNR im Push-Gateway-Journal — Adressierung nur über pushkey/app_id"
else
  bad "KVNR eines Testnutzers im Push-Gateway-Journal gefunden"
fi

section "AK 19: Schemavalidierung der RS-Requests (A_29986)"
request POST "$NS_URL/notifications" \
  -H "Content-Type: application/json" \
  -d "{\"user\":{\"id_type\":\"kvnr\",\"value\":\".invalid\"},\"channel\":\"$CHANNEL\",\"prio\":\"normal\",\"payload\":{}}"
if [ "$STATUS" = "400" ] && echo "$BODY" | grep -q '"errorCode":"M_INVALID_PARAM"'; then
  planned "Pattern-verletzende userId im Body → 400 M_INVALID_PARAM"
else
  bad "erwartet: 400 M_INVALID_PARAM bei ungültiger userId"
fi
request POST "$NS_URL/notifications" \
  -H "Content-Type: application/json" \
  -d "{\"user\":{\"id_type\":\"kvnr\",\"value\":\"$USER_ACTIVE\"},\"prio\":\"normal\",\"payload\":{}}"
if [ "$STATUS" = "400" ] && echo "$BODY" | grep -q '"errorCode":"M_MISSING_PARAM"'; then
  planned "fehlendes Pflichtfeld channel → 400 M_MISSING_PARAM"
else
  bad "erwartet: 400 M_MISSING_PARAM bei fehlendem channel"
fi

section "Nicht im Skript prüfbar"
printf "  ${YELLOW}∅${NC} AK 9 (401 bei fehlender Auth): RS-mTLS ist im Compose-Stack abgeschaltet, abgedeckt durch RsMtlsAcceptanceIT\n"
printf "  ${YELLOW}∅${NC} AK 10 (Contract-Test): über generierte OpenAPI-Modelle abgesichert — Spec-Änderung bricht den Build\n"
printf "  ${YELLOW}∅${NC} AK 11 (reference): wird persistiert, aber auf dem Plain-Versandpfad nicht ans Gateway gesendet (offener Befund)\n"

printf "\n${BOLD}== Ergebnis ==${NC}\n"
if [ "$FAIL" -eq 0 ]; then
  printf "${GREEN}${BOLD}%d Prüfungen bestanden, 0 fehlgeschlagen.${NC}\n" "$PASS"
else
  printf "${RED}${BOLD}%d bestanden, %d FEHLGESCHLAGEN.${NC}\n" "$PASS" "$FAIL"
  exit 1
fi
