#!/usr/bin/env bash
# Writes /opt/solace/.env and livekit.yaml with new random secrets. Run on the server, once. It
# refuses to overwrite an existing .env: new keys would make sealed data unreadable.
set -euo pipefail
cd "$(dirname "$0")"

if [ -e .env ]; then
  echo ".env already exists; leaving it alone" >&2
  exit 1
fi

random() { openssl rand -hex 24; }
key() { openssl rand -base64 32; }

umask 077
LIVEKIT_API_KEY="solace$(openssl rand -hex 6)"
LIVEKIT_API_SECRET="$(openssl rand -hex 32)"
cat > .env <<EOF
SOLACE_TAG=${SOLACE_TAG:?set SOLACE_TAG}
PUBLIC_IP=${PUBLIC_IP:?set PUBLIC_IP}
API_HOST=services.solacecare.space
ADMIN_HOST=masters.solacecare.space
ID_HOST=id.solacecare.space
LIVE_HOST=live.solacecare.space
POSTGRES_PASSWORD=$(random)
CORE_DB_PASSWORD=$(random)
TRACKING_DB_PASSWORD=$(random)
AI_DB_PASSWORD=$(random)
KEYCLOAK_DB_PASSWORD=$(random)
REDIS_PASSWORD=$(random)
MINIO_ROOT_USER=solace-$(openssl rand -hex 4)
MINIO_ROOT_PASSWORD=$(random)
KEYCLOAK_ADMIN=bootstrap-$(openssl rand -hex 4)
KEYCLOAK_ADMIN_PASSWORD=$(random)
SOLACE_CORE_ADMIN_SECRET=$(random)
SOLACE_CORE_SERVICE_SECRET=$(random)
SOLACE_TRACKING_SERVICE_SECRET=$(random)
SOLACE_AI_SERVICE_SECRET=$(random)
SOLACE_CODE_SENDER_SECRET=$(random)
SOLACE_HEALTH_CHECK_KEY=$(key)
SOLACE_SAFETY_PLAN_KEY=$(key)
SOLACE_AI_NOTES_KEY=$(key)
SOLACE_PAYMENTS_SANDBOX_WEBHOOK_SECRET=$(random)
LIVEKIT_API_KEY=${LIVEKIT_API_KEY}
LIVEKIT_API_SECRET=${LIVEKIT_API_SECRET}
EOF

cat > livekit.yaml <<EOF
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 60000
turn:
  enabled: true
  domain: turn.solacecare.space
  udp_port: 3478
  tls_port: 5349
  cert_file: /etc/livekit-certs/turn.crt
  key_file: /etc/livekit-certs/turn.key
keys:
  ${LIVEKIT_API_KEY}: ${LIVEKIT_API_SECRET}
webhook:
  api_key: ${LIVEKIT_API_KEY}
  urls:
    - https://services.solacecare.space/api/core/v1/webhooks/livekit
logging:
  level: info
EOF
echo "wrote .env and livekit.yaml"
