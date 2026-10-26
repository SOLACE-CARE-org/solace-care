# Solace — Implementation Plan

Oct 16, 2026 · @Banseka

Solace is built in five phases: foundations, the marketplace, tracking, AI assist, then deeper care. No code is written until every screen is designed and every spec is signed off. The public launch comes at the end of Phase 1; each later phase ships behind its own gate.

## Scope and assumptions

The plan builds what Solace — System Design, Solace — Tracking Service Spec and the [Solace App Screens](https://claude.ai/artifact/UkTAFAMeaoepm59BU9xH8p) canvas describe. Nothing exists yet, so every service, table and screen is new.

|  | At public launch (end of Phase 1) | After launch |
| --- | --- | --- |
| Seekers | Guest booking, accounts, sign-up, search, booking, mobile-money payment, audio and video sessions, ratings, crisis button, privacy centre | Tracking and the health check (Phase 2), companion and safety plan (Phase 4) |
| Specialists | Sign-up and verification, hours, calendar, sessions, earnings, reviews | Pre-session brief (Phase 3), session scribe and homework (Phase 4) |
| Admins | Live overview, crisis queue, specialist review, bookings, payments, catalogue, content, audit | Module builder (Phase 2), AI controls (Phase 3), copilot (Phase 4) |
| AI | Safety monitor only | Every other AI feature |

**Assumptions**

- The stack is fixed: Java 21 and Spring Boot 3 for Core, Tracking and AI; React Native with Expo for mobile; Angular for admin; gRPC between services, REST at the edge, Kafka for events.
- Versions start at v1; the next revision of anything is v1.1.
- Phases are sequenced by dependency and closed by gates, not dates. Durations come once team size and the target launch date are known.
- Work outside engineering (legal, payments, clinicians, specialist recruitment) starts in Phase 0, because each one blocks a later gate.

## Before Phase 0: design complete

Implementation starts only when this list is done and signed off, so engineers never build a screen or a flow that has not been decided.

- [x] Specs: Solace — System Design, Solace — Tracking Service Spec and Solace — Admin Portal Spec
- [x] Mobile app: all 69 screens, including the health check, on the [Solace App Screens](https://claude.ai/artifact/UkTAFAMeaoepm59BU9xH8p) canvas
- [x] Admin portal: all 31 screens, plus first sign-in and the phone crisis queue, on the [Solace Admin Portal](https://claude.ai/artifact/CF8vRe3ntoFM6FVaNgEzvf) canvas
- [x] Admin portal, D61 configuration screens 32 to 37 (providers, senders, jobs, notification copy, AI models, reviewed lists): in `design/screens/admin`, on the canvas's Configuration pages
- [x] French versions of every mobile and admin screen, checked for longer text: on the FR pages of both canvases
- [x] Empty, loading, error and offline states: shown as patterns on representative screens, on the States pages of the [app](https://claude.ai/artifact/UkTAFAMeaoepm59BU9xH8p) and [admin](https://claude.ai/artifact/CF8vRe3ntoFM6FVaNgEzvf) canvases
- [x] Push, SMS and email message copy in EN and FR, in neutral wording: Solace — Notification Copy
- [x] Design handoff: colour and type tokens, a component list, and spacing rules shared by mobile and admin, in the [Solace Care design system](https://claude.ai/artifact/M78BFurzPEz2mJPDAn3g5H). The five colour fixes for WCAG AA are applied and recorded there under Contrast
- [x] Open questions that change a screen answered: every refund needs a second approver; crisis alerts escalate after 5 minutes; staff accounts are created by a Super admin in Keycloak with a one-time temporary password, then a new password and an authenticator app at first sign-in

**Gate to Phase 0:** you approve both canvases and all three specs.

## Phases at a glance

> Diagram: Implementation phases · 5 phases, outside dependencies, 5 gates (in the live doc, linked from START_HERE.md)

Read each column top to bottom: what engineering builds, what must be ready from outside engineering, and the gate that closes the phase. Phase 1 (highlighted) ends in the public launch.

## Phase 0: Foundations

Phase 0 produces a walking skeleton: one request travels from the app through every layer to every service, traced, on staging. No product features ship yet.

| Workstream | Deliverables |
| --- | --- |
| Repository | One monorepo: `services/core`, `services/tracking`, `services/ai` (Gradle multi-project); `contracts/` (Protobuf with Buf, edge OpenAPI, event JSON Schemas); `apps/mobile` (Expo); `apps/admin` (Angular); `packages/i18n` (shared EN/FR strings); `packages/api-clients` (generated TypeScript clients); `infra/` |
| CI | GitHub Actions: build, unit tests, Testcontainers integration tests, Buf lint and breaking-change check, OpenAPI diff, event-schema compatibility, EN/FR key parity, dependency and container scanning, EAS mobile builds |
| Environments | Dev, staging and prod Kubernetes clusters; Argo CD; Vault; PostgreSQL 16 per service; Kafka with schema registry; Redis; S3-compatible storage; LiveKit on staging |
| Identity and edge | Keycloak with a phone and email code authenticator for seekers and specialists, and staff accounts created by a Super admin (one-time password, forced change, authenticator app); client-credentials tokens and mTLS between services; Spring Cloud Gateway with JWT checks and rate limits |
| Shared libraries | Event envelope and transactional outbox; gRPC client defaults (2-second deadline, Resilience4j); error model; audit-log writer; locale resolution |
| Observability | OpenTelemetry traces, Prometheus metrics, Loki logs with health data scrubbed, Grafana dashboards |
| Mobile shell | Expo development build, role-based navigation shells, i18next, TanStack Query, design tokens from the canvas (colours, Bricolage Grotesque and Figtree), a performance baseline on a low-end Android phone |
| Admin shell | Angular app behind Keycloak sign-in (password and authenticator code), permission guards, Transloco |
| Security | A threat model per service and a records-of-processing register, both reviewed before Phase 1 code touches personal data |

**Start now, outside engineering:** a data-protection lawyer's view on hosting region and AI vendors; the authorisation filing with the Data Protection Authority; the mobile-money aggregator and SMS provider contracts; app store accounts; recruiting clinical reviewers and the first specialists in both languages.

**Gate to Phase 1:** the skeleton runs on staging end to end (app → gateway → Core → gRPC to Tracking and AI stubs → Kafka event), one trace shows every hop, and CI rejects a breaking contract change or a missing FR string. The hosting region is decided.

## Phase 1: Marketplace and public launch

Phase 1 delivers the full care loop: find a specialist, book, pay, talk, rate. The epics are listed in build order; each depends on the ones above it unless its row says otherwise.

| # | Epic | Where | Delivers |
| --- | --- | --- | --- |
| 1 | Identity and accounts | Core, mobile | OTP sign-in, seeker sign-up, roles; guest contact verification with a booking-scoped token; guest bookings claimed on registration |
| 2 | Specialist onboarding | Core, mobile, admin | Onboarding state machine (Draft to Approved or Rejected, then Suspended); credential documents in object storage; languages with proficiency; services and prices; payout details; admin review and language verification |
| 3 | Catalogue and search | Core | Specialty taxonomy in EN/FR; session language chosen first; filters for specialty, modality, price, availability and rating on PostgreSQL full-text |
| 4 | Scheduling | Core | Weekly rules and exceptions in the specialist's time zone; slots computed on read; a 10-minute hold in Redis; an exclusion constraint so double booking cannot happen |
| 5 | Bookings | Core | The booking state machine; cancellation and reschedule policies set by admins |
| 6 | Payments | Core | Provider port with the mobile-money aggregator adapter; double-entry ledger; funds held until the session completes; refunds, payouts and commission; signed, idempotent webhooks; daily reconciliation. Cards follow as a second adapter |
| 7 | Sessions | Core, mobile | LiveKit rooms per booking; join tokens from 10 minutes before start; waiting room with a network test; video steps down to audio; 2-minute reconnection; webhooks record attendance; calls ring through CallKit and ConnectionService |
| 8 | Ratings and reviews | Core, admin | One rating per completed booking; human moderation queue until AI moderation arrives in Phase 3 |
| 9 | Notifications | Core | Push, SMS and email templates in EN/FR; neutral wording; SMS fallback for guests; session reminders; quiet hours |
| 10 | Safety monitor | AI, Core, admin | The first AI slice: classifier on review text, `ai.risk.flagged` into the admin crisis queue, on-call alerting; crisis button with local numbers that work offline. Can start alongside epic 1 |
| 11 | Privacy and consent | Core, mobile | Versioned consent records, privacy centre, data export, erasure through `core.user.erased`, retention jobs. Can start alongside epic 1 |
| 12 | Admin portal v1 | Admin | Live overview, crisis queue, specialist queue, bookings and sessions, payments and payout runs, catalogue and policies, bilingual content, audit log, settings and feature flags |

**Pilot before launch:** a closed pilot with a small group of vetted specialists and invited seekers, run in both languages, with real mobile-money payments and real calls on the weakest networks the team can find.

**Gate to public launch:** the Data Protection Authority has authorised processing; a penetration test has no open high-severity findings; the pilot meets the booking, join and payment-confirmation targets; the crisis on-call rota is staffed.

## Phase 2: Tracking

Phase 2 builds the Tracking service exactly as the spec describes and launches the two v1 modules, PCOS & cycle and the daily check-in.

| # | Epic | Where | Delivers |
| --- | --- | --- | --- |
| 1 | Service and data model | Tracking | Every table in the spec; sync push and pull with `client_entry_id` replay; tombstones; local day plus time zone; notes in `entry_note`, hard-deleted on delete |
| 2 | Elements | Tracking, mobile | Migration seeding the nine v1 elements; the seven input views and their icons in the app; `min_app_version` checks |
| 3 | Cycle model | Tracking | `CycleReconciler` and `CycleModel`; cycle signals for cycle length, period days and very heavy days |
| 4 | Module builder | Tracking, admin | Draft, review, publish and retire; card image upload; who-it's-for text and the enrolment question; synthetic-history test runs; the clinical reviewer role |
| 5 | Pattern check | Tracking | The three operators, minimum data, guards, outcomes, repeat window, withdrawal, nightly run and the `rule_evaluation` log |
| 6 | Recommendations to care | Tracking, Core, mobile | `tracking.recommendation.created`; Core matches specialists by specialty and session language; invite cards with book, snooze and dismiss. Messages come from the rule's EN/FR template until AI wording arrives in Phase 3 |
| 7 | Specialist access | Tracking, Core | Forward-only consent grant with a separate yes for notes; a live-booking check over gRPC on every read; access logged |
| 8 | Nudges | Tracking, Core | At most one push a day, never SMS, stopping after 5 ignored days; an invite takes that day's push |
| 9 | Offline logging | Mobile | op-sqlite queue, catalogue and labels cached, replay on reconnect |
| 10 | Launch modules | Admin, clinicians | PCOS & cycle v1 and Daily check-in v1 built in the builder and approved through clinical review |
| 11 | Health check | Core, mobile, admin | Versions, questions, scoring rules and the locked safety rule; guest answers kept under a device token and claimed on sign-up; matches by specialty, language, format and price; the builder with made-up test sets; v1 written and approved by two clinicians |

The tracking step in sign-up, the Track tab and the tracking cards on Home appear only when this phase ships; until then, sign-up has three steps.

**Gate:** clinicians have set every launch threshold and approved both modules; the evaluator passes the full synthetic-history suite; a test proves no API or event exposes notes.

## Phases 3 and 4: AI assist, then deeper care

Phase 3 adds AI where a person always confirms the result; Phase 4 adds the features that touch session content, behind audited consent. Every feature sits behind the safety monitor and its own feature flag.

| Phase | Epic | Delivers | Human in the loop |
| --- | --- | --- | --- |
| 3 | AI platform | Model gateway (routing, fallbacks, budgets, PII redaction); Spring AI and LangGraph4j runtime; bilingual evaluation harness; prompt and policy versions in admin | n/a |
| 3 | Tracking insights | Plain-language wording of a recommendation, from its evidence only | Rules decide; AI only explains |
| 3 | Pre-session brief | One page of shared health check answers, consented tracking trends and prior notes | Specialist reviews; seeker controls sharing |
| 3 | Moderation and translation | AI review moderation replaces manual-only; EN↔FR drafts of bios, articles and notifications | Admin or author approves |
| 4 | Session scribe | Audio egress only when both consent; transcription; SOAP or DAP draft; audio deleted after signing or within 7 days | Specialist edits and signs; seeker can refuse per session |
| 4 | Companion and homework | Journaling and exercises grounded in a vetted bilingual corpus (pgvector); homework set by the specialist; safety plan | Hands off to the specialist or crisis flow |
| 4 | Admin copilot | Questions over aggregated, de-identified metrics; anomaly alerts | Read-only |
| 4 | Reach and range | Phone dial-in through LiveKit SIP; more tracking modules built in the builder | n/a |

**Gate to Phase 3 features going live:** bilingual test sets per feature, crisis recall of at least 99% on the safety set in EN and FR, clinician-graded samples, and transfer authorisation for any non-local model (otherwise a self-hosted model behind the gateway).

**Gate to Phase 4:** the scribe and companion consent flows pass an independent audit.

## Screens by phase

All 69 designed mobile screens are assigned to a phase; screens build in the same phase as the backend epics they call.

| Screen group | Canvas screens | Phase |
| --- | --- | --- |
| Splash and onboarding | Splash, Language, Intro 1–3, Reminders permission | 1 |
| Welcome and sign-in | Welcome, Choose account type, Sign in, Verify code | 1 |
| Seeker sign-up | Details, Preferences, Privacy choices, Done | 1 |
| Seeker sign-up, tracking step | Tracking | 2 |
| Specialist sign-up | Account, Credentials, Languages, Documents, Services & prices, Weekly hours, Payouts, Under review | 1 |
| Find and book | Find a specialist, Specialist profile, Choose a session, Verify and pay, Approve payment, Booking confirmed | 1 |
| Health check | Start, Topics, Screener question, Urgent help, Preferences, Your suggestion, Matches | 2 |
| Sessions | My sessions, Session details, Cancel or reschedule, Guest booking from SMS, Waiting room, Audio call, Video call, Rate your session | 1 |
| Home | Seeker home (sessions first; tracking cards added in Phase 2) | 1 |
| Tracking | Track tab, Add a module, Log today (both modules), Trends, Recommendation | 2 |
| Between sessions | Urgent help | 1 |
| Between sessions | Companion & homework, Safety plan | 4 |
| Profile and settings | You tab, Edit profile, Privacy & data, Notifications, Settings | 1 |
| Specialist app | Today, Calendar & availability, In session, Earnings & payouts, Reviews, Profile tab | 1 |
| Specialist app, AI | Pre-session brief | 3 |
| Specialist app, scribe | Notes, Review AI note draft, Assign homework | 4 |

**Design work, all done as part of the design gate above:**

- French versions of every screen, checked for text that runs longer than English: done, on the FR pages.
- Empty, loading, error and offline states: done, as patterns on the States pages.
- Admin portal: done. Its screens map to phases the same way: 20 for launch plus first sign-in, the module and health check builders for Phase 2, AI controls for Phase 3 and the copilot for Phase 4.

## Quality, security and launch readiness

Every story meets one definition of done, and each layer of testing has a single job.

**Definition of done for every story**

- EN and FR strings both present; CI fails on a missing key.
- Unit tests, plus Testcontainers integration tests for anything touching a database, Kafka or another service.
- Contracts updated when an API, gRPC call or event changes, and the breaking-change check passes.
- Every read of clinical data writes an audit entry.
- Runs on the low-end Android baseline phone; touch targets at least 44 px; screen-reader labels on every control.
- Risky changes ship behind a feature flag.

| Layer | Tool | Covers |
| --- | --- | --- |
| Contract | Buf, OpenAPI diff, schema registry | Services and clients agree before anything deploys |
| Integration | Testcontainers | Each service with its real PostgreSQL, Kafka and Redis |
| Mobile end to end | Maestro | Guest booking, sign-up, join a call, log a day, in EN and FR |
| Admin end to end | Playwright | Specialist approval, refund, module publish |
| Load | k6 | Slot search, checkout holds, call joins at launch volume |
| Payments | Aggregator sandbox | Ledger always balances; reconciliation matches the provider |
| AI | Evaluation harness | Crisis recall and clinician-graded samples, as release gates |

**Before public launch**

- [ ] Authorisation from the Data Protection Authority, plus a data-protection impact assessment for health data
- [ ] Penetration test against OWASP ASVS and MASVS, with no open high-severity findings
- [ ] Breach runbook with the 72-hour assessment, rehearsed once
- [ ] Crisis on-call rota staffed; unacknowledged alerts go to the backup after 5 minutes and a Super admin after 10
- [ ] Retention and erasure jobs running and tested
- [ ] Dashboards and alerts for booking success, call join success, payment confirmation time and crisis acknowledgement time
- [ ] Store listings in EN and FR, and a rollback plan for each release

## Risks and fallbacks

Most risks sit outside the code, which is why their work starts in Phase 0.

| Risk | What it blocks | Fallback |
| --- | --- | --- |
| Data Protection Authority authorisation is slow, or the Authority is not yet fully operating | Public launch | File in Phase 0; run the pilot on the lawyer's advice; keep processing minimal until authorised |
| Health data must stay in Cameroon | Infrastructure choices made in Phase 0 | Decide hosting before clusters exist; keep everything in containers so it can move |
| Mobile-money aggregator is unreliable | Payments | Second aggregator behind the same port; daily reconciliation; the Approve payment screen handles slow confirmations |
| Weak networks spoil calls | Sessions | Audio-first fallback and the waiting-room network test now; phone dial-in in Phase 4 |
| Too few vetted specialists in either language | Search and matching | Recruit from Phase 0; honest empty states that suggest the other language or a later slot |
| Clinical reviewers not in place | Phase 2 gate | Recruit in Phase 0; Phase 2 can be built while thresholds wait |
| Foreign AI or speech vendors not authorised | Phase 3 and 4 features | Self-hosted models behind the model gateway |
| Crisis alerts arrive with nobody to answer | Public launch | The on-call rota is a launch checklist item, not a later improvement |

## Open questions

- [ ] **Team and date:** how many engineers, in which roles, and the target launch date? Phase durations follow from this.
- [ ] **Monorepo:** confirm one repository for services, contracts, mobile and admin.
- [ ] **Hosting:** an in-country data centre, or a regional cloud with transfer authorisation?
- [ ] **Aggregator:** which provider for MTN MoMo and Orange Money, payout cadence and commission rate?
- [ ] **Physical-health specialists:** do gynaecologists and endocrinologists join the marketplace before Phase 2, so PCOS invites stay in the app?
- [ ] **Crisis staffing:** who is on call and at what hours? The escalation times are set.
- [ ] **Pilot:** which city, how many specialists and seekers, and for how long?
- [ ] **Guest limits:** is there a cap on guest sessions before an account is required?
- [ ] **Clinical records:** does Solace keep signed session notes, or does each specialist? This shapes Phase 4.
