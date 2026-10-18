# Threat model: Core

**Scope:** the Core service (Spring Modulith: identity and guest access, specialists, catalogue, health check, scheduling, bookings, sessions, payments, ratings, notifications, content, admin and config, audit) and its database, Redis keys and object-storage buckets. Status as of Phase 0: only the platform pieces (security, audit library, outbox, gRPC clients) exist; every product module arrives in Phase 1 or later.

## What is protected

| Asset | Sensitivity |
| --- | --- |
| Identity: phone, email, preferred locale; guest contacts | Personal |
| Specialist profiles, licence numbers, credential documents, payout details | Personal; documents are high value for fraud |
| Health check answers, screener totals, risk flag | **Health data** (encrypted, System Design) |
| Bookings: who sees which specialist, when, in which specialty | **Health data by inference** |
| Payments, refunds, ledger, payouts | Financial |
| Reviews and specialist replies | Personal, free text |
| Crisis alerts and outcomes | **Health data** |
| Consent records | Proof of lawful processing |
| Audit log | Integrity-critical |
| Platform settings and feature flags | Integrity-critical (pricing, refund policy, safety rule) |

## Trust boundaries and flows

1. Gateway → Core REST (seekers, guests, specialists, staff), token re-validated.
2. Core → Tracking and AI over gRPC with client-credentials tokens, 2-second deadline, circuit breaker and fallback.
3. Core → Kafka (outbox) and Kafka → Core consumers (idempotent, dead-letter topic).
4. Payment aggregator → Core webhooks; Core → aggregator (charges, refunds, payouts).
5. Core → SMS, email and push providers; Core → LiveKit (rooms, tokens) and LiveKit → Core webhooks.
6. Core → object storage (credential documents, content images).
7. Core → Keycloak admin API (staff accounts, D7).

## Threats

| ID | STRIDE | Threat | Control | Status |
| --- | --- | --- | --- | --- |
| CO-1 | S | A guest token reused for another booking or after its use | Issued only after a 6-digit SMS code check ([10] minutes, [3] tries, the code gone after use or the last wrong try; kept hashed in Redis). The token is random, stored only as a SHA-256 hash, scoped to one booking and valid until `solace.bookings.guest-token-after-end` (a day) after the session (D34). Guest paths answer only for that booking | In place (`BookingIntegrationTest.aGuestBooksWithACodeAndKeepsTheBookingOnSigningUp`); a cap on codes sent per number is not in the specs, Findings 3 |
| CO-2 | E | A seeker or specialist reads another person's booking, profile or payment by changing an id | Every query filtered by the caller's subject id or booking relationship; tests per endpoint for another person's id | In place for bookings (`BookingIntegrationTest`: another seeker gets 404); every later epic adds the same test |
| CO-3 | E | A staff member calls an admin endpoint their role does not allow | One permission matrix, `contracts/permissions/admin-permissions.json`, read by Core (`StaffAccess`) on every admin endpoint and by the portal to hide what Core refuses anyway. Only a person's token from the staff realm counts: member and service tokens get 403 whatever roles they carry | In place for Specialists (`AdminReviewIntegrationTest.thePermissionMatrixIsEnforcedOnTheServer`); every later admin endpoint uses the same check |
| CO-4 | T | Forged or replayed payment webhook marks a booking paid | Signature check and idempotency on the provider reference (CLAUDE.md, Money) | Planned (Phase 1, payments) |
| CO-5 | T / E | One person issues a refund or payout run | Every refund and payout run needs a second Finance approver who did not propose it; both steps audited (D14) | Planned (Phase 1, payments) |
| CO-6 | T | Balances changed directly in the database | Double-entry ledger; entries only appended, corrections are new entries; funds held until the session completes | Planned (Phase 1, payments) |
| CO-7 | I | Health check answers read by someone other than the seeker | Encrypted with envelope keys in Vault; shared with a specialist only when the seeker ticks "Share my answers" for that booking; every read audited | Planned (Phase 2) |
| CO-8 | R | A clinical read or admin action leaves no trace | Audit entry for every clinical read and admin action, written in its own transaction (`libs/audit`, `JdbcAuditLog`); table refuses update, delete and truncate | Library in place (`JdbcAuditLogIntegrationTest`); use Planned per epic |
| CO-9 | T | A compromised Core rewrites or deletes its own audit trail | Triggers block changes, but the service's database user owns the table and could drop them | Open; Findings 2 |
| CO-10 | I | Personal data in events | Envelope allows only a pseudonymous `subjectId`; every payload has a JSON Schema checked at publish time; breaking changes refused in CI | In place (`OutboxIntegrationTest.envelopeRejectsValuesTheSchemaWouldRefuse`, `contracts/scripts`) |
| CO-11 | I | Personal data in logs and traces | Scrubbing appender on every log line; code logs ids and outcomes only | In place (`ScrubberTest`, `ScrubbingAppenderTest`) |
| CO-12 | T | A Kafka event processed twice, or a poison message blocks a consumer | Idempotent consumers on `eventId`; failures to a dead-letter topic | In place (`OutboxIntegrationTest.redeliveredEventIsHandledOnce`, `failingAndUnreadableRecordsEndUpInTheDeadLetterTopic`) |
| CO-13 | D | A booking and its event disagree after a crash | Transactional outbox: the event commits with the booking | In place (`OutboxIntegrationTest.rolledBackChangePublishesNothing`) |
| CO-14 | D | Slots held by one person to block a specialist's calendar | 10-minute holds in Redis that expire on their own; holds are only a courtesy, the `calendar_entry_no_overlap` exclusion constraint is what makes double booking impossible; the gateway rate-limits each caller | In place (`SchedulingIntegrationTest`); a cap on holds per person is not in the specs, Findings 3 |
| CO-15 | D | Crisis alerts missed because nobody acknowledges | Escalation to the backup responder at 5 minutes and to Super admins at 10 (D11); staff get SMS for crisis alerts (D15) | Planned (Phase 1, safety monitor) |
| CO-16 | S / D | Fake guest call-back requests flood the crisis queue | Not specified | Open; Findings 3 |
| CO-17 | I | Credential documents exposed from object storage | Private bucket with no public access; server-side encryption set in the cluster (`SOLACE_STORAGE_SERVER_SIDE_ENCRYPTION`); objects under random keys, never the uploaded name; the applicant sees only names and sizes back. Reviewers open files only through Core, with view access to Specialists; each opening is audited before any byte is read, and served `no-store` | In place (`SpecialistApplicationIntegrationTest.specialistAppliesStepByStepAndSubmits`, `AdminReviewIntegrationTest.openingADocumentIsAuditedFirst`) |
| CO-30 | T / I | A harmful file uploaded as a credential: a script or office document renamed `.pdf`, or an oversized file | Type read from the file's signature only (PDF, JPEG, PNG); the profile photo must be an image; one file per kind (two for the identity document); size capped by `solace.specialists.documents.max-file-size` (10 MB, a setting) | In place (`SpecialistApplicationIntegrationTest.eachStepKeepsToItsRules`); malware scanning not specified, Findings 7 |
| CO-31 | T | An applicant changes their licence, prices or payout details after review has started | The application is editable only as a DRAFT; once submitted every step answers 409 until a reviewer asks for changes | In place (`SpecialistApplicationIntegrationTest.specialistAppliesStepByStepAndSubmits`) |
| CO-18 | S | Someone joins a session they are not booked into | Core mints a LiveKit token per participant, scoped to the room, from 10 minutes before the start to 15 minutes after the end, only for the booked seeker (account or guest token) or the specialist | Planned (Phase 1, sessions) |
| CO-19 | I | A session recorded without consent | No recording by default; audio-only egress only when both people consented to the scribe | Planned (Phase 4) |
| CO-20 | I | A notification reveals a condition, a specialty or a mood | Neutral copy fixed in `notification-copy.md`; discreet mode; no health data in push payloads | Planned (Phase 1, notifications) |
| CO-21 | S | Account takeover by changing the phone number or email to the attacker's | Not specified | Open; Findings 4 |
| CO-22 | S | Someone claims another person's guest bookings, for example by typing a victim's email at sign-up next to their own phone | Keycloak records which contact the code went to (`verified_contact` claim); Core marks only that contact verified and publishes `ContactVerified` for it alone. Bookings are claimed only through a verified contact (D6) | In place (`SignupFlowIntegrationTest.onlyTheContactTheCodeWentToCountsAsVerified`, `BookingIntegrationTest.aGuestBooksWithACodeAndKeepsTheBookingOnSigningUp`) |
| CO-28 | I | Sign-up used to find out who has an account | The same 202 whatever the number; an active account is never changed; an email already used elsewhere is dropped silently rather than refused | In place (`SignupFlowIntegrationTest.signingUpAgainWithARegisteredNumberLooksTheSameAndChangesNothing`) |
| CO-29 | S / D | Accounts created with other people's numbers pile up, or block the real owner | Unverified accounts are replaced by a new sign-up with the same number (a seeker sign-up and a specialist sign-up replace each other the same way) and removed with their Keycloak user after `solace.identity.unverified-signup-ttl` (24 hours, a setting; not in the specs) | In place (`SignupFlowIntegrationTest.unverifiedAccountsAreRemovedWithTheirKeycloakUser`) |
| CO-32 | I | Public search and profiles reveal more than a listed profile should, or list someone not ready (unverified language, unchecked bio) | Search and profile are open to guests but return only listed fields: name, profession, bio, verified bookable languages, sessions and prices, focus areas, next start. No contact details, licence number, documents or payout details. Listing needs approval, a verified Professional-or-above level in the session language and a confirmed bilingual bio (D30, D31). Rate-limited at the gateway | In place (`DirectoryIntegrationTest`) |
| CO-23 | E | A staff member keeps old permissions after a role change | Sessions end when a role changes (D7) | Planned (Phase 1, Team and roles) |
| CO-24 | T | A setting changed without trace, or the health check's safety rule edited | Versioned settings with audit trail; the safety rule is locked in every version (D9) | Planned (admin portal v1, Phase 2) |
| CO-25 | I | A data export reaches the wrong person | Export only for the signed-in seeker, delivered in the app | Planned (epic 11); details open |
| CO-26 | D | Tracking or AI down makes Core hang | 2-second deadline, circuit breaker and a defined fallback for every call | In place (`libs/grpc-client`, `GrpcClientDefaultsTest`, `SystemPingTest`) |
| CO-27 | E | Core's service account used to call Tracking or AI methods it should not | Narrow scopes per gRPC method; callee checks audience and scope | In place (`GrpcSecurityTest`) |

## Findings

1. **Server-side permissions (CO-3).** Resolved: the matrix is a contract (`contracts/permissions/admin-permissions.json`) that Core enforces and the portal reads. Each new admin endpoint must call `StaffAccess` and add a 403 test for a role outside the matrix.
2. **Audit log tamper resistance (CO-9).** The append-only triggers stop mistakes, not a compromised service: the table owner can drop a trigger. Proposal: run Flyway as a migration role that owns the tables, run the service as a role with only `INSERT` and `SELECT` on `audit_log`, and ship audit rows to a store the services cannot write to (for example object storage with object lock). How long audit rows are kept is not in the specs, so deletion is impossible today; decide the period with the lawyer (see the register).
3. **Abuse limits not in the specs (CO-1, CO-14, CO-16).** Caps on slot holds per person, on guest codes sent per phone number (each one is a paid SMS) and on guest call-back requests per contact. Until then the gateway's per-caller rate limit is the only brake. The numbers need the owner; implement them as settings.
4. **Changing a contact (CO-21).** Changing the phone number or email should need a code sent to the new contact and a notice to the old one. Not in the specs or the notification copy; raise before Phase 1 profile editing.
5. **Erasure and the audit log.** `core.user.erased` must delete or anonymise everything within 30 days, but audit rows are append-only and keep the pseudonymous subject id. Ask the lawyer whether keeping pseudonymous audit rows is allowed as a legal obligation, and for how long.
6. **Help topics are health data (P1).** A seeker's "What would you like help with" choices (anxiety, low mood, grief...) say something about their health, though no spec classes them as clinical data. Today they are stored plainly in Core, like preferences. Decide whether they are encrypted like health check answers and whether reading them is audited.
7. **Uploaded files are not scanned (CO-30).** Only the file signature is checked. A PDF can still carry active content, and the profile photo will later be shown to seekers. Proposal: scan uploads before a reviewer opens them, and re-encode the profile photo before it is published. Neither is in the specs; raise before the admin review screens ship.
8. **Specialist approvals rest on one reviewer.** Resolved by D29: one person proposes, a different one confirms; tested in `AdminReviewIntegrationTest`.
