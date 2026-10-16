# Solace

Solace (brand: **Solace Care**) is a bilingual English/French mental-health marketplace for Cameroon. Read [CLAUDE.md](CLAUDE.md) for the rules, then [START_HERE.md](START_HERE.md) and the specs in [docs/specs/](docs/specs/).

The project is in **Phase 0: Foundations**, building the walking skeleton. There are no product features yet.

## Layout

```
backend/     Java: Gradle build with services/ (core, tracking, ai, gateway), libs/, build-logic/
mobile/      Expo app for seekers, guests and specialists
admin/       Angular admin portal
contracts/   Protobuf, edge OpenAPI, event JSON Schemas (shared)
packages/    i18n, tokens, api-clients (shared by mobile and admin)
infra/       docker-compose, Keycloak config, Kubernetes, Argo CD
```

## Prerequisites

| Tool | Version |
| --- | --- |
| JDK | 21 (Gradle finds a local JDK 21 or downloads one) |
| Node.js | 22 or later |
| pnpm | 10 (`corepack enable`) |
| Docker | with Compose v2 |

## Commands

```sh
# Java: build and test every service and library
cd backend
./gradlew build

# Format Java sources
./gradlew spotlessApply

# JavaScript / TypeScript workspace (from the repo root)
cd ..
pnpm install
pnpm build
pnpm test
```

More commands (local infrastructure, running each service and app) are added here as each Phase 0 step lands.
