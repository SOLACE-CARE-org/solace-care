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

**D23. Two Keycloak realms: `solace-members` and `solace-staff`.** Decided 16 Oct 2026 by the owner.
- `solace-members`: seekers and specialists. One-time code sign-in through the custom authenticator; roles SEEKER and SPECIALIST pick the app shell after sign-in.
- `solace-staff`: admin portal users. Password plus TOTP, 72-hour single-use temporary password, `[15]`-minute idle sign-out, no self-registration. Roles ADMIN, SUPER_ADMIN, SUPPORT, CLINICAL_REVIEWER and the admin portal's other roles.
- **Why:** the System Design's infrastructure table says seekers, specialists and admins sit in separate realms, but the Sign-in screen has one phone/email field with no role choice, and the app "switches shell by role after login". Seekers and specialists therefore share a realm; staff stay fully isolated.
- This replaces "separate realms" in the System Design's infrastructure table.

**D24. Specialists sign in with one-time codes, like seekers.** Decided 17 Oct 2026 by the owner.
- The specialist account screen (SpecSignup) loses its "Create a password" field and its help line. Specialists get a code by phone or email at each sign-in, as D6 says.
- **Why:** D6 and D23 already settle member sign-in as codes only. A password plus a code would need a second Keycloak flow in the members realm for one group of members.

**D25. Staff book the onboarding interview.** Decided 17 Oct 2026 by the owner.
- A reviewer sets the interview time in the admin portal. The specialist gets the "Interview booked" push (notification-copy.md).
- The "Pick an interview time" button on SpecReview ships behind the `specialist-interview-picker` flag, off, until someone specifies where interview times come from.

**D26. Working defaults for specialist onboarding.** Taken 17 Oct 2026, with the owner's go-ahead on recommendations; any of them can be changed.
- **Professions** are catalogue rows, not an enum, seeded with the five on SpecProfessional. Gynaecologist and Endocrinologist are seeded but disabled until the open question on physical-health specialists is answered.
- **Areas you work with** are the help topics seekers choose, so matching can compare the two (System Design, Matching). The chips on SpecProfessional show whatever topics the catalogue holds.
- **Bank transfer** on SpecPayout is behind the `payout-bank-transfer` flag, off: no screen or spec says which bank details to collect. Mobile money (MTN, Orange) works now.
- **Application states** are the six in the System Design (DRAFT, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, SUSPENDED). A change request sends the application back to DRAFT with a reason; a Super admin can reinstate a suspended specialist.
- **Guest access** (epic 1's booking-scoped token and claiming) is built with Bookings (epic 5), because the token is scoped to a booking and claiming moves bookings. Core already publishes `ContactVerified` for it.

**D27. Session prices: 5,000 to 20,000 FCFA.** Decided 17 Oct 2026 by the owner.
- SpecServices' "Allowed range: [Min] to [Max] FCFA per session" is 5,000 to 20,000 FCFA. It stays a setting (`solace.specialists.pricing.min-price` and `max-price`) with these defaults, for admin settings to take over.
- Solace's fee ("[n]%") is still open with the commission rate below.

**D28. Solace's fee: 20% for now, as a setting.** Decided 18 Oct 2026 by the owner.
- `solace.specialists.pricing.fee-percent` defaults to 20. It is configuration, for admin settings to take over, and can change without a release.

**D29. Approving a specialist takes two people.** Decided 18 Oct 2026 by the owner.
- Like refunds (D14): the reviewer who verified the six items proposes the approval; a second staff member with change access to Specialists, who did not propose it, confirms it. Either can withdraw the proposal, which sends the application back to review.
- Any change to the verified items after a proposal withdraws it.
- Rejection, change requests and suspension stay with one person: they stop or protect, and should not wait.

**D30. The second-language bio until the AI draft arrives.** Taken 18 Oct 2026 on the owner's request for a recommendation.
- Until Phase 3 drafts it, the reviewer writes the missing language during review. It is marked as a Solace draft.
- The specialist checks the draft before their profile goes live, as SpecProfessional promises ("We'll draft the ... version for you to check before it goes live"). The check is built with the specialist's Profile tab; until it is done, search does not list the profile (epic 3).

**D31. Specialties for the catalogue and search (epic 3).** Working default, 18 Oct 2026; any of it can change.
- Specialties are catalogue rows in EN and FR (System Design, data model `specialty`): clinical psychology, counselling, psychiatry, gynaecology, endocrinology. The last two stay disabled with their professions (D26). Admins will manage the list (Catalogue and policies).
- Each profession belongs to one specialty, and a specialist's sessions carry it. A specialist with several specialties waits for profile editing.
- Search lists only approved specialists with a verified level of Professional or above in the chosen session language, an enabled session, and a bio in EN and FR whose Solace draft, if any, the specialist has confirmed (D30).
- "Next available" comes from weekly hours, notice and break until Scheduling (epic 4) subtracts bookings and holds. Ratings appear with Ratings and reviews (epic 8).

**D32. The booking lifecycle (epic 5).** Taken 18 Oct 2026 under the owner's standing instruction to choose the best option when the specs are silent. The System Design's diagram ("4 main states, 3 exits") was lost in the export.
- Main states: PENDING_PAYMENT → CONFIRMED → IN_PROGRESS → COMPLETED. Exits: EXPIRED (not paid within the hold window), CANCELLED, NO_SHOW.
- Creating a booking turns the 10-minute hold into a calendar entry at once, so the exclusion constraint guards the time while the seeker pays. EXPIRED and CANCELLED free it.
- Money follows the System Design: captured at CONFIRMED, released at COMPLETED (epic 6).

**D33. Cancellation and reschedule policy, as versioned admin settings.** Taken 18 Oct 2026; the specs give no numbers.
- Defaults: a full refund when cancelled 24 hours or more before the start, half between 24 and 2 hours, none under 2 hours; a seeker no-show gets nothing back, a specialist no-show everything. Up to 2 reschedules, at least 2 hours before the start, to the same specialist at no charge (CancelSession).
- Each booking keeps the policy version in force when it was made (Admin Portal Spec, Refund or no-show dispute). Admins publish new versions in Catalogue and policies.

**D34. Guest access (epic 1's guest part, built with bookings).** Taken 18 Oct 2026.
- A guest proves a phone number with a 6-digit code that Core sends by SMS (kept in Redis, [10] minutes, [3] tries). The code check creates the booking and returns a guest token: random, stored hashed, scoped to that one booking, valid until a day after the session. Guests have no Keycloak account (D6).
- Guest calls go to `/v1/guest/**`, open at the gateway; Core checks the guest token itself.
- When a member's first sign-in proves the same phone or email (`ContactVerified`), their guest bookings move into the account.

**D35. Payments: aggregator, cadence and who approves what (epic 6).** Taken 18 Oct 2026 on the owner's instruction to choose the best option where the specs are open.
- **Aggregator:** Notch Pay is the first adapter behind the provider port. Of the Cameroon-focused aggregators checked, it documents MTN MoMo and Orange Money collection, transfers to both wallets (used for refunds and payouts), signed webhooks (HMAC-SHA256 of the raw body in `X-Notch-Signature`) with retries, and a test mode. Its docs show no refund call, so a refund is a transfer back to the wallet that paid. A `sandbox` adapter serves local development and tests; `solace.payments.provider` picks one. Cards stay a later second adapter, behind the `cardPayments` flag.
- **Payout cadence:** twice a month, the 1st to the 15th and the 16th to the month's end, as the Payout run screen shows. `solace.payments.payout-period` is a setting. Core drafts each run when its period closes; a Finance member submits it and a different Finance member or a Super admin approves and sends it (D14). Pay out now on Earnings stays off (`payoutOnRequest` flag): an off-cycle payout would bypass the two-person run.
- **Refunds proposed by policy:** when a paid booking is cancelled or ends in a no-show, Core proposes the refund the booking's policy gives (D33), as the system. One Finance member then approves or declines it; they are the second person, because nobody on staff proposed it. A refund proposed by a staff member (a dispute) still needs a different Finance member to approve it.
- **Retained money:** what a policy does not refund goes to the specialist like a completed session, less Solace's fee (D28).
- **Fee snapshot:** the fee percent in force when the payment is taken is kept with the payment; a later change to the fee does not change it.

## Still open

None of these blocks Phase 0 except hosting, which the Phase 0 gate needs.

| Question | Blocks |
| --- | --- |
| Hosting: an in-country data centre, or a regional cloud with transfer authorisation? Needs a Cameroonian data-protection lawyer | Phase 0 gate (clusters) |
| AI vendors that are acceptable under the transfer rules | Phase 3 |
| Do physical-health specialists (gynaecology, endocrinology) join before Phase 2? | Phase 2 PCOS invites |
| Crisis on-call staffing and hours | Public launch |
| A cap on guest sessions before an account is required | Phase 1, as a setting |
| Is Solace the record keeper for signed session notes? | Phase 4 |
| Team size and target launch date | Phase durations |
| Every bracketed threshold in the launch rules, set by clinicians | Phase 2 gate |
