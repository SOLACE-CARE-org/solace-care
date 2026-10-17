#!/usr/bin/env bash
# Phase 0 gate, end to end: app -> gateway -> Core -> gRPC to Tracking and AI -> Kafka event
# consumed by Tracking, all in one trace.
#
# Needs the local stack and the four processes running (see README.md):
#   docker compose -f infra/docker-compose.yml up -d
#   cd backend && ./gradlew :services:{gateway,core,tracking,ai}:bootRun   (one terminal each)
# Then, from the repo root:
#   bash infra/scripts/e2e-ping.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1

KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
GATEWAY=${GATEWAY:-http://localhost:8080}
TEMPO=${TEMPO:-http://localhost:3200}
PHONE="+237699000001"
MASKED="+237*******01"
compose=(docker compose -f infra/docker-compose.yml)

json() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const o=JSON.parse(s);console.log($1)})"; }
fail() { echo "FAIL  $*"; exit 1; }
step() { echo "--    $*"; }

# 1. A test seeker in the members realm, created once.
step "test seeker in solace-members"
ADMIN=$(curl -fs -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
  "$KEYCLOAK/realms/master/protocol/openid-connect/token" | json 'o.access_token')
FOUND=$(curl -fs -H "Authorization: Bearer $ADMIN" \
  "$KEYCLOAK/admin/realms/solace-members/users?username=e2e-seeker&exact=true" | json 'o.length')
if [ "$FOUND" = "0" ]; then
  curl -fs -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"e2e-seeker\",\"enabled\":true,\"attributes\":{\"phone_number\":[\"$PHONE\"]}}" \
    "$KEYCLOAK/admin/realms/solace-members/users" > /dev/null
  USER_ID=$(curl -fs -H "Authorization: Bearer $ADMIN" \
    "$KEYCLOAK/admin/realms/solace-members/users?username=e2e-seeker&exact=true" | json 'o[0].id')
  ROLE=$(curl -fs -H "Authorization: Bearer $ADMIN" "$KEYCLOAK/admin/realms/solace-members/roles/SEEKER")
  curl -fs -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "[$ROLE]" "$KEYCLOAK/admin/realms/solace-members/users/$USER_ID/role-mappings/realm" > /dev/null
fi

# 2. Sign in with a one-time code, as the app does. Locally the code is in Keycloak's log.
step "sign in with a one-time code"
# The masked number contains * and +, so it is matched as plain text (grep -F).
code_lines() { "${compose[@]}" logs keycloak 2>/dev/null | grep "sign-in code" | grep -F "for $MASKED" || true; }
codes_logged() { code_lines | wc -l | tr -d ' '; }
request_code() {
  # Answers 401 code_required by design; only the code it sends matters.
  curl -s -d grant_type=password -d client_id=solace-mobile --data-urlencode "username=$PHONE" \
    "$KEYCLOAK/realms/solace-members/protocol/openid-connect/token" > /dev/null || true
}
BEFORE=$(codes_logged)
request_code
sleep 2
if [ "$(codes_logged)" = "$BEFORE" ]; then
  step "a code was sent less than a minute ago; waiting for the resend delay"
  sleep 61
  request_code
  sleep 2
fi
CODE=$(code_lines | tail -1 | sed -E 's/.*sign-in code ([0-9]+) for.*/\1/')
[ -n "$CODE" ] || fail "no sign-in code in the Keycloak log"
TOKEN=$(curl -fs -d grant_type=password -d client_id=solace-mobile --data-urlencode "username=$PHONE" -d "code=$CODE" \
  "$KEYCLOAK/realms/solace-members/protocol/openid-connect/token" | json 'o.access_token')
[ -n "$TOKEN" ] || fail "code was not accepted"

# 3. The ping, through the gateway.
step "GET $GATEWAY/api/core/v1/system/ping"
STARTED=$(date +%s)
RESULT=$(curl -fs -H "Authorization: Bearer $TOKEN" -H 'Accept-Language: fr' "$GATEWAY/api/core/v1/system/ping") \
  || fail "ping through the gateway failed"
echo "      $RESULT"
[ "$(echo "$RESULT" | json 'o.core && o.tracking && o.ai')" = "true" ] || fail "not every service answered"

# 4. One trace with every hop. Spans reach Tempo within a few seconds of being exported.
step "looking for the trace in Tempo"
QUERY='{ resource.service.name = "core" && name =~ ".*system/ping.*" }'
TRACE=""
for _ in $(seq 1 20); do
  sleep 3
  TRACE=$(curl -fs -G "$TEMPO/api/search" --data-urlencode "q=$QUERY" \
    --data-urlencode "start=$((STARTED - 5))" --data-urlencode "end=$(( $(date +%s) + 5 ))" \
    --data-urlencode "limit=1" | json '(o.traces[0]||{}).traceID||""') || true
  [ -n "$TRACE" ] && break
done
[ -n "$TRACE" ] || fail "no trace for the ping in Tempo"

SUMMARY=""
for _ in $(seq 1 10); do
  SUMMARY=$(curl -fs "$TEMPO/api/traces/$TRACE" | node -e "
    let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{
      const o=JSON.parse(s); const services=new Set(); const spans=[];
      for (const b of (o.batches||o.resourceSpans||[])) {
        const svc=(b.resource.attributes.find(a=>a.key==='service.name')||{}).value?.stringValue;
        services.add(svc);
        for (const ss of (b.scopeSpans||b.instrumentationLibrarySpans||[]))
          for (const sp of ss.spans) spans.push(svc+': '+sp.name+' ['+sp.kind+']');
      }
      const need=['gateway','core','tracking','ai'];
      const kafkaSend=spans.some(x=>x.startsWith('core:')&&/core\.system/.test(x)&&/PRODUCER/.test(x));
      const kafkaReceive=spans.some(x=>x.startsWith('tracking:')&&/core\.system/.test(x)&&/CONSUMER/.test(x));
      const ok=need.every(n=>services.has(n))&&kafkaSend&&kafkaReceive;
      console.log((ok?'OK':'INCOMPLETE')+'\n'+spans.sort().map(x=>'        '+x).join('\n'));
    })")
  [ "${SUMMARY%%$'\n'*}" = "OK" ] && break
  sleep 3
done
echo "      trace $TRACE ($TEMPO/api/traces/$TRACE)"
echo "${SUMMARY#*$'\n'}"
[ "${SUMMARY%%$'\n'*}" = "OK" ] || fail "trace is missing a hop: gateway, core, tracking, ai, the Kafka send or Tracking's consumer"
echo "PASS  one trace covers gateway, Core, gRPC to Tracking and AI, and the Kafka event to Tracking"
