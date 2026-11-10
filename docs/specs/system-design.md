# Solace — System Design

Oct 15, 2026 · @Banseka

## Overview

Solace is a bilingual (English/French) mental-health marketplace where seekers book audio or video sessions with vetted specialists, optionally track their health, and get nudged toward care when their data warrants it. It runs as three backend services (Core, Tracking, AI), one cross-platform mobile app, and one web admin portal.

### Functional requirements

| Area | Requirement |
| --- | --- |
| Booking | Seekers book audio or video sessions with specialists, with or without an account |
| Guest access | Guests book with phone or email only; a verified contact replaces the account |
| Accounts | Registered seekers unlock tracking modules, history, favourites and AI features |
| Tracking | Admin-built modules from engineer-seeded elements, with rules that show a seeker their pattern or invite them to see a type of specialist |
| Health check | Clinician-built questions that recommend a type of specialist and filter matches, for guests and seekers |
| Language | UI in EN/FR; seekers pick a session language; specialists declare proficiency per language |
| Payments | Pay per session, refunds, specialist payouts, platform commission |
| Ratings | Post-session rating and review, moderated |
| Admin | Live overview of the platform plus configuration of modules, pricing, specialties, content and policies |
| AI | Tracking insights, session support for specialists, safety monitoring (see AI service). Finding the right specialist uses the health check, which has no AI |

### Non-functional requirements

- **Privacy first:** health and session data is the most sensitive data the platform holds; it is encrypted, minimised and access-logged.
- **Low bandwidth:** sessions must degrade gracefully from video to audio on weak 3G/4G connections.
- **Availability:** 99.5% for booking and session join in the first year; scheduled jobs and AI may degrade without blocking care.
- **Safety:** crisis signals anywhere in the product route to a human and to local emergency resources.
- **Scale target:** design for 50k seekers and 1k specialists in year one, with clear headroom to 10x.

### Assumptions

- Cameroon is the launch market, so mobile money (MTN MoMo, Orange Money) comes first, with cards as a second rail.
- Specialists are licensed professionals vetted by Solace admins before they can take bookings.
- The stack follows the team's strengths: Java 21 + Spring Boot for all three services, React Native (TypeScript) for mobile, Angular for admin, gRPC between services and REST at the edge.

## High-level architecture

Two clients reach three independently deployed services through one API gateway; the services share nothing but a Kafka event bus and narrow gRPC contracts. Core is the system of record for people and money, Tracking for health logs, and AI for every model call.

> Diagram: Solace architecture · 2 clients, 3 services, 1 event bus (in the live doc, linked from START_HERE.md)

Core (highlighted) is the only service that talks to payments, notifications and the media server; the mobile app streams audio and video straight to LiveKit using a token Core issues. Each service owns its own database, so a compromise or outage in one does not expose or stop the others.

## Core service

Core is a Spring Boot 3 modular monolith (Spring Modulith) that owns identity, the marketplace, money and administration. A modular monolith keeps one deployable and one transaction boundary for booking + payment, while module boundaries stay enforced so any module can be split out later.

### Modules

| Module | Owns | Key decisions |
| --- | --- | --- |
| Identity & access | Users, roles, sessions, OTP, guest tokens | Keycloak issues JWTs for everyone and each service checks them as a Spring Security resource server (see Sign-in and accounts); roles SEEKER, SPECIALIST, ADMIN, SUPER\_ADMIN, SUPPORT, CLINICAL\_REVIEWER; fine-grained admin permissions |
| Guest access | Guest profiles keyed by verified phone/email | OTP verifies the contact; a short-lived, booking-scoped guest token lets them pay, join and rate; bookings are claimed automatically if they later register with the same contact |
| Specialists | Profiles, credentials, specialties, languages, pricing, payout details | Onboarding is a state machine (DRAFT → SUBMITTED → UNDER\_REVIEW → APPROVED/REJECTED → SUSPENDED); documents in object storage; languages stored with a proficiency level per language |
| Catalog & search | Searchable specialist index | Filters: specialty, session language, modality (audio/video), price band, next availability, rating; Postgres full-text first, OpenSearch only if needed |
| Health check | Health check versions, questions, scoring rules, responses | Rules score specialties from answers; a locked safety rule opens urgent help and raises a crisis alert; versions reviewed like tracking modules (see Health check below) |
| Scheduling | Availability rules, exceptions, slots, holds | Weekly recurring rules + date exceptions stored in the specialist's time zone; slots computed on read; a 10-minute hold during checkout; a Postgres exclusion constraint makes double-booking impossible |
| Bookings | Booking lifecycle, cancellation and no-show policy | Explicit state machine (see the booking lifecycle diagram); policies (refund windows, reschedule limits) are admin-configurable |
| Sessions | Call rooms, join tokens, attendance | Creates the media room, mints per-participant tokens 10 minutes before start, records join/leave events for no-show and payout logic |
| Payments | Charges, refunds, specialist balances, payouts, commission | Provider-agnostic port with adapters for a mobile-money aggregator and a card PSP; a double-entry ledger; funds held until the session completes, then released to the specialist's balance |
| Ratings & reviews | Post-session ratings, written reviews, moderation | Only a completed booking can be rated, once; reviews pass AI moderation then optional human review; specialists can reply |
| Notifications | Push, SMS, email, WhatsApp, in-app | Template per event per locale (EN/FR); channel fallback (push → SMS for guests); user-level quiet hours |
| Content | Articles, FAQs, crisis resources, legal text | Bilingual content managed by admins; crisis resources per country |
| Admin & config | Platform settings, feature flags, pricing bands, specialties, policies | Versioned settings with audit trail; changes publish a config-changed event |
| Audit | Who accessed or changed what | Append-only audit log for every read of clinical data and every admin action |

### Core data model (main entities)

| Entity | Key fields |
| --- | --- |
| user\_account | id, type (SEEKER/SPECIALIST/ADMIN), phone, email, preferred\_locale, status |
| guest\_contact | id, phone or email, verified\_at, claimed\_by\_user\_id |
| specialist\_profile | user\_id, title, bio\_en, bio\_fr, years\_experience, license\_no, verification\_status, timezone |
| specialist\_language | specialist\_id, language (en/fr/…), proficiency (NATIVE/FLUENT/PROFESSIONAL) |
| specialty | id, code, name\_en, name\_fr, parent\_id |
| service\_offering | specialist\_id, specialty\_id, modality (AUDIO/VIDEO), duration\_min, price, currency |
| availability\_rule / availability\_exception | specialist\_id, weekday or date, start, end |
| health\_check\_version | id, version, status (DRAFT/IN\_REVIEW/PUBLISHED/RETIRED), author\_id, reviewer\_id, published\_at, change\_reason |
| hc\_question / hc\_option | version\_id, code, position, kind (SINGLE/MULTI/SCALE/PREFERENCE/SAFETY), instrument (PHQ9/GAD7/none), item\_no, text\_en, text\_fr, show\_if\_json, locked; options: code, position, label\_en, label\_fr, points |
| hc\_rule / hc\_priority | version\_id, code, when\_json, specialty\_id, points, reason\_en, reason\_fr; priority: version\_id, specialty\_id, rank |
| health\_check\_response | id, version\_id, seeker\_user\_id or guest\_contact\_id or anon\_token, answers (encrypted), totals\_json, recommended\_specialty\_id, also\_specialty\_id, risk\_flagged, completed\_at, expires\_at |
| booking | id, seeker\_user\_id or guest\_contact\_id, specialist\_id, offering\_id, session\_language, starts\_at, ends\_at, status, source (SEARCH/HEALTH\_CHECK/TRACKING\_RECOMMENDATION) |
| session\_room | booking\_id, provider\_room\_id, started\_at, ended\_at, participant events |
| payment / refund | booking\_id, provider, method (MOMO/OM/CARD), amount, status, provider\_ref |
| ledger\_entry | account, debit, credit, booking\_id, created\_at |
| payout | specialist\_id, amount, period, status |
| review | booking\_id, rating 1–5, comment, moderation\_status, specialist\_reply |
| consent\_record | subject, purpose (AI\_SCRIBE, TRACKING, RESEARCH…), granted, version, timestamp |
| platform\_setting | key, value\_json, version, updated\_by |

The `source` field on booking lets admins measure how many bookings come from tracking recommendations and the health check, which is the core proof that the integrated model works.

### Booking lifecycle

> Diagram: Booking lifecycle · 4 main states, 3 exits (in the live doc, linked from START_HERE.md)

Money follows the state: it is captured at Confirmed, held while the session runs, and released to the specialist's balance only at Completed. Refund amounts for Cancelled and No-show come from the admin-configured policy, so finance can change them without a release.

### Health check

The health check is how a seeker or guest finds the right type of specialist. It is a short series of fixed questions that clinicians write, test and approve in the admin portal, and rules turn the answers into a recommended specialty. No AI reads the answers.

**What the seeker answers.** The launch version, v1, takes about three minutes:

1. What they want help with (one or more topics), how long it has lasted, and how much it affects daily life.
2. A safety question, always asked and never editable.
3. PHQ-2 and GAD-2. When either scores 3 or more, the rest of PHQ-9 or GAD-7 follows.
4. Whether they take, or think they may need, medicine for their mental health.
5. Preferences that filter matches but never score: session language, audio or video, price band, and an optional specialist gender.

**How answers become a recommendation.**

- Each rule reads answers or screener totals and adds points to one specialty, with a reason in EN and FR ("You said worry affects your work most days").
- The specialty with the most points is recommended. A second one within \[1\] point shows as "also a good fit". Ties follow the clinicians' priority order.
- Screener bands follow the published cut-offs (PHQ-9: 5, 10, 15, 20; GAD-7: 5, 10, 15). Totals and bands are stored for the specialist and never shown to the seeker as a label.
- The seeker sees the specialty, the reasons, and "This is not a diagnosis".

**Safety rule.** Any answer other than "No" to the safety question, or a PHQ-9 item 9 above 0, stops the check and opens urgent help at once. For a signed-in seeker, Core also raises a crisis-queue alert with source HEALTH\_CHECK. A guest can leave a phone number for a call back, which raises the same alert. Every version carries this rule, and no one can edit or remove it.

**Matching.** Matches are specialists who offer the recommended specialty in the chosen session language and format, within the price band. They are ranked by overlap between the seeker's topics and the specialist's focus areas, then the soonest slot, then rating, and each shows its reason from those facts. Bookings made from it carry source HEALTH\_CHECK.

**Guests and storage.** A guest's response is kept under an anonymous token on the device and is linked when they create an account or verify a contact to book. Unclaimed responses are deleted after \[30\] days. Answers are health data: they need a HEALTH\_CHECK consent before the first question, are encrypted like notes, and reach a specialist only when the seeker ticks "Share my answers" for that booking. A seeker can retake the check at any time. The latest completed response is used, and one older than \[90\] days asks for a retake before matching.

**Versions.** The health check follows the tracking module lifecycle: Draft, In review, Published, Retired, and the clinical reviewer who approves a version cannot be its author. Changing wording, points, reasons or priority makes a minor version (v1.1); adding or removing a question or option makes a major version (v2). A response keeps the version it was answered on. PHQ-9 and GAD-7 items use the official English and French wording and cannot be edited.

## Tracking service

Tracking is its own service with its own database. Engineers ship the input views, rule operators and the episode algorithm in code; admins define elements, episode models and context questions, build modules with a card image, and set their rules and thresholds in the admin portal, with clinical review of each (D61). Full detail: Solace — Tracking Service Spec.

### What it does and refuses to do

- Logs small check-ins against elements: mood, sleep, notes, flow, period events, acne, hair growth and stress at launch, more as engineers seed them. Modules and elements start at v1; the next revision is v1.1.
- No scores, predictions or diagnoses. Rules state facts against thresholds.
- Notes are storage only: never read by rules or scanned, and deleted for real on delete. Only the AI layer's separate D1 opt-in can send them anywhere.
- No crisis detection. The offline hotline link is on every tracking surface.
- Specialist access needs a live booking, checked with Core on every read, plus a forward-only, revocable grant; notes need a separate yes.
- Only registered seekers can track.

### Elements, modules and rules

| Part | Created by | Holds |
| --- | --- | --- |
| Element | Engineers, by migration | Value type, day state or event, logging view, icon, signals, EN/FR labels |
| Module | Admins, in the portal | Card image, EN/FR text, 1–6 elements, eligibility, versions with clinical review |
| Rule | Admins, per module version | 1–3 conditions on element signals, profile conditions that switch it off, outcome (pattern or invite), cooldown |

### Recommendation flow

When a rule fires, Tracking stores the evidence and publishes `tracking.recommendation.created`. For an invite, Core matches specialists of that specialty in the seeker's session language and shows the invite in the app; the AI service may phrase the evidence in plain words, never from notes. The seeker books, snoozes or dismisses, and the outcome comes back so clinicians can see which rules lead to care.

> Diagram: Tracking to care · 4 lanes, 1 decision (in the live doc, linked from START_HERE.md)

Rules never notify anyone but the seeker, and the seeker always decides. Outcomes feed back so clinicians can tune thresholds.

## AI service

The AI service makes care easier to reach and specialists more effective, and never acts as the therapist. Every feature below sits behind an independent safety monitor, is bilingual, and keeps a human in the loop wherever a clinical judgement is made.

### Why these features

- Industry direction for 2026 is AI-assisted intake and matching with human oversight, "do not train on my data" controls, and between-session companions paired with escalation paths ([source](https://www.rajeshkumar.xyz/blog/mental-health-therapy-apps/)).
- Leading platforms match on clinical fit rather than engagement: Spring Health matches people to the right level of care, and Lyra matches by symptom profile, modality and schedule ([source](https://www.vcbacked.co/blog/ai-health-startups-to-watch-2026)).
- Model-native safeguards are not enough for crisis detection. An independent supervisory agent caught suicide risk in 91.5% of test evaluations versus 18.3% for built-in safeguards ([source](https://www.medrxiv.org/content/10.64898/2026.04.13.26350757.full.pdf)). A separate study reached near-perfect recall in under a second by running a conservative detector apart from the chatbot ([source](https://www.suicideinfo.ca/resource/suicide-and-crisis-risk-detection-using-large-language-models-in-mental-health-chatbots/)).
- Ambient session scribes are becoming standard, but need explicit, separate consent, a per-session opt-out, and clinician review of every draft ([source](<https://www.slocounty.ca.gov/departments/slo-health/behavioral-health/forms-documents/quality-support-team/technical-center-documents/paper-and-lobby-forms/english/consent-to-use-artificial-intelligence-(ai)-ambient-scribe>), [source](https://ensorahealth.com/blog/how-ambient-ai-is-making-its-way-into-therapy/)).
- Several US states have restricted AI acting as a therapist ([source](https://www.qatar-tribune.com/article/250555/opinion/big-techs-solutions-to-mental-health-bring-bigger-problems)), which supports keeping Solace's AI in a support role.

### Feature set

| # | Feature | Who uses it | What it does | Human in the loop |
| --- | --- | --- | --- | --- |
| 1 | Safety monitor | Everyone (background) | Checks text written to the companion and reviews (the health check has its own locked safety rule); never tracking notes, which stay storage unless the seeker gives the separate D1 opt-in, for crisis risk across six categories: suicidal ideation, self-harm, acute anxiety, violent thoughts, substance crisis, risk-taking; switches the app into emergency mode | On-call specialist and admin alerted on every URGENT signal |
| 2 | Tracking insights | Registered seekers | Plain-language wording of why a rule fired, from its evidence only; it never adds findings of its own | Rules decide; AI only explains |
| 3 | Pre-session brief | Specialists | One-page summary of the health check answers the seeker shared, consented tracking trends and prior notes before a session | Specialist reviews; seeker controls what is shared |
| 4 | Session scribe | Specialists (opt-in) | Transcribes consented sessions (EN/FR) and drafts notes (SOAP/DAP) plus suggested homework | Draft only until the specialist edits and signs; seeker can refuse or opt out per session |
| 5 | Between-session companion | Registered seekers | Guided journaling, CBT-style exercises and homework set by their specialist, grounded only in vetted content | Specialist sets homework; companion hands off to the specialist or crisis flow, never diagnoses |
| 6 | Content & bio translation | Admins, specialists | Drafts FR↔EN versions of articles, bios and notifications | Human approves before publish |
| 7 | Trust & safety | Admins | Moderates reviews and messages, flags fake reviews, credential-document anomalies and payment fraud patterns | Admin decides |
| 8 | Admin copilot | Admins | Natural-language questions over aggregated, de-identified metrics; anomaly alerts (booking drop, payment failure spikes) | Read-only |

### Architecture

- **Runtime:** Spring Boot + Spring AI for model calls, embeddings and structured output; LangGraph4j for multi-step flows (scribe, companion) with checkpoints and human-approval interrupts.
- **Model gateway:** one internal port in front of all models (an AI gateway such as OmniRoute fits here) handling routing, fallbacks, per-feature budgets, PII redaction and logging. Feature code never calls a vendor directly.
- **Models:** a hosted frontier LLM for reasoning-heavy flows; a small, fast classifier model for the safety monitor; a multilingual speech-to-text model for the scribe; a multilingual embedding model for retrieval over vetted content.
- **Retrieval:** PgVector holding vetted, bilingual psychoeducation and platform content; the companion can only answer from this corpus.
- **Guardrails:** input and output checks (crisis, medical advice, diagnosis, off-topic), a system policy that refuses to diagnose or prescribe, and every response carries its locale.
- **Data rules:** pseudonymous ids only; no user data used for training; prompts and outputs retained 30 days for safety audit then deleted; health data sent to a non-local model only with the authorisation Cameroon's data law requires (see Security), otherwise routed to a self-hosted model.
- **Evaluation:** bilingual test sets per feature, with crisis recall as a release gate (target ≥ 99% on the safety set) and clinician-graded samples of notes and companion replies each release.

## Inter-service communication

Services talk synchronously over gRPC only when a user is waiting for the answer, and asynchronously over Kafka for everything else. Each service owns its own database; no service reads another's tables.

### Rules of the road

- **Edge:** clients hit one API gateway (Spring Cloud Gateway) that validates the JWT, applies rate limits and routes `/api/core/**`, `/api/tracking/**`, `/api/ai/**`. Each service validates the token again.
- **Service-to-service auth:** OAuth2 client-credentials tokens from Keycloak with narrow scopes (e.g. `tracking:summary.read`), plus mTLS inside the cluster.
- **Sync contracts:** Protobuf definitions in a shared contracts repo, with Buf lint and breaking-change checks in CI; Java stubs generated and served through Spring gRPC; 2-second deadlines, Resilience4j circuit breakers and a defined fallback for every call. Mobile and admin clients stay on REST/JSON at the gateway. gRPC holds long-lived HTTP/2 connections, so Kubernetes' connection-level balancing would pin traffic to one pod: use client-side round-robin over a headless service, or a mesh such as Linkerd.
- **Async contracts:** Kafka topics per domain; events published through the transactional outbox (Spring Modulith event publication) so a booking and its event commit together; JSON Schema in a schema registry; consumers are idempotent on `eventId`; failed messages go to a dead-letter topic with alerting.
- **Event envelope:** `eventId`, `type`, `version`, `occurredAt`, `subjectId` (pseudonymous user id), `locale`, `traceId`, `payload`. No names, phone numbers or free text in events; consumers fetch details through a scoped API when they truly need them.

### Synchronous calls

| Caller → callee | Call | If the callee is down |
| --- | --- | --- |
| AI → Tracking | Consented trend summary for a pre-session brief | Brief is generated without tracking data |
| Tracking → Core | Live-booking check on every specialist read (never cached); specialty taxonomy and eligibility (cached 1 h) | Specialist access denied; taxonomy from cache |
| Core → AI | Moderate a review before publishing | Review held for human moderation |

### Event catalog

| Event | Producer | Consumers | Purpose |
| --- | --- | --- | --- |
| `core.user.registered` | Core | Tracking, AI | Create shadow profiles keyed by user id |
| `core.user.erased` | Core | Tracking, AI | Delete all data for the user (right to erasure) |
| `core.consent.changed` | Core | Tracking, AI | Turn data sharing, scribe or AI features on or off |
| `core.booking.confirmed` / `cancelled` / `completed` | Core | Tracking, AI | Link recommendations to outcomes; schedule pre-session brief |
| `core.session.ended` | Core | AI | Start the scribe pipeline when both parties consented |
| `core.review.submitted` | Core | AI | Moderation |
| `core.config.changed` | Core | Tracking, AI | Refresh cached settings, feature flags, specialties |
| `tracking.module.published` | Tracking | Core | Refresh the module catalogue cache; Tracking sends no event about individual entries |
| `tracking.recommendation.created` / `resolved` | Tracking | Core, AI | Notify seeker with matched specialists; generate explanation |
| `ai.risk.flagged` | AI | Core, Tracking | Trigger the crisis flow, alert on-call, pause nudges |
| `ai.note.drafted` | AI | Core | Notify the specialist that a draft awaits review |
| `ai.moderation.decided` | AI | Core | Publish or hold a review |

The safety path is the one exception to "async for everything else": in-app chat text is checked inline before the AI replies, and `ai.risk.flagged` is consumed by Core within seconds to push crisis resources.

## Audio and video sessions

Use LiveKit (open-source WebRTC SFU) for both modalities, self-hosted or on LiveKit Cloud, with Agora as the fallback provider behind the same Sessions port in Core. LiveKit has an official React Native SDK and is built for weak networks, which matters most for Solace users.

### Why LiveKit

- **Audio holds up on bad networks:** speech is sent with Opus at roughly 24–64 kbps, and redundant encoding plus forward error correction lets audio tolerate around 20–30% packet loss ([source](https://livekit.com/blog/audio-quality)).
- **Video adapts:** simulcast sends several layers and the server forwards the one each receiver can handle, stepping up as conditions improve ([source](https://docs.livekit.io/transport/media/advanced.md)); adaptive stream and dynacast cut bandwidth and CPU further.
- **Open source:** it can be self-hosted close to users if data-residency rules require it, avoiding lock-in.

### Join flow

1. At booking confirmation, Core creates a room named after the booking id with the modality (AUDIO or VIDEO).
2. From 10 minutes before start, the app asks Core for a join token; Core checks the caller is the booked seeker (account or guest token) or the specialist, then mints a LiveKit JWT scoped to that room, valid until 15 minutes after the scheduled end.
3. Both parties land in a waiting room; the session starts when both are present.
4. Core consumes LiveKit webhooks (participant joined/left, room finished) to record attendance, which drives completion, no-show rules and payout release.

### Weak-network behaviour

- Audio sessions publish no video at all.
- Video sessions start at a low layer and step up; if bandwidth stays too low for 10 seconds, the app offers to switch to audio-only and does it automatically below a hard floor.
- Reconnection keeps the same room for up to 2 minutes, so a dropped connection does not end the session.
- A pre-call network test runs in the waiting room and warns early.
- Later phase: phone dial-in through LiveKit SIP for seekers without data.

### Recording

Sessions are never recorded by default. Only when both seeker and specialist have consented to the scribe does Core start an audio-only egress to encrypted object storage; the AI service deletes the audio once the note is signed, or after 7 days at most.

## Bilingual design (English / French)

Solace separates two choices that are easy to conflate: the **app language** (EN or FR, for the interface) and the **session language** (the language the seeker wants to be counselled in). A Francophone seeker may use the app in French but prefer a session in English, or the reverse.

- **Languages are data, not an enum.** Session languages use ISO 639 codes in a `language` table, so Pidgin English, Fulfulde or others can be added later without code changes; the interface stays EN/FR.
- **Specialist proficiency:** at signup each specialist declares languages with a level (NATIVE, FLUENT, PROFESSIONAL). Admins verify it during the onboarding interview; only PROFESSIONAL and above are bookable.
- **Booking:** the seeker picks a session language first; search, health check matches and recommendation matching only return specialists bookable in it. The booking stores `session_language`, and the specialist sees it on every reminder.
- **Translatable content:** specialties, modules, metrics, rules, articles, notifications and legal text use translation tables (`entity_id, locale, field, value`) with a completeness check in the admin portal; nothing publishes without both EN and FR.
- **Clients:** the React Native app uses i18next and the Angular admin uses Transloco, both reading the same JSON translation files from a shared package; the backend resolves messages with Spring `MessageSource` from the user's stored locale (falling back to `Accept-Language`).
- **Notifications:** rendered in the recipient's locale, so a booking can notify the seeker in French and the specialist in English.
- **Formats:** dates, times and XAF amounts formatted per locale; all times stored in UTC and shown in the viewer's time zone.
- **AI:** replies in the user's app locale unless they write in the other language; every safety and quality test set exists in both languages and includes code-switched text (e.g. Camfranglais).

## Clients

### Mobile app (React Native, iOS + Android)

One React Native app serves seekers, guests and specialists, switching shell by role after login. Using TypeScript lets it share API types, generated clients and translation files with the Angular admin. One codebase, one store listing and one release train are the right trade at launch; the specialist experience can split into its own app later if it grows.

| Experience | Main screens |
| --- | --- |
| Guest | Browse and search specialists, health check and matches, specialist profile, choose session language and slot, verify phone/email by OTP, pay, join session from SMS/push link, rate |
| Seeker | Everything a guest has, plus my sessions, tracking modules, insights and recommendations, companion and homework, crisis button, privacy and consent centre |
| Specialist | Availability and calendar, upcoming sessions with pre-session brief, waiting room and call, draft notes to review and sign, earnings and payouts, reviews and replies, profile and languages |

Key technical choices:

- **State and data:** Expo with development builds for native modules, Hermes and the New Architecture on; TanStack Query for server state and Zustand for local state; generated OpenAPI clients; op-sqlite (or WatermelonDB) for offline tracking entries with a sync queue, so logging works without data. Performance is tested on low-end Android phones from the first sprint.
- **Calls:** LiveKit React Native SDK, with CallKit (iOS) and ConnectionService (Android) via react-native-callkeep, so incoming sessions ring like a phone call.
- **Privacy on the device:** optional PIN/biometric app lock, neutral notification text by default ("You have a reminder"), no health data in push payloads, encrypted local storage.
- **Crisis button:** always one tap away, works for guests, shows local emergency numbers from the content module even offline.

### Admin portal (Angular)

An Angular 17+ web app (standalone components, Angular Material, NgRx Signal Store) behind SSO with per-permission access, so support staff, clinical reviewers and finance see only their areas.

| Area | What admins see or configure |
| --- | --- |
| Live overview | Sessions in progress, bookings today, revenue, active crisis alerts, service health, via server-sent events |
| Crisis queue | Every `ai.risk.flagged` and URGENT recommendation, with acknowledge, assign and outcome |
| Specialists | Onboarding queue, credential review, language verification, suspension |
| Bookings & sessions | Search, reschedule, refund, no-show disputes |
| Payments | Transactions, refunds, payout runs, commission and ledger reports |
| Tracking | Module builder: card image, elements from the seeded library, rules and thresholds, test runs on synthetic histories, clinical approval |
| AI | Feature flags per AI feature, prompt and policy versions, eval results, moderation queue |
| Catalogue | Specialties, pricing bands, cancellation policy, session durations |
| Content | Bilingual articles, crisis resources per country, legal documents |
| Analytics | Funnel (visit → book → complete), recommendation-to-booking rate, ratings, retention |
| Audit | Searchable log of admin actions and clinical data access |

## Security, privacy and compliance

Health and mental-health data is legally sensitive in Cameroon, so compliance shapes where data lives and which AI models may see it. Law No. 2024/017 of 23 December 2024 is Cameroon's first dedicated data protection law and creates an independent Data Protection Authority ([source](https://dig.watch/countries/cameroon)).

### What the law means for Solace

- **Prior authorisation:** processing personal data needs prior authorisation from the Authority ([source](https://www.dlapiperdataprotection.com/?c=CM&t=law)); health data is in the sensitive category with stricter limits ([source](https://techhiveadvisory.africa/insights/operationalising-cameroons-data-protection-law-a-review-of-key-provisions-and-impacts)). File before launch.
- **Cross-border transfers:** transfers abroad need the Authority's authorisation and an adequate destination, with sender and recipient jointly liable ([source](https://www.afriwise.com/resources/cameroons-evolving-data-privacy-landscape)). This covers cloud hosting and any foreign LLM or speech API.
- **Local hosting:** regulation encourages local hosting for health data, and breach notification is mandatory ([source](https://www.digitalbusiness.africa/?p=103567)).
- **Deadline:** businesses were given until June 2026 to comply ([source](https://www.afriwise.com/resources/cameroons-evolving-data-privacy-landscape)), so Solace must be compliant from day one.

This needs confirmation from a Cameroonian data-protection lawyer before architecture is frozen, especially on hosting region and AI vendors.

### Controls

| Area | Control |
| --- | --- |
| Encryption | TLS 1.3 everywhere; AES-256 at rest; envelope encryption with per-tenant keys in a KMS/Vault for tracking entries, notes, health check answers and transcripts |
| Separation | Identity data in Core only; Tracking and AI hold pseudonymous ids; separate databases and credentials per service |
| Access | RBAC for roles plus attribute checks: a specialist sees a seeker's clinical data only while a booking relationship is active and the seeker has shared it; break-glass access for crisis, logged and reviewed |
| Consent | Granular, versioned consent records (tracking, AI features, scribe, data sharing with specialist, research); every service checks consent before processing |
| Data subject rights | Export and erasure from the privacy centre; `core.user.erased` cascades to all services within 30 days |
| Retention | Transcripts and audio deleted after note signing (≤ 7 days); AI logs 30 days; clinical notes per local medical-record rules; guest data purged 12 months after last booking |
| Payments | Card data never touches Solace (hosted PSP fields); mobile money through the aggregator; webhooks verified by signature and idempotent |
| App security | OWASP MASVS for mobile, certificate pinning, root/jailbreak warning; OWASP ASVS for APIs; dependency and container scanning in CI; annual penetration test |
| Specialist trust | Licence and identity verification, reference checks, periodic re-verification, complaints workflow |
| Incident response | Runbook with 72-hour internal breach assessment and notification to the Authority and affected users |

### Sign-in and accounts

Keycloak is the one identity provider. Core, Tracking and AI never store passwords; each is a Spring Security resource server that checks Keycloak's tokens and reads roles from them.

- **Seekers and specialists** sign in with a one-time code sent to their phone or email, on the app's own screens. A small Keycloak authenticator extension checks the code; Core sends the SMS.
- **Guests** never get a Keycloak account. Core issues a short-lived token scoped to one booking after the code check.
- **Staff** are added only by a Super admin in Team and roles. Core creates the account through the Keycloak admin API with a unique temporary password, handed over in person, that works once and expires after 72 hours. At first sign-in Keycloak makes them set their own password and add an authenticator app. Nobody can sign themselves up as staff.
- Password rules, lockout after repeated failures, the \[15\]-minute idle sign-out and a log of every sign-in come from Keycloak's settings, not our code.

Why Keycloak rather than our own sign-in on Spring Security: the staff rules above (temporary password, forced change, authenticator, lockout, session limits, sign-in audit) are settings there, while building them ourselves means writing and maintaining security-critical code for a health platform. It also gives all three services one token format, and it is maintained and patched upstream. The cost is running one more service with its own database, and the phone-code extension we write once.

## Infrastructure and operations

Run everything in containers on one managed Kubernetes cluster per environment (dev, staging, prod), hosted where the data-protection review allows: a local Cameroonian data centre if health data must stay in-country, otherwise the nearest cloud region with transfer authorisation.

| Component | Choice | Notes |
| --- | --- | --- |
| Services | Core, Tracking, AI as Spring Boot 3 / Java 21 images (virtual threads on) | Stateless, horizontally scaled with HPA |
| Databases | PostgreSQL 16, one per service; pgvector in the AI database | Point-in-time recovery; RPO 15 min, RTO 4 h |
| Cache & locks | Redis | Slot holds, rate limits, OTP, cached taxonomy |
| Messaging | Kafka (Strimzi on the cluster, or a managed offering) + schema registry | Partition by pseudonymous user id to keep per-user ordering |
| Object storage | S3-compatible (MinIO on-prem or cloud) | Credentials, content images, temporary scribe audio; server-side encryption |
| Identity | Keycloak | Seekers, specialists, admins in separate realms; OTP via SMS provider |
| Media | LiveKit servers (+ TURN) or LiveKit Cloud | Placed for lowest RTT from Douala and Yaoundé |
| Gateway | Spring Cloud Gateway behind a WAF/CDN | TLS termination, rate limiting |
| Secrets | HashiCorp Vault or cloud KMS | Also holds data-encryption keys |

### Observability

- OpenTelemetry traces across gateway, services and Kafka, with `traceId` in every event.
- Micrometer metrics to Prometheus, logs to Loki, dashboards and alerts in Grafana; logs are scrubbed of health data at the appender.
- AI-specific metrics per feature: latency, tokens, cost, fallback rate, safety-flag rate.
- SLOs with alerts: booking success, session join success, payment confirmation time, crisis-alert acknowledgement time.

### Delivery

- One mono-repo or three repos with shared contract modules; GitHub Actions running unit tests, Testcontainers integration tests and contract tests against protobuf definitions, the edge OpenAPI specs and event schemas.
- Argo CD for GitOps deployment; feature flags for gradual rollout of AI features.
- Database migrations with Flyway per service.

## Roadmap and open questions

Build the marketplace first, because bookings and payments prove demand; tracking and AI then plug into a working care loop. The safety monitor ships in phase 1, because a crisis can surface in the very first review or companion message.

> Diagram: Delivery roadmap · 4 phases, 4 gates (in the live doc, linked from START_HERE.md)

Each phase ships only when its gate (amber diamonds) is met; durations will be set once team size is known.

### Open questions

- [ ] **Hosting:** in-country data centre, or a regional cloud with transfer authorisation? Needs a Cameroonian data-protection lawyer; the Authority may not yet be fully operational.
- [ ] **AI vendors:** which hosted LLM and speech-to-text providers are acceptable under the transfer rules, and which features must use self-hosted models?
- [ ] **Physical-health specialists:** do gynaecologists, endocrinologists and nutritionists join the marketplace so PCOS recommendations stay in-app, or does Solace refer them out?
- [ ] **Payments:** which aggregator for MTN MoMo and Orange Money (Cameroon-focused or multi-country), payout cadence, and commission rate.
- [ ] **Clinical records:** is Solace the record keeper for signed session notes, or does each specialist keep their own?
- [ ] **Crisis staffing:** who is on call and at what hours? The escalation is set: an alert not acknowledged in 5 minutes goes to the backup, then after 5 more to a Super admin.
- [ ] **Guest limits:** should guests be capped (e.g. number of sessions) before an account is required?

## Sources

- [Afriwise: Cameroon's evolving data privacy landscape](https://www.afriwise.com/resources/cameroons-evolving-data-privacy-landscape)
- [Digital Watch: Cameroon](https://dig.watch/countries/cameroon)
- [DLA Piper: Data protection in Cameroon](https://www.dlapiperdataprotection.com/?c=CM&t=law)
- [Tech Hive Advisory: Operationalising Cameroon's data protection law](https://techhiveadvisory.africa/insights/operationalising-cameroons-data-protection-law-a-review-of-key-provisions-and-impacts)
- [Digital Business Africa: Cameroon data sovereignty](https://www.digitalbusiness.africa/?p=103567)
- [medRxiv: Independent supervisory safety agent and suicidal ideation](https://www.medrxiv.org/content/10.64898/2026.04.13.26350757.full.pdf)
- [Centre for Suicide Prevention: Crisis-risk detection in mental-health chatbots](https://www.suicideinfo.ca/resource/suicide-and-crisis-risk-detection-using-large-language-models-in-mental-health-chatbots/)
- [SLO County: AI ambient scribe consent form](<https://www.slocounty.ca.gov/departments/slo-health/behavioral-health/forms-documents/quality-support-team/technical-center-documents/paper-and-lobby-forms/english/consent-to-use-artificial-intelligence-(ai)-ambient-scribe>)
- [Ensora Health: Ambient AI in therapy sessions](https://ensorahealth.com/blog/how-ambient-ai-is-making-its-way-into-therapy/)
- [Top mental health therapy apps and 2026 trends](https://www.rajeshkumar.xyz/blog/mental-health-therapy-apps/)
- [AI health startups to watch in 2026](https://www.vcbacked.co/blog/ai-health-startups-to-watch-2026)
- [Qatar Tribune: Big tech's solutions to mental health](https://www.qatar-tribune.com/article/250555/opinion/big-techs-solutions-to-mental-health-bring-bigger-problems)
- [LiveKit: Measuring audio quality](https://livekit.com/blog/audio-quality)
- [LiveKit docs: Codecs and advanced media](https://docs.livekit.io/transport/media/advanced.md)
- [CEL for Java](https://github.com/google/cel-java)
