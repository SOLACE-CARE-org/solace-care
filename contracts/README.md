# Contracts

Shared by the backend, mobile and admin. Change the contract first, then the code.

**Status:** placeholder. Built in Phase 0, step 2:

- `proto/`: gRPC services between backend services (Buf lint and breaking-change checks)
- `openapi/`: edge REST APIs per service, used to generate `packages/api-clients`
- `events/`: Kafka event envelope and payloads as JSON Schema
