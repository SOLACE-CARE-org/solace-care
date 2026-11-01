#!/usr/bin/env bash
# Daily database dumps, kept for 7 days. They stay on this server, so they don't protect against
# losing it: copy them elsewhere before the server holds anything that matters.
set -euo pipefail
OUT=/opt/solace/backups
mkdir -p "$OUT"
chmod 700 "$OUT"
cd /opt/solace
for db in core tracking ai keycloak; do
  docker compose exec -T postgres pg_dump -U postgres -Fc "$db" > "$OUT/$db-$(date +%F).dump"
done
find "$OUT" -name '*.dump' -mtime +7 -delete
