# Solace: guidance for Claude Code

Solace (brand: **Solace Care**) is a bilingual English/French mental-health marketplace for Cameroon. Seekers find a licensed specialist, book and pay with mobile money, and talk by audio or video. Registered seekers can also track their health between sessions. Staff run the platform from a web admin portal.

Everything here was designed before any code was written. **The specs and screens are the source of truth.** If code and a spec disagree, the spec wins. If a spec is silent or two specs conflict, stop and ask; don't invent behaviour.

## Where things are

| Path | What it is |
| --- | --- |
| `docs/specs/system-design.md` | Architecture: services, modules, data model, events, security, infrastructure. Read it first. |
| `docs/specs/implementation-plan.md` | Phases 0–4, epics in build order, gates, definition of done, and screens by phase |
| `docs/specs/tracking-service-spec.md` | The Tracking service in full: elements, modules, rules, the pattern check, APIs, tables |
| `docs/specs/admin-portal-spec.md` | 31 admin screens, 7 roles, the permission matrix, workflows |
| `docs/specs/notification-copy.md` | Every push, SMS and email in EN and FR, word for word |
| `docs/decisions.md` | Decisions already made, with the reasons. Don't reopen these without asking. |
| `docs/screens.md` | Index of every screen: phase, source file, PNG render |
| `design/screens/mobile/`, `design/screens/admin/` | Screen designs: `png/` to look at, `source/` for exact values |
| `design/design-system/` | Tokens (`tokens.json`), rules (`README.md`), component guidelines (`components/*/README.md`), logos |

The specs contain diagrams that were dropped from the Markdown export; you'll see "embedded content" placeholders where they were. The live docs keep them; the links are in `START_HERE.md`.

## Monorepo layout

```
services/core        Spring Boot 3, Spring Modulith (identity, marketplace, money, admin, health check)
services/tracking    Spring Boot 3 (tracking elements, modules, rules, observations)
services/ai          Spring Boot 3, Spring AI + LangGraph4j (safety monitor first)
services/gateway     Spring Cloud Gateway
libs/                shared Java libraries: event envelope + outbox, gRPC client defaults, error model, audit writer, locale
contracts/           Protobuf (Buf), edge OpenAPI specs, event JSON Schemas
apps/mobile          Expo (React Native, TypeScript): seekers, guests, specialists in one app
apps/admin           Angular 17+ (standalone components, Angular Material, NgRx Signal Store)
packages/i18n        shared EN/FR JSON strings
packages/api-clients generated TypeScript clients
packages/tokens      platform files generated from design/design-system/tokens.json
infra/               docker-compose for local dev, Keycloak realm config, Kubernetes manifests, Argo CD
```

The Java services are one Gradle multi-project build. Flyway migrations live in each service.

## Stack, fixed

- **Backend:** Java 21 (virtual threads on), Spring Boot 3, Spring Modulith in Core, Spring Security resource servers, Spring gRPC, Resilience4j, Flyway.
- **Data:** PostgreSQL 16, one database per service, and no service reads another's tables. pgvector goes in the AI database. Redis is for slot holds, OTP, rate limits and cached taxonomy. S3-compatible object storage (MinIO locally).
- **Messaging:** Kafka with a schema registry. Events are written through the transactional outbox (Spring Modulith event publication). Consumers are idempotent on `eventId` and failures go to a dead-letter topic.
- **Between services:** gRPC with 2-second deadlines, a circuit breaker and a defined fallback for every call. Clients talk REST/JSON at the gateway.
- **Identity:** Keycloak is the only identity provider (see Sign-in below).
- **Calls:** LiveKit, with react-native-callkeep for CallKit and ConnectionService.
- **Mobile:** Expo dev builds, Hermes, New Architecture, TanStack Query, Zustand, i18next, op-sqlite for offline tracking, Lucide icons at stroke width 1.8.
- **Admin:** Angular, Transloco, Lucide icons.
- **Observability:** OpenTelemetry, Prometheus, Loki and Grafana. Every event carries a `traceId`.
- **CI:** GitHub Actions.

## Rules that apply to every change

**Privacy and safety**

- Logs, traces, metrics and events never contain health data, free text, names, phone numbers or emails. Scrub at the log appender. Events carry only a pseudonymous `subjectId`.
- Every read of clinical data writes an audit entry. That covers tracking entries, notes, health check answers, transcripts and crisis alerts. Every admin action is audited too.
- Check consent before processing anything that needs it, using versioned consent records per purpose.
- Tracking notes (`entry_note`) are storage only. Rules never read them, nothing scans them, and they are hard-deleted when the seeker deletes them.
- Tracking and the health check never produce a diagnosis, a severity label or a prediction. Don't use the word "triage" anywhere.
- The crisis button works for guests, is one tap away and shows local numbers even offline.

**Language**

- Every user-facing string exists in EN and FR in `packages/i18n` (mobile and admin) or Spring `MessageSource` (backend). CI fails on a missing key. Components never hard-code text.
- The app language (EN/FR) and the session language are different things. Session languages are data (ISO 639 rows), not an enum.
- Nothing seekers see can be published without both EN and FR.
- Dates, times and FCFA amounts are formatted per locale. Store times in UTC and show them in the viewer's time zone.

**Notifications**

- Use the exact copy in `notification-copy.md`. Notifications use neutral wording: never a condition, a specialty, a mood, "therapy" or "mental health". Respect discreet mode.
- SMS must fit 160 GSM-7 characters after the placeholders are filled. French SMS avoid ê â î ô û ç ë ï.

**Money**

- Use a double-entry ledger. Funds are held until the session completes.
- Every refund, at any amount, needs a second Finance approver who did not propose it. Payout runs need a second approver too.
- Webhooks are signature-checked and idempotent.

**Contracts**

- Change the contract first: Protobuf, OpenAPI or event schema.
- Buf breaking-change checks, OpenAPI diff and schema compatibility must pass.

**Settings**

- Numbers shown in brackets in the specs (e.g. `[30]` days, `[15]`-minute idle sign-out) are placeholders.
- Implement them as admin settings or config with that default, never as constants.
- Fixed numbers (no brackets) are decisions: the 2-second gRPC deadline, the 10-minute slot hold, crisis escalation at 5 and 10 minutes, a 72-hour temporary password.

**Feature flags:** risky or later-phase features ship behind a feature flag.

## Sign-in

- **Seekers and specialists:** a one-time code by SMS or email, on the app's own screens. A custom Keycloak authenticator extension checks the code, and Core sends the SMS.
- **Guests:** no Keycloak account. After the code check, Core issues a short-lived token scoped to one booking. Guest bookings are claimed automatically when the guest registers with the same contact.
- **Staff:**
  - Only a Super admin can create a staff account. Core does it through the Keycloak admin API.
  - Each account gets a unique temporary password, handed over in person. It works once and expires after 72 hours.
  - First sign-in forces a new password and authenticator-app (TOTP) setup.
  - Staff are signed out after `[15]` idle minutes, and sessions end when a role changes.
  - No one can sign themselves up as staff.
- **Services:** each validates the token again behind the gateway. Service-to-service calls use client-credentials tokens with narrow scopes, plus mTLS.

## Design

- Read `design/design-system/README.md` before building any UI.
- **Tokens:** generate platform files from `tokens.json` at build time; never retype a hex value or a size.
- **Fonts:** Bricolage Grotesque (display) and Figtree (body). Bundle them; don't call a font CDN at runtime.
- **Visual rules:**
  - Light theme only.
  - Borders, not shadows; the only shadow is the focus ring.
  - Tap targets are at least 44 px.
  - No emoji. No exclamation marks in copy.
- **Component names in code:** Button, TextField, Chip, OptionCard, Switch, Segmented, Pill, CountBadge, Avatar, Card, Notice, Skeleton, EmptyState, ProgressSteps, BottomNav, Sidebar, DataTable. Each has a guidelines README.
- **Screen files:** `.dc.html` files are design references from a canvas tool, not code to copy.
  - Read them for layout, exact spacing, copy and states.
  - Ignore the `<x-dc>`, `<helmet>`, `dc-import` and `class Component extends DCLogic` mechanics.
  - Their Google Fonts links and inline styles are not how the app should load fonts or style components.
- **Screenshots:** the PNGs use fallback fonts and show a dashed "logo" box where a logo goes.
- **States:** every list has loading, empty, error and (on mobile) offline states. The patterns are on the States pages (`State*.dc.html`).
- **Copy:** the EN/FR copy on screens and in `notification-copy.md` is final. Put it in the i18n catalogues as written.

## How to work

- **Build order:** work phase by phase, in the epic order of `implementation-plan.md`. Don't build a later phase's feature early, except behind a flag where the plan says so.
- **Plan first:** before a sizeable piece of work, write a short plan and confirm it.
- **Definition of done** (from the plan):
  - Unit tests, plus Testcontainers integration tests for anything that touches a database, Kafka or another service.
  - Contracts updated.
  - EN and FR present.
  - Audit on clinical reads.
  - Works on a low-end Android phone.
  - Screen-reader labels on every control.
- **Commits:** keep them small and focused.
- **Unresolved questions:** the open questions at the end of each spec are still open. If one blocks you, say so and use the safest default behind config. Don't decide it silently.
