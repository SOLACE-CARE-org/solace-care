# Start here

This bundle is the complete plan for Solace, as of 16 Oct 2026: five specs, the decision log, every mobile and admin screen in EN and FR, and the design system. It is meant to become the first commit of the Solace monorepo, which Claude Code then builds on.

## 1. Set up the repository

1. Create an empty GitHub repository, e.g. `solace`.
2. Copy everything in this folder to the repository root, so that `CLAUDE.md`, `docs/` and `design/` sit at the top level.
3. Download the four logo SVGs into `design/design-system/assets/Logos/`. That folder's README says how.
4. Commit the files as "Design handoff".

Claude Code reads `CLAUDE.md` automatically in every session, so the rules in it apply to all the work.

## 2. Phase 0 kickoff prompt

Open Claude Code in the repository and paste this:

> Read `CLAUDE.md`, then `docs/specs/system-design.md`, `docs/specs/implementation-plan.md` and `docs/decisions.md`. We are starting **Phase 0: Foundations**, the walking skeleton described in the plan. Don't build product features yet.
>
> First, propose a plan for Phase 0 as an ordered list of small steps, each ending in something runnable and committed. Wait for my OK before writing code. The plan should cover, in roughly this order:
>
> 1. The monorepo scaffold, following the layout in `CLAUDE.md`. That means a Gradle multi-project build for `services/*` and `libs/*`, plus pnpm workspaces for `apps/*` and `packages/*`.
> 2. `contracts/`:
>    - Buf setup with one example gRPC service per call in the System Design's "Synchronous calls" table.
>    - The event envelope as JSON Schema.
>    - An edge OpenAPI stub per service.
> 3. Local infrastructure in `infra/docker-compose.yml`:
>    - PostgreSQL 16, one database per service, plus a separate database for Keycloak.
>    - Kafka with a schema registry, Redis, MinIO, Keycloak, LiveKit (dev mode), and the OpenTelemetry collector with Prometheus, Loki and Grafana.
> 4. Keycloak configuration as code:
>    - Realms and clients.
>    - Roles from the System Design.
>    - Staff policy: required password update and TOTP, 72-hour temporary password, `[15]`-minute idle timeout.
>    - Client-credentials clients for service-to-service calls.
>    - A skeleton of the phone/email code authenticator extension, with a fake SMS sender.
> 5. Shared libraries in `libs/`:
>    - Event envelope and transactional outbox.
>    - gRPC client defaults: 2-second deadline and Resilience4j.
>    - Error model, audit-log writer, locale resolution.
>    - A log appender that scrubs health data.
> 6. Service skeletons for Core (Spring Modulith, with the module packages from the System Design but no features), Tracking and AI:
>    - Each is a resource server that checks Keycloak tokens.
>    - Each has Flyway and health endpoints.
> 7. The gateway (Spring Cloud Gateway), with JWT checks, rate limits and routes for `/api/core/**`, `/api/tracking/**` and `/api/ai/**`.
> 8. The end-to-end slice: the app calls the gateway, the gateway calls Core, Core makes a gRPC call to Tracking and AI stubs, then Core publishes a Kafka event through the outbox. One trace must show every hop.
> 9. The mobile shell:
>    - Expo dev build with role-based navigation shells, i18next with EN/FR from `packages/i18n`, and TanStack Query.
>    - Tokens generated from `design/design-system/tokens.json` into `packages/tokens`.
>    - Bundled Bricolage Grotesque and Figtree.
>    - The Splash, Language and Welcome screens built to the designs, as the visual baseline.
> 10. The admin shell:
>     - Angular with Keycloak sign-in (password plus authenticator code), permission guards and Transloco.
>     - The Sidebar and Sign-in screens built to the designs.
> 11. CI in GitHub Actions:
>     - Build, unit tests and Testcontainers integration tests.
>     - Buf lint and breaking-change check, OpenAPI diff and event-schema compatibility.
>     - EN/FR key parity.
>     - Dependency and container scanning.
>     - EAS mobile builds.
> 12. Kubernetes manifests and Argo CD apps for dev, staging and prod in `infra/`. Write them but don't apply them: hosting is not decided yet.
> 13. First drafts of a threat model per service and a records-of-processing register in `docs/security/`.
>
> Everything has to run locally with `docker compose up` plus the service and app run commands. Put those commands in the root README.
>
> Stop when the Phase 0 gate in the plan is met locally:
> - The skeleton runs end to end, and one trace shows every hop.
> - CI rejects a breaking contract change and a missing FR string.
>
> Then tell me what's left that only I can do.

When Phase 0 is done, start each later phase with: "Read `CLAUDE.md` and the plan. We're starting Phase N. Propose the plan for epic 1 and wait for my OK."

## 3. What only you can do

The plan says these start in Phase 0, because each one blocks a later gate:

- Choose the hosting: an in-country data centre or a regional cloud. Get a data-protection lawyer's view first. The Phase 0 gate needs this decision.
- File for authorisation with Cameroon's Data Protection Authority.
- Sign contracts with a mobile-money aggregator and an SMS provider.
- Open the app store accounts.
- Recruit clinical reviewers and the first specialists in both languages.
- Settle the team size and target launch date.

## What's in the bundle

| Path | Contents |
| --- | --- |
| `CLAUDE.md` | Standing rules for Claude Code |
| `docs/specs/` | System Design, Implementation Plan, Tracking Service Spec, Admin Portal Spec, Notification Copy |
| `docs/decisions.md` | The decision log, and the questions still open |
| `docs/screens.md` | Every screen with its phase, source file and render |
| `design/screens/` | 162 mobile and 78 admin boards (EN and FR) as source plus PNG |
| `design/design-system/` | Tokens, rules, 17 component guides, logo notes |

## Live versions

The specs were exported from these live docs, which also hold the diagrams the Markdown export leaves out:

| Doc | Link |
| --- | --- |
| System Design | https://claude.ai/code/artifact/bbe42882-341d-440f-a272-6eea26f4bf0f |
| Tracking Service Spec | https://claude.ai/code/artifact/f9131a5a-c304-45f6-86d1-48917ffa99d9 |
| Admin Portal Spec | https://claude.ai/code/artifact/25805b4d-994e-4b86-8fb3-41094165b0a4 |
| Implementation Plan | https://claude.ai/code/artifact/6c85d331-519f-4d75-9040-712f5143d52d |
| Notification Copy | https://claude.ai/code/artifact/072fc9e4-c9e5-4fda-8438-502e2f59dfc2 |
| App screens canvas | https://claude.ai/artifact/UkTAFAMeaoepm59BU9xH8p |
| Admin portal canvas | https://claude.ai/artifact/CF8vRe3ntoFM6FVaNgEzvf |
| Design system | https://claude.ai/artifact/M78BFurzPEz2mJPDAn3g5H |

If you change a live doc, export it again and replace the file in `docs/specs/`.
