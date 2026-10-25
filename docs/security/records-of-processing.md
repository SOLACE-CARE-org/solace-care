# Records of processing

The register of every way Solace processes personal data, for Cameroon's Law No. 2024/017 (System Design, Security, privacy and compliance). It is the basis of the authorisation filing with the Data Protection Authority and of the impact assessment for health data.

**Status: draft for review.** Built from the specs as of Phase 0; nothing below processes real people's data yet. Items marked **Open** need a decision; **Counsel** items need the data-protection lawyer. Retention periods are given only where a spec or decision sets them; every other period is Open rather than guessed.

## Controller and processors

| Role | Who | Status |
| --- | --- | --- |
| Controller | Solace Care (legal entity, address and contact to be filled in) | Open |
| Data-protection contact | To be named | Open |
| Hosting | In-country data centre or a regional cloud with transfer authorisation | Open (decisions.md, blocks the Phase 0 gate) |
| SMS provider | Sends sign-in codes and guest notifications, through any gateway set up as a `message_sender` row (D61). Receives the phone number and the message | Open: no vendor chosen (contract in Phase 0, outside engineering). Each gateway needs its own row here before it goes live |
| Email provider | Codes, notifications, staff account emails, through any mail server set up as a `message_sender` row (D61) | Open: no vendor chosen. Each mail server needs its own row here before it goes live |
| Browser push for staff | The staff member's browser push service (Google FCM, Mozilla, Apple), D47. Receives an encrypted message it cannot read and an endpoint for one browser; no personal details | Chosen by each browser; listed for transparency |
| Push notifications | Expo's push service (D38), delivering through Apple (APNs) and Google (FCM). Receives the device token and the message text (neutral copy, or "you have a reminder" in discreet mode) | Chosen; data-processing terms and transfer authorisation open (**Counsel**) |
| Mobile-money aggregator | Notch Pay (D35): MTN MoMo and Orange Money charges; refunds and payouts as transfers. Receives the payer's or specialist's phone, the amount and Solace's reference | Chosen; contract and data-processing terms open (**Counsel**), and whether it processes in Cameroon |
| Further payment providers | Any account added as a `payment_provider` row (D61). A LIVE provider becomes active only after a Super admin confirms it has an entry in this register, a threat-model entry, a contract and test transactions; the confirmation is kept with the provider's version | Each one needs its own row here before it goes live |
| Media server | LiveKit, self-hosted or LiveKit Cloud | Open, follows hosting |
| Phone dial-in | A SIP trunk with a Cameroonian number, into LiveKit SIP (D59). Carries the caller's phone number and the call audio; the PIN routes the call to one booking | Open (contract outside engineering); off until contracted |
| AI model vendors | Safety monitor classifier (Phase 1); later AI features (Phase 3, 4) | Open (decisions.md); see AI-1 in the AI threat model |

Every processor needs a contract that forbids other use and, for AI vendors, training on Solace data. Any processor outside Cameroon needs the Authority's transfer authorisation (**Counsel**).

## Summary

| ID | Activity | Phase | Health data | Consent purpose |
| --- | --- | --- | --- | --- |
| P1 | Member accounts and sign-in | 1 | **Help topics** | |
| P2 | Guest booking and contact verification | 1 | By inference | |
| P3 | Staff accounts and sign-in | 0–1 | No | |
| P4 | Specialist onboarding and verification | 1 | No | |
| P5 | Booking, scheduling and sessions | 1 | **By inference** | |
| P6 | Payments, refunds and payouts | 1 | By inference | |
| P7 | Ratings and reviews, with AI moderation | 1 | Possibly (free text) | |
| P8 | Notifications | 1 | No (neutral copy) | |
| P9 | Crisis alerts and the safety monitor | 1 | **Yes** | |
| P10 | Consent records | 1 | No | |
| P11 | Data-subject requests (export, erasure) | 1 | Touches all | |
| P12 | Audit log | 0 onwards | Ids only | |
| P13 | Security and operations telemetry | 0 onwards | No (scrubbed) | |
| P14 | Health check | 2 | **Yes** | HEALTH_CHECK |
| P15 | Tracking | 2 | **Yes** | TRACKING; separate grants for specialist sharing and for notes |
| P16 | AI features after launch | 3–4 | **Yes** | Per feature (AI_SCRIBE, ...) |
| P17 | Analytics and the admin copilot | 1, 4 | No (aggregated) | |

Legal bases below are proposals for **Counsel**: health data relies on explicit, per-purpose, versioned consent (System Design, Controls); bookings and payments on the contract with the seeker; accounting on legal obligation; security logs on legitimate interest; crisis response possibly on vital interests.

## Activities

### P1. Member accounts and sign-in

| | |
| --- | --- |
| Purpose | Let seekers and specialists sign in with a one-time code and keep an account |
| People | Seekers, specialists |
| Data | Phone number and/or email (and when each was proven by a code), preferred app language, role, account status, the Terms and privacy-notice versions accepted; for seekers: the name they want to be called (only their specialist sees it), date of birth (18 and over only), session languages, help topics and specialist-gender preference; sign-in events (time, client, IP address, outcome) |
| Health data | **Help topics** (anxiety, low mood, grief...) reveal health concerns: treat as health data (**Open**: encryption and audit, Core threat model Findings 6) |
| Where | Core database; Keycloak database (`solace-members`) |
| Recipients | SMS and email providers (the code and the contact) |
| Transfers abroad | Depends on the SMS and email providers (**Counsel**) |
| Retention | While the account exists. Erasure (D40) runs at once for seekers and within `solace.privacy.request-deadline` ([30] days) for specialists; it leaves a tombstone id with no contact so bookings and the ledger keep their accounting record, and keeps the pseudonymous consent history as proof (**Counsel**). An account whose first sign-in never comes is removed after 24 hours (`solace.identity.unverified-signup-ttl`, a setting, not in the specs). Sign-in events 90 days, a setting (`SOLACE_MEMBERS_EVENTS_EXPIRATION_SECONDS`), not set by any spec (**Open**) |
| Security | Threat model EI-1 to EI-5, EI-19 |

### P2. Guest booking and contact verification

| | |
| --- | --- |
| Purpose | Let a person book, pay, join and rate a session without an account |
| People | Guests |
| Data | Phone number or email, verification time, bookings and payments made as a guest, claimed-by account |
| Where | Core database |
| Recipients | SMS provider; aggregator (payment) |
| Retention | Phone and links purged `solace.privacy.guest-retention` ([12] months) after the guest last proved the number, which every booking does (D40); claimed into an account when the guest registers with the same contact |
| Security | CO-1, CO-22 |

### P3. Staff accounts and sign-in

| | |
| --- | --- |
| Purpose | Give staff access to the admin portal for their role |
| People | Staff (7 roles, Admin Portal Spec) |
| Data | Name, email, role, password hash, authenticator secret, sign-in and admin events |
| Where | Keycloak database (`solace-staff`); Core's `staff_member` (name, email, roles, status, last active, when sessions were ended; D42). Temporary passwords are generated by Core, shown once to the Super admin and never stored |
| Recipients | Email provider ("Staff account created", without a password, D7; "Data requests due" to Operations, D47); browser push services (encrypted staff notifications, D47) |
| Retention | Account: **Open** (for example until leaving plus a period). Events 90 days in the realm settings (**Open**: no spec sets it) |
| Security | EI-6 to EI-10, EI-18 |

### P4. Specialist onboarding and verification

| | |
| --- | --- |
| Purpose | Check that specialists are licensed and who they say they are before they can be booked |
| People | Specialist applicants and specialists |
| Data | From sign-up (Phase 1, epic 2): full name as on the licence, professional email, phone number, Specialist terms and Code of conduct versions accepted; profession, licence number, issuing body, years in practice, areas worked with (help topics), a short bio in the language written (the other language drafted later); declared session languages and levels, and when a reviewer confirmed each; licence, identity document (front and back), profile photo and optional CV; session types, lengths and prices; weekly hours and time zone; payout method, mobile money number and account holder name; application status, submission time, interview time and reviewer notes. Later: reference checks and verification history |
| Where | Core database; documents in the private `solace-credentials` bucket, encrypted at rest by the object store, one object per file under a random key |
| Recipients | Staff with the right role; the public sees the published profile only |
| Retention | **Open**: documents of rejected applicants; documents after a specialist leaves |
| Security | CO-17 |

### P5. Booking, scheduling and sessions

| | |
| --- | --- |
| Purpose | Book, hold, confirm, run and complete audio or video sessions |
| People | Seekers, guests, specialists |
| Data | Booking (who, with whom, specialty and offering, session language, times, status, source), slot holds, join and leave events (role and time only: the media server never receives a name, D36). Audio and video pass through the media server and are **not recorded** (System Design, Recording) |
| Where | Core database; Redis (10-minute holds); LiveKit (live media only) |
| Recipients | The two participants; LiveKit if LiveKit Cloud is chosen |
| Transfers abroad | Live media, if the media server is outside Cameroon (**Counsel**) |
| Retention | **Open**: booking history; attendance events |
| Security | CO-2, CO-14, CO-18 |

### P6. Payments, refunds and payouts

| | |
| --- | --- |
| Purpose | Charge for sessions, hold funds until completion, refund, pay specialists |
| People | Seekers, guests, specialists |
| Data | Amounts, method (MoMo, Orange Money, later card), the payer's wallet number (kept to send a refund back to the same wallet), the specialist's payout number, provider references, status, ledger entries, refund proposals and approvals (two people, D14), payout runs, webhook event ids, daily reconciliation results. Card data never touches Solace (System Design). Staff screens show wallet numbers masked |
| Where | Core database |
| Recipients | Mobile-money aggregator; later a card PSP |
| Retention | Accounting-law period (likely the OHADA 10 years; **Counsel**) |
| Security | CO-4 to CO-6 |

### P7. Ratings and reviews

| | |
| --- | --- |
| Purpose | Let seekers rate a completed session; moderate before publishing |
| People | Seekers, guests, specialists (replies) |
| Data | Stars and what stood out, the written review (free text, may mention health), a private note for Solace staff only, the pseudonymous subject id (never shown: staff see a short reference, the specialist a date and the session language), contact details masked before publishing, moderation decision and moderator, specialist reply (D37) |
| Where | Core database; text sent to the AI service for moderation and the safety monitor |
| Recipients | The public (published reviews); AI model vendor (**Open**, AI-1) |
| Retention | **Open**. Prompts and outputs in the AI service: 30 days (System Design) |
| Security | AI-1 to AI-4, AI-9 |

### P8. Notifications

| | |
| --- | --- |
| Purpose | Send booking, session, payment and account messages |
| People | All |
| Data | Contact or push token with the device's time zone, locale, template and its placeholders; for members the pushed text (the in-app inbox); for SMS and email only the kind and time, never the text. Copy is neutral: never a condition, a specialty, a mood, "therapy" or "mental health" (`notification-copy.md`); discreet mode |
| Recipients | SMS, email and push providers |
| Retention | Delivered messages (including the inbox) deleted after `solace.privacy.message-retention` ([180] days, D40; **Open** for the owner to confirm). SMS and email text is never stored |
| Security | CO-20, CL-6 |

### P9. Crisis alerts and the safety monitor

| | |
| --- | --- |
| Purpose | Route crisis signals to a person quickly and show urgent help |
| People | Seekers, guests who ask for a call back |
| Data | Alert source (safety monitor, health check safety rule, later others), time, assignee, acknowledgement, outcome; a guest's call-back phone number |
| Where | Core database (crisis queue); AI service (risk flags) |
| Recipients | Crisis responders, backup responder at 5 minutes, Super admins at 10 (D11); SMS provider for staff alerts (D15) |
| Retention | **Open** |
| Security | CO-15, CO-16, AI-3, AI-4 |

### P10. Consent records

| | |
| --- | --- |
| Purpose | Prove what each person agreed to, per purpose and version, and stop processing when they withdraw |
| People | Seekers, guests (health check), specialists (scribe) |
| Data | Subject, purpose (HEALTH_CHECK, TRACKING, AI_SCRIBE, RESEARCH, ...), granted or withdrawn, text version, time |
| Where | Core database; changes published as `core.consent.changed` (ids only) |
| Retention | **Open** (as long as the processing it covers, plus the period in which it may need proving; **Counsel**) |

### P11. Data-subject requests

| | |
| --- | --- |
| Purpose | Export a person's data and erase it on request |
| Data | Everything held about the person, across services |
| How | Privacy centre in the app; `core.user.erased` makes every service delete within 30 days and confirm (Tracking replies `tracking.user.erased`) |
| Open | What erasure does to the audit log and to accounting records (Core threat model, Findings 5); backups (Tracking threat model, Findings 2) |

### P12. Audit log

| | |
| --- | --- |
| Purpose | Record every read of clinical data and every admin action |
| People | Staff, specialists, services as actors; seekers as subjects |
| Data | Pseudonymous actor and subject ids, roles, action, target, outcome, trace id, and the reason staff give for a change or for break-glass access (D42: the staff member's own words, kept in the database and never logged). Never names, contacts or what members typed (`libs/audit`). The Audit log screen joins staff names from `staff_member` |
| Where | Each service's database, `audit_log`, append-only |
| Retention | **Open**. Rows cannot be deleted today, so this must be decided before launch (**Counsel**) |
| Security | CO-8, CO-9 |

### P13. Security and operations telemetry

| | |
| --- | --- |
| Purpose | Run the platform, find faults, detect attacks |
| Data | Logs (scrubbed of phone numbers, emails, codes, tokens and free-text fields at the appender), traces and metrics carrying ids and outcomes; IP addresses in ingress and gateway logs |
| Where | Loki, Tempo, Prometheus (Grafana stack) |
| Retention | **Open** |
| Security | CO-11, EI-4 |

### P14. Health check (Phase 2)

| | |
| --- | --- |
| Purpose | Recommend a type of specialist and filter matches. Not a diagnosis; no AI reads the answers (D8) |
| People | Seekers, guests (anonymous device token) |
| Data | Answers (encrypted), screener totals and bands (never shown to the seeker as a label), recommended specialties, risk flag |
| Consent | HEALTH_CHECK, before the first question |
| Recipients | A specialist, only when the seeker ticks "Share my answers" for that booking |
| Retention | Unclaimed guest responses deleted after `solace.health-check.unclaimed-retention` (`[30]` days, nightly at 03:15 Douala). A response older than `[90]` days asks for a retake before matching but is not deleted by that rule. Deleted with the account on erasure; included in the data export. Otherwise **Open** |
| Where | Core database, `hc_response.sealed` (AES-256-GCM); preferences and the suggested specialty code in the clear for counts; a guest's call-back number for the crisis responder (D55) |
| Security | CO-7 |

### P15. Tracking (Phase 2)

| | |
| --- | --- |
| Purpose | Let registered seekers log their health between sessions, see patterns and invitations to care |
| People | Registered seekers only |
| Data | Observations per element, notes (storage only), modules joined, an enrolment answer where a module asks one (no sex at birth, D17), recommendations and their outcomes, sharing grants, the days a tracking push went out and nudge pauses (D52); in Core, open invites (ids and specialty codes) |
| Consent | TRACKING; a separate, revocable, forward-only grant per specialist; a separate yes for notes |
| Where | Tracking database, pseudonymous ids only; the device (offline, encrypted) |
| Recipients | Specialists with a live booking and a grant; AI receives a consented summary (Phase 3) |
| Retention | Until the seeker deletes an entry (notes hard-deleted) or their account is erased. Backups: **Open** |
| Security | Tracking threat model |

### P16. AI features after launch (Phase 3, 4)

| | |
| --- | --- |
| Purpose | Tracking insights, pre-session brief, session scribe, between-session companion, translation of content, trust and safety |
| Consent | Per feature; the scribe needs both people and can be refused per session |
| Data | As each feature needs; pseudonymous ids only; no user data used for training. Phase 3: `ai_call` (feature, route, prompt version, tokens, outcome, redacted prompt and output text); AI consent mirrored from Core; specialists' private prep notes per booking, stored in Core (`session_prep_note`), read by that specialist only. Health check answers are never sent to a model (D8). Phase 4: session audio (private bucket, 7 days at most), sealed transcripts and notes (SOAP/DAP) for the booking's specialist, homework and the seeker's sealed records, companion answers checked by the safety monitor; the seeker's sealed safety plan in Core; phone dial-in PINs per booking; copilot metrics are aggregates only |
| Retention | Prompts and outputs `[30]` days (`ai_call` texts cleared by a job); prep notes with the booking, removed on erasure; scribe audio deleted after the note is signed or within 7 days (System Design). Signed notes: **Open** (decisions.md: is Solace the record keeper?). Transcripts deleted at signing; unsigned drafts after 7 days; signed notes **Open** (record keeper); homework records with the account |
| Transfers abroad | Only with authorisation, otherwise a self-hosted model (System Design; **Counsel**) |
| Security | AI threat model |

### P17. Analytics and the admin copilot

| | |
| --- | --- |
| Purpose | Measure the funnel, recommendation-to-booking rate, ratings and retention; answer staff questions over metrics |
| Data | Aggregated, de-identified metrics only |
| Open | The aggregation threshold below which a number is not shown, so small groups cannot identify a person |
