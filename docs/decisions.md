# Decision log

These decisions were made during design, up to 16 Oct 2026. Each one is already reflected in the specs and screens. Treat them as settled. If the code seems to need something different, raise it rather than working around it.

## Architecture

**D1. Three services, with Core as a modular monolith.**
- **Services:**
  - Core uses Spring Modulith. It covers identity, the marketplace, money, admin, notifications and the health check.
  - Tracking is its own service.
  - AI is its own service.
- **Why:** booking and payment need one transaction boundary. Module boundaries stay enforced, so a module can be split out later. Tracking and AI hold the most sensitive data and fail independently.

**D2. gRPC between services, REST at the edge, Kafka for everything else.**
- gRPC is used only when a user is waiting for the answer. Calls have 2-second deadlines, a circuit breaker and a fallback.
- Events go through the transactional outbox.
- Event payloads never contain personal or free-text data.

**D3. Separate databases.** Each service has its own PostgreSQL 16 database with its own Flyway migrations. No service reads another's tables, and there are no cross-service foreign keys.

**D4. One monorepo** (confirmed by the owner on 16 Oct 2026; layout in D22). It holds the services, contracts, mobile, admin and shared packages, so a contract change and its callers land in one PR.

## Identity

**D5. Keycloak is the single identity provider.**
- **Alternative considered:** sign-in built on Spring Security.
- **Why Keycloak:**
  - The staff rules below are configuration in Keycloak: temporary password, forced change, TOTP, lockout, session limits and a sign-in audit. Building them ourselves would mean writing and maintaining security-critical code.
  - All three services get one token format.
  - Keycloak is patched upstream.
- **Cost:**
  - One more service, with its own database.
  - A custom authenticator extension for phone and email codes.

**D6. Seekers and specialists sign in with one-time codes.**
- They get a code by phone or email and enter it on the app's own screens; there are no passwords.
- Guests have no account. Core issues a booking-scoped token, and the booking is claimed on sign-up.

**D7. Staff accounts are created only by a Super admin.**
- **Temporary password:** unique, handed over in person, works once and expires after 72 hours. The "Staff account created" email never contains a password.
- **First sign-in:** the staff member sets a new password and an authenticator app (TOTP).
- **Session:** idle sign-out after [15] minutes. Sessions end when a role changes.

## Care and safety

**D8. The health check replaces the AI intake chat and AI matching.**
- The AI intake assistant and AI matching are removed. A clinician-built health check takes their place: fixed questions configured in the admin portal, scored by rules.
- **No AI:** no AI reads the answers, and matching is deterministic.
  - **Filters:** specialty, session language, format and price band.
  - **Ranking:** topic overlap, then the soonest slot, then rating.
- **Who can take it:** anyone, before signing up. Guest answers are kept under an anonymous device token and claimed on sign-up. Unclaimed answers are deleted after [30] days.
- **Questions:** the standard screeners PHQ-2/GAD-2, expanding to PHQ-9/GAD-7, plus the clinicians' own questions. The screener wording is official and locked.
- **Scoring:** rules add points to a specialty. A second specialty within [1] point shows as "also a good fit". Ties follow the clinicians' priority order.
- **Lifecycle:** versions go through Draft, In review, Published and Retired, with an author and a different clinical reviewer.
- **Phase:** Phase 2, alongside the module builder.
- **Kept:** the companion and homework AI (Phase 4).

**D9. The health check has a locked safety rule.**
- **Triggers:** any answer other than "No" to the safety question, or PHQ-9 item 9 above 0.
- **Effect:** the check stops and urgent help opens. For a signed-in seeker, Core raises a crisis-queue alert with source `HEALTH_CHECK`. A guest is offered a call back.
- Every version carries this rule, and nobody can edit it.

**D10. Tracking states facts and never interprets them.**
- No scores, predictions or diagnoses.
- Notes are storage only.
- Engineers seed elements by migration. Admins build modules and rules in the portal, and a clinical reviewer approves them.
- The words "triage" and "severity" are banned.

**D11. Crisis escalation.**
- An alert not acknowledged within 5 minutes goes to the backup responder.
- After 5 more minutes (10 in total), it goes to the Super admins.

**D12. AI's place.** The safety monitor is the only AI feature at public launch (Phase 1). Every later AI feature sits behind a feature flag, the safety monitor and a human reviewer.

## Money

**D13. Mobile money first.**
- An aggregator handles MTN MoMo and Orange Money behind a provider port. Cards come later as a second adapter.
- Money is recorded in a double-entry ledger. Funds are held until the session completes.

**D14. Every refund needs two people.**
- Every refund, at any amount, needs a second approver in Finance who did not propose it.
- Both steps are audited.
- There is no threshold setting.
- Payout runs also need a second approver.

## Product rules

**D15. Notifications are neutral, with a discreet mode.**
- Copy is fixed in `notification-copy.md`.
- Guests get SMS. Staff get SMS only for crisis alerts.

**D16. Two languages.**
- The app language (EN/FR) and the session language are separate.
- Session languages are data.
- Only specialists with PROFESSIONAL proficiency or above can be booked in a language.

**D17. No sex at birth.** Solace never collects sex at birth. Seekers choose tracking modules themselves; each module card says who it is for and may ask one enrolment question.

**D18. Calls.**
- LiveKit, with video stepping down to audio.
- 2 minutes to reconnect.
- Join opens 10 minutes before the start.

## Design

**D19. Contrast fixes applied on 16 Oct 2026.**

| Token | New value | Notes |
| --- | --- | --- |
| `ink-faint` | #66726C | |
| `line-control` | #7F8B84 | New token for control borders, radio rings, empty stars and switch-off tracks |
| `rating-star` | #BF8126 | |
| `warning` | #A36710 | |
| `success` | #2A7F50 | |

Every pair now meets WCAG 2.1 AA.

**D20. Icons.**
- Use Lucide at stroke width 1.8. It is the closest match to the hand-drawn icons on the screens, so check each icon against its screen.
- This answers the Tracking spec's "icon set" question, unless the owner says otherwise.

**D21. Light theme only at launch.**

**D22. Repository layout: backend, mobile and admin as separate top-level folders.** Decided 16 Oct 2026 by the owner.
- `backend/` holds the Gradle build (services, libs, build-logic). `mobile/` and `admin/` are top-level apps.
- Only shared things stay at the root: `contracts/`, `packages/` (i18n, tokens, api-clients) and `infra/`.
- **Why:** the backend, the mobile app and the admin portal should be clearly separated, while one repo still lets a contract change and its callers land in one PR (D4 confirmed).
- The specs' `services/...`, `apps/mobile` and `apps/admin` paths map to `backend/services/...`, `mobile/` and `admin/`.

## Still open

None of these blocks Phase 0 except hosting, which the Phase 0 gate needs.

| Question | Blocks |
| --- | --- |
| Hosting: an in-country data centre, or a regional cloud with transfer authorisation? Needs a Cameroonian data-protection lawyer | Phase 0 gate (clusters) |
| Mobile-money aggregator, payout cadence and commission rate | Phase 1, payments epic |
| AI vendors that are acceptable under the transfer rules | Phase 3 |
| Do physical-health specialists (gynaecology, endocrinology) join before Phase 2? | Phase 2 PCOS invites |
| Crisis on-call staffing and hours | Public launch |
| A cap on guest sessions before an account is required | Phase 1, as a setting |
| Is Solace the record keeper for signed session notes? | Phase 4 |
| Team size and target launch date | Phase durations |
| Every bracketed threshold in the launch rules, set by clinicians | Phase 2 gate |
