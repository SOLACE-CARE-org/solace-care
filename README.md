# Solace

Solace (brand: **Solace Care**) is a bilingual English/French mental-health marketplace for Cameroon. Read [CLAUDE.md](CLAUDE.md) for the rules, then [START_HERE.md](START_HERE.md) and the specs in [docs/specs/](docs/specs/).

The project is in **Phase 0: Foundations**, building the walking skeleton. There are no product features yet.

## Layout

```text
backend/     Java: Gradle build with services/ (core, tracking, ai, gateway), libs/, build-logic/
mobile/      Expo app for seekers, guests and specialists
admin/       Angular admin portal
contracts/   Protobuf, edge OpenAPI, event JSON Schemas (shared)
packages/    i18n, tokens, api-clients (shared by mobile and admin)
infra/       docker-compose, Keycloak config, Kubernetes, Argo CD
.github/     CI workflows and Dependabot
```

## CI

| Workflow | When | What |
| --- | --- | --- |
| `ci.yml` | every pull request and push to main | Gradle build with all tests; i18n check, admin and mobile tests; contract lint; on pull requests, Buf, OpenAPI and event-schema breaking-change checks against main. On main it also builds, scans (Trivy) and publishes the six images to GHCR, tagged with the commit |
| `security.yml` | pull requests, main, weekly | Dependency review, Trivy for vulnerabilities and committed secrets, Trivy IaC scan, CodeQL |
| `mobile-build.yml` | by hand | EAS build of the mobile app (mobile/README.md) |

Actions are pinned to commit SHAs; Dependabot keeps them, the dependencies and the base images current. Lint the workflows locally with `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12`.

## Prerequisites

| Tool | Version |
| --- | --- |
| JDK | 21 (Gradle finds a local JDK 21 or downloads one) |
| Node.js | 22 or later |
| pnpm | 10 (`corepack enable`) |
| Docker | with Compose v2 |

## Commands

```sh
# Local infrastructure (details and addresses in infra/README.md)
(cd backend && ./gradlew :keycloak:otp-authenticator:jar)   # Keycloak extension, needed before the first start
docker compose -f infra/docker-compose.yml up -d
bash infra/scripts/smoke-check.sh

# Java: build and test every service and library
cd backend
./gradlew build

# Format Java sources
./gradlew spotlessApply

# Run the services against the local stack (one terminal each)
./gradlew :services:gateway:bootRun    # the API for apps: http://localhost:8080/api/{core,tracking,ai}/...
./gradlew :services:core:bootRun       # REST :8101
./gradlew :services:tracking:bootRun   # REST :8102, gRPC :9102
./gradlew :services:ai:bootRun         # REST :8103, gRPC :9103

# Phase 0 gate, end to end (from the repo root, with the stack and all four processes running):
# signs a test seeker in with a one-time code, calls the ping through the gateway, and checks
# that one trace in Tempo covers gateway, Core, gRPC to Tracking and AI, and the Kafka event.
bash infra/scripts/e2e-ping.sh

# JavaScript / TypeScript workspace (from the repo root)
cd ..
pnpm install                              # also generates design tokens (packages/tokens)
pnpm build
pnpm test                                 # i18n EN/FR parity, mobile tests

# Admin portal (details in admin/README.md): http://localhost:4200, staff sign in through Keycloak
pnpm --filter @solace/admin start

# Mobile app (details in mobile/README.md)
pnpm --filter @solace/mobile android      # dev build on an Android device or emulator
pnpm --filter @solace/mobile start        # Metro for an installed dev build

# Contracts: Buf lint, OpenAPI lint, event schema validation
pnpm --filter @solace/contracts lint
# Fail on a breaking change compared with main: proto (buf), edge OpenAPI (oasdiff, in Docker)
# and event schemas
pnpm --filter @solace/contracts breaking

# Container images, from the repo root, after the Gradle build and pnpm build above
docker build --build-arg SERVICE=core -t solace/core backend      # also tracking, ai, gateway
docker build -t solace/admin admin                                 # settings from env at start
docker build -f infra/keycloak/Dockerfile -t solace/keycloak .     # authenticators and theme built in
```

The admin image reads `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`, `API_URL`, `IDLE_SIGN_OUT_MINUTES` and the `FLAG_*` variables when it starts, so one image serves every environment; its Content-Security-Policy is built from the same values.

Apps talk only to the gateway on :8080, which checks the token, rate-limits per person and routes `/api/core/**`, `/api/tracking/**` and `/api/ai/**` with the prefix removed. Health checks are open at `/actuator/health/readiness` and `/actuator/health/liveness` on each process; everything else needs a Keycloak token. App commands are added here as each Phase 0 step lands.
