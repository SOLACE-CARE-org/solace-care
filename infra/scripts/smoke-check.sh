#!/usr/bin/env bash
# Checks that every piece of the local stack answers. Run from the repo root
# after `docker compose -f infra/docker-compose.yml up -d`. Each check retries
# for up to READY_TIMEOUT seconds (default 90), so it also waits for start-up.
set -uo pipefail

# Git Bash on Windows rewrites /opt/... and /bin/... arguments into Windows paths
# before Docker sees them. Container paths must pass through untouched.
export MSYS_NO_PATHCONV=1

compose=(docker compose -f infra/docker-compose.yml)
timeout="${READY_TIMEOUT:-90}"
failures=0

check() {
  local name="$1"; shift
  local deadline=$((SECONDS + timeout))
  until "$@" > /dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      printf 'FAIL  %s\n' "$name"
      failures=$((failures + 1))
      return
    fi
    sleep 2
  done
  printf 'ok    %s\n' "$name"
}

# Expected to fail: proves the database boundary holds.
check_denied() {
  local name="$1"; shift
  if "$@" > /dev/null 2>&1; then
    printf 'FAIL  %s (access was allowed)\n' "$name"
    failures=$((failures + 1))
  else
    printf 'ok    %s\n' "$name"
  fi
}

psql_as() {
  local role="$1" db="$2"
  "${compose[@]}" exec -T -e PGPASSWORD="$role" postgres \
    psql -h localhost -U "$role" -d "$db" -tAc 'select 1'
}

for db in core tracking ai keycloak; do
  check "postgres: $db can open its own database" psql_as "$db" "$db"
done
check_denied "postgres: tracking cannot open core's database" psql_as tracking core
check_denied "postgres: core cannot open ai's database" psql_as core ai
check "postgres: pgvector in ai" "${compose[@]}" exec -T postgres \
  psql -U postgres -d ai -tAc "select 1 from pg_extension where extname = 'vector'"

check "kafka: broker answers" "${compose[@]}" exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
check "schema registry" curl -fs http://localhost:8081/subjects
check "redis" "${compose[@]}" exec -T redis redis-cli ping
check "minio: buckets created" "${compose[@]}" run --rm --no-deps -T --entrypoint /bin/sh minio-init -c \
  'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" && mc ls local/solace-credentials'
check "keycloak: master realm" curl -fs http://localhost:8180/realms/master/.well-known/openid-configuration
check "keycloak: solace-members realm" curl -fs http://localhost:8180/realms/solace-members/.well-known/openid-configuration
check "keycloak: solace-staff realm" curl -fs http://localhost:8180/realms/solace-staff/.well-known/openid-configuration
check "keycloak: service token with narrow scope" bash -c \
  'curl -fs -d grant_type=client_credentials -d client_id=tracking-service -d client_secret=tracking-service-dev-secret http://localhost:8180/realms/solace-staff/protocol/openid-connect/token | grep -q "core:booking-access.read"'
check "livekit" curl -fs http://localhost:7880
check "prometheus" curl -fs http://localhost:9090/-/ready
check "loki" curl -fs http://localhost:3100/ready
check "tempo" curl -fs http://localhost:3200/ready
check "grafana: datasources provisioned" bash -c \
  'curl -fs -u admin:admin http://localhost:3000/api/datasources | grep -q "\"uid\":\"tempo\""'
check "otel collector: accepts OTLP" curl -fs -X POST \
  -H 'Content-Type: application/json' -d '{"resourceSpans":[]}' http://localhost:4318/v1/traces

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
