#!/usr/bin/env bash
# Copies Caddy's certificate for the TURN name to where LiveKit reads it, and restarts LiveKit when
# it changed. Runs as root, daily from cron.
set -euo pipefail
DIR=/opt/solace/turn-certs
SOURCE=$(find /var/lib/caddy/.local/share/caddy/certificates -type d -name turn.solacecare.space | head -1)
[ -n "$SOURCE" ] || { echo "Caddy has no certificate for the TURN name yet" >&2; exit 1; }

mkdir -p "$DIR"
if ! cmp -s "$SOURCE/turn.solacecare.space.crt" "$DIR/turn.crt"; then
  install -m 644 "$SOURCE/turn.solacecare.space.crt" "$DIR/turn.crt"
  install -m 644 "$SOURCE/turn.solacecare.space.key" "$DIR/turn.key"
  docker compose -f /opt/solace/docker-compose.yml restart livekit 2>/dev/null || true
fi
