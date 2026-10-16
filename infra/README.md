# Infrastructure

## Local stack

From the repo root:

```sh
docker compose -f infra/docker-compose.yml up -d          # start (first run pulls images)
bash infra/scripts/smoke-check.sh                    # wait for and check every component
docker compose -f infra/docker-compose.yml down            # stop, keep data
docker compose -f infra/docker-compose.yml down -v         # stop and delete all data
```

Defaults work out of the box. To change a password, copy `.env.example` to `.env` here and edit it. These credentials are for local development only; real environments take secrets from Vault.

| Component | Host address | Notes |
| --- | --- | --- |
| PostgreSQL 16 | `localhost:5432` | Databases `core`, `tracking`, `ai` (with pgvector), `keycloak`. Each has an owner role of the same name (password = name), and no role can connect to another's database |
| Kafka (KRaft) | `localhost:9092` | Containers use `kafka:19092`. Topics are not auto-created; each service declares its own |
| Schema registry | <http://localhost:8081> | JSON Schemas from `contracts/events/` |
| Redis | `localhost:6379` | Slot holds, OTP, rate limits, cached taxonomy |
| MinIO | API <http://localhost:9000>, console <http://localhost:9001> | Buckets `solace-credentials`, `solace-content`. Login `solace` / `solace-dev-only` |
| Keycloak | <http://localhost:8180> | Admin `admin` / `admin`. Realms arrive in step 4 |
| LiveKit | `ws://localhost:7880` | Key `devkey`, secret in `livekit/livekit.yaml` |
| OTel collector | OTLP gRPC `localhost:4317`, HTTP `localhost:4318` | Traces to Tempo, metrics to Prometheus, logs to Loki |
| Grafana | <http://localhost:3000> | Prometheus, Loki and Tempo provisioned, trace-to-logs linked. Login `admin` / `admin` |
| Prometheus, Loki, Tempo | :9090, :3100, :3200 | Usually viewed through Grafana |

## Still to come

- `keycloak/`: realms, roles, clients and the code authenticator extension (Phase 0, step 4)
- `k8s/`, `argocd/`: manifests for dev, staging and prod, written but not applied until hosting is decided (step 12)
