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

**D36. Sessions: rooms, attendance and no-shows (epic 7).** Taken 18 Oct 2026 on the owner's instruction to choose where the specs are open.
- **Rooms:** Core creates the LiveKit room on the first join-token request, from 10 minutes before the start, not at confirmation: LiveKit closes a room nobody joins, and a session can be days away. The room keeps a dropped participant for 2 minutes (D18) and admits two people.
- **No names to the media server:** participants join as `seeker` or `specialist`; each app already knows who the other person is from the booking.
- **Starting and ending:** the session starts (IN_PROGRESS) when both are in the room, and completes when the room finishes after both attended, or `solace.sessions.complete-after` ([15] minutes) after the scheduled end.
- **No-shows:** `solace.sessions.no-show-after` ([15] minutes) after the start, a booking where only one person ever joined becomes a no-show by the other (D33 refunds). When neither joined, Core does not guess: the booking is flagged for Support to resolve (Refund or no-show dispute), and the money stays held.

**D37. Ratings and reviews (epic 8).** Taken 18 Oct 2026 on the owner's instruction to choose where the specs are open.
- One rating per completed booking, from its seeker or guest, within `solace.reviews.window` ([14] days) of the session.
- Stars count towards the specialist's average at once; they carry no words. The written review waits in the moderation queue (admin screen 13) and appears on the profile only when staff publish it. Seekers stay anonymous everywhere: the specialist sees a date and the session language, staff see a pseudonymous reference.
- Core flags contact details (phone numbers, emails, links) in review text; staff may publish with them masked. The safety monitor's check (epic 10) comes before the queue: a review it flags never reaches the queue or the profile.
- "Tell the Solace team privately" is kept with the rating for staff (Support, booking detail) and never published or shown to the specialist.
- A specialist may reply once to a published review. A reply with contact details is refused rather than moderated, since the specialist can simply write it again.
- Search shows a rating once a specialist has `solace.reviews.min-ratings-shown` ([3]) ratings, so one opinion is not shown as an average; the rating filter uses the same rule.

**D38. Notifications (epic 9).** Taken 18 Oct 2026 on the owner's instruction to choose where the specs are open.
- **Push** goes through Expo's push service (APNs and FCM behind it), behind a sender port; a `logging` sender serves local runs. Devices register their push token and time zone after sign-in and drop it on sign-out; a token Expo reports as unregistered is deleted. Push payloads carry the message and a booking id at most.
- **Time zone:** a member's latest device's, a specialist's weekly-hours zone, otherwise Africa/Douala.
- **Quiet hours:** `solace.notifications.quiet-hours` ([21:00]–[07:00]) in the recipient's zone, per person with that default. Only messages that can wait (day-before reminder, rating ask, new review) wait for the end; session, payment and cancellation messages go at once.
- **Reminders:** the day before at the same time (skipped when the booking is made less than 24 hours ahead), and when joining opens, 10 minutes before (D18). Session reminders can be turned off in Settings; joining-opens messages cannot.
- **Guest links:** `{link}` is `solace.notifications.link-base` (`https://slc.cm/g/`) plus a fresh 14-character booking-scoped guest token, so every SMS stays within 160 GSM-7 characters. French placeholders lose the accents the copy rules forbid (août becomes aout), as the copy already does for "anxiété aigue".
- **In-app:** members also find each message in the Notifications inbox, worded as the push.
- **Staff pushes** (refunds and payout runs waiting, module submitted) arrive with the admin portal's web push (epic 12); crisis SMS with the safety monitor (epic 10).

**D39. The safety monitor and the crisis queue (epic 10).** Taken 18 Oct 2026 on the owner's instruction to choose where the specs are open.
- **Classifier until a model is approved:** the AI service checks text with a bilingual (EN, FR, code-switched) phrase classifier tuned to over-flag, which threat-model-ai accepts. A model adapter (Spring AI, self-hosted so no transfer authorisation is needed) sits behind `solace.safety.model.enabled`, off until the model is chosen and passes the 99% crisis-recall gate on the EN and FR sets; when on, text is flagged if either flags it. Each decision keeps the classifier and policy version (AI-9), never the text.
- **Flow:** Core asks the AI service to moderate each written review (2-second deadline; on failure the review simply waits for a person, AI-4). A flag is answered at once, so the review never reaches the queue or the profile (D37), and published as `ai.risk.flagged` with ids, the category and versions only. Core turns the event into a crisis alert.
- **On call:** a rota kept by Super admins in Core (who is primary, who is backup, their work phones, from when to when) until staffing is decided (Still open). The primary gets an SMS at once, the backup at 5 minutes, Super admins on the rota at 10 (D11, D15). Staff pushes join with the portal's web push (D38).
- **Urgent help:** the app carries Cameroon's emergency numbers, police 117, fire 118 and ambulance (SAMU) 119, so they work offline; the Content module will override them per country (epic 12). The numbers need the owner's confirmation before launch. "Talk to an on-call specialist" stays behind the `onCallSpecialistCall` flag until that service is specified; "Message someone you trust" comes with the safety plan (Phase 4). "Breathe with me" works offline.

**D40. Privacy: export, erasure and retention (epic 11).** Taken 18 Oct 2026 on the owner's instruction to choose where the specs are open.
- **Export:** "Download a copy of my data" prepares one JSON file of everything Core holds about the person (account, preferences, consent history, bookings, payments and refunds, ratings, messages). It is kept in private object storage for [7] days (`solace.privacy.export-lifetime`) and downloaded through Core with the person's token; never a public link. Tracking and the health check add their parts when they ship (Phase 2). The person is told by push and email (notification-copy.md).
- **Erasure, seekers:** self-service from Privacy & data. Refused while a paid session is upcoming or in progress, or a refund is still open, so no money is stranded; the screen says why. The account stays only as an ERASED tombstone with no name, contact or sign-in, so bookings and the ledger keep their accounting record (legal obligation, **Counsel** to confirm the period); payer phone numbers on its payments are removed; ratings lose their words, keep their stars; consent history stays, pseudonymous, as proof of lawful processing. `core.user.erased` tells Tracking and AI to delete theirs. The "Account deleted" email goes before the address is erased.
- **Erasure, specialists:** the button files a data request for Operations (licence documents, payouts and patients' records need checking), answered within `solace.privacy.request-deadline` ([30] days, the legal deadline is still open).
- **Retention:** guest contacts lose their phone `solace.privacy.guest-retention` ([12] months) after their last booking (System Design); delivered messages are deleted after `solace.privacy.message-retention` ([180] days, delivery-record retention is open); expired export files are deleted.

**D41. Admin portal v1: bookings, disputes and exports (epic 12).** Taken 18 Oct 2026 on the owner's instruction to choose where the specs are open.
- **Booking reference:** staff see a booking as `BK-` plus the first eight characters of its id (BK-7F9D1C2E), short enough to read out on the phone. Searches take it, the full id, or a longer prefix.
- **Disputed:** a booking is shown as disputed while Support has an open dispute on it, or while Core has flagged it because neither person joined (D36). Support opens a dispute with a short note of what they were told.
- **Resolving:** Support picks who missed the session. "Specialist was late" or "seeker left early" applies the no-show percent of the booking's own policy (D33). A booking Core has not settled yet becomes a no-show, and the policy refund follows as the system's proposal (D35). A settled booking gets a refund proposed by the staff member, at the policy amount unless they change it with a reason; a different Finance member approves it (D14). "Connection problem" counts as nobody's fault: the full price is proposed back, so the seeker can book the time again at no cost, and the specialist keeps what the new session pays. Every step is audited.
- **Not in v1:** staff do not book or move a session for someone (the seeker reschedules in the app; Support talks them through it), and the crisis queue has no separate Assign (acknowledging takes the alert; the rota decides who is called). Notification copy has no dispute message, so both parties hear through the refund messages and from Support. These wait for copy and a slot picker for staff.
- **Exports:** every CSV export (bookings, payout report, audit log) is made by Core, not the browser, so it is audited like any other admin action. A cell that starts with = + - or @ gets a leading apostrophe so a spreadsheet does not run it.

**D42. Admin portal v1: settings, flags, team and the audit log (epic 12).** Taken 18 Oct 2026 on the owner's instruction to choose where the specs are open.
- **Settings:** the Settings and flags screen lists every bracketed business number Core uses (commission, price range, no-show and completion windows, rating window and minimum, quiet hours, privacy periods, guest code lifetime and tries). A value set there is stored as a new version in `platform_setting` with the staff member's reason and wins over the configured default; Core reads it at the moment of use and reloads every [30] seconds (`solace.admin.settings-refresh`), so a change reaches every instance within a minute, and `core.config.changed` tells Tracking and AI. Fixed decisions (the 10-minute hold, two people for every refund, crisis escalation at 5 and 10 minutes) are shown and cannot be changed. Staff idle sign-out stays a staff-realm setting (`SOLACE_STAFF_IDLE_TIMEOUT_SECONDS`): Core's Keycloak client may manage users, not the realm.
- **Payout cadence** stays twice a month (D35) and is not on the screen until a second cadence is specified.
- **Flags:** stored the same way, versioned with a reason. The apps read them from `GET /v1/config` (no token, cached a minute). The safety monitor is locked on and Pay out now is locked off (D35); later-phase flags can be seen but their screens still ship with their phase.
- **Team:** a Super admin adds staff by name, email and roles; Core creates the Keycloak account with a 16-character temporary password, shown once and never stored, plus a new password and the authenticator as required actions (D7). A new password also resets the authenticator. A role change, a new password or deactivation logs the person out of Keycloak and Core refuses any token issued before it. A Super admin cannot remove their own Super admin role or deactivate themselves.
- **Audit log:** Core keeps a staff directory (names, last active) so entries show staff by name; people appear as pseudonymous references and bookings as BK- references. Filters: contact revealed, crisis alert viewed, refunds, status changes, setting changes, break-glass. Admin actions now record the reason given. Export is capped at 10,000 entries and is itself audited.

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
