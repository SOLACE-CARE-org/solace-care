# Solace — Admin Portal Spec

Oct 16, 2026 · @Banseka

The admin portal is one Angular web app used by seven staff roles; each role sees only the areas it needs, and no role reads a seeker's tracking entries, notes, health check answers or transcripts. It is built against the Core admin module described in Solace — System Design.

## Roles and permissions

| Role | Who | Main job |
| --- | --- | --- |
| Super admin | Founders, platform lead | Everything, including team and roles |
| Operations | Day-to-day operations staff | Specialists, bookings, catalogue, settings |
| Support | Front-line support agents | Help seekers and specialists with bookings, accounts and reviews |
| Finance | Finance staff | Payments, refunds, payouts, commission |
| Clinical reviewer | Licensed clinicians on contract | Write and review the health check; review tracking modules and clinical content; read AI evaluation results |
| Crisis responder | On-call clinicians | Acknowledge and act on crisis alerts |
| Content editor | Content staff | Articles, crisis resources, legal text, translations |

**F** = view and change, **V** = view only, **—** = hidden.

| Area | Super admin | Operations | Support | Finance | Clinical reviewer | Crisis responder | Content editor |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Overview | F | F | V | V | V | V | V |
| Crisis queue | F | V | V | — | V | F | — |
| Specialists | F | F | V | V | V | — | — |
| Bookings and sessions | F | F | F | V | — | V | — |
| People and data requests | F | F | V | — | — | V | — |
| Payments and payouts | F | V | V | F | — | — | — |
| Reviews | F | F | F | — | — | — | — |
| Tracking modules | F | F | — | — | F | — | V |
| Health check | F | V | — | — | F | — | V |
| AI controls | F | V | — | — | V | — | — |
| Catalogue and policies | F | F | V | V | — | — | V |
| Content | F | V | V | — | V | V | F |
| Analytics | F | F | V | F | V | — | — |
| Audit log | F | V | — | — | — | — | — |
| Team and roles | F | — | — | — | — | — | — |
| Settings and flags | F | V | — | — | — | — | — |

One person can hold several roles. Only Operations or a Super admin drafts tracking modules and only a Clinical reviewer approves them, and the same person may not do both for one version. The health check is written by a Clinical reviewer and approved by a different one.

## Site map

> Diagram: Admin sidebar · 7 groups, 19 entries (in the live doc, linked from START_HERE.md)

Each staff member sees only the entries their roles allow. Detail screens (application review, booking detail, person record, content editor, the builder steps and clinical review) open from their list and are not in the sidebar. A red badge on Crisis queue counts unacknowledged alerts on every page.

## Screens

The portal has 37 screens. Phase 1 needs 24 of them for the public launch; the rest arrive with the phase that adds their feature.

| # | Screen | Purpose | Key actions | Phase |
| --- | --- | --- | --- | --- |
| 1 | Sign in | Staff sign-in with email, password and an authenticator code; first sign-in sets a new password | Sign in, choose the admin language | 1 |
| 2 | Overview | What is happening now | Open an alert, a failing service or today's numbers | 1 |
| 3 | Crisis queue | Every open crisis alert, oldest first | Acknowledge, assign, call, record the outcome | 1 |
| 4 | Specialist applications | Applications waiting for review | Filter by step and language, open one | 1 |
| 5 | Application review | One applicant's licence, documents, languages and services | Verify each item, request changes, approve, reject | 1 |
| 6 | Specialist record | An approved specialist's profile, status and history | Edit, suspend, reinstate, re-verify | 1 |
| 7 | Bookings | Search all bookings | Filter by status, date, specialist, language | 1 |
| 8 | Booking detail | One booking's timeline, attendance and money | Reschedule, cancel, refund, resolve a no-show dispute | 1 |
| 9 | People | Find a seeker or guest | Search by phone, email or booking id | 1 |
| 10 | Person record | One person's account, bookings, consents and data requests | Run an export, carry out an erasure, reveal a masked contact | 1 |
| 11 | Payments | Transactions, refunds and failures | Approve a refund, retry, reconcile | 1 |
| 12 | Payout run | Specialist balances due for payout | Review, approve, send, mark failed | 1 |
| 13 | Reviews | Reviews waiting for moderation | Publish, hide with a reason | 1 |
| 14 | Catalogue and policies | Specialties, durations, price bands, cancellation policy | Edit in EN and FR, publish a new policy version | 1 |
| 15 | Content | Articles, FAQs, crisis resources and legal text | Create, translate, publish | 1 |
| 16 | Content editor | One item side by side in EN and FR | Edit, mark translation done, publish | 1 |
| 17 | Analytics | Funnel, completion, ratings, recommendation-to-booking rate | Change the period, export | 1 |
| 18 | Audit log | Every admin action and clinical-data read | Filter, export | 1 |
| 19 | Team and roles | Staff accounts and their roles | Add staff with a temporary password, change roles, deactivate | 1 |
| 20 | Settings and flags | Platform settings and feature flags | Change a value with a reason | 1 |
| 21 | Tracking modules | All modules and their versions | Create a module, open a version | 2 |
| 22 | Module builder: details | Name, card image, who it's for, enrolment question | Edit the draft, preview | 2 |
| 23 | Module builder: elements | Pick elements and their order | Add, mark required, reorder | 2 |
| 24 | Module builder: rules | Rules and their test runs | Add a rule, run tests, submit for review | 2 |
| 25 | Clinical review | A submitted version, side by side with the published one | Approve, return with comments | 2 |
| 26 | AI controls | AI features, prompt and policy versions, evaluation results | Turn a feature on or off, promote a version | 3 |
| 27 | Admin copilot | Questions about aggregated, de-identified numbers | Ask, open the chart behind an answer | 4 |
| 28 | Health check | Versions, what each recommends, completions and safety stops | Start a new version, open a draft | 2 |
| 29 | Health check: questions | Questions and answers in EN and FR, when each is shown | Add, reorder, set show-if; screener items and the safety question are locked | 2 |
| 30 | Health check: scoring | Rules that add points to specialties, with the reason seekers read | Add a rule, set the specialty order | 2 |
| 31 | Health check: test and review | Sample answer sets run against the draft, beside the published version | Run tests, submit, approve, return with comments | 2 |
| 32 | Payment providers and routes | Each aggregator account, its keys, circuit and money in flight; which providers collect, refund and pay out per method (D61) | Add, test, change, activate (four checks), drain or disable a provider; change routes | 1 |
| 33 | SMS and email senders | Each sender by channel and priority, and whether its keys are found (D61) | Add, test or change a sender | 1 |
| 34 | Scheduled jobs | Every job of Core, Tracking and AI from its trigger table, with its schedule and last run (D61) | Pause, resume or reschedule a job; followed from the service's next restart | 1 |
| 35 | Notification copy | Every push, SMS and email in EN and FR, beside the approved wording (D61) | Edit both languages, restore the approved wording | 1 |
| 36 | AI models and routes | Models, each feature's route and monthly budget, and pending routes with their evaluation (D61) | Add or change a model, propose a route, evaluate it, put it live or discard it, set a budget | 3 |
| 37 | Reviewed lists | Guardrail wording, safety phrases, evaluation sets, the companion's corpus and wording, Tracking's rule wording (D61) | Draft a version, run its check, sign off or decline | 2 (Tracking), 3 (AI) |

Screens 32 to 37 open from their area's page (Payments; Settings and flags; Content; AI controls; Tracking modules) and are not in the sidebar. They follow the area's row in the matrix, narrowed by D61:

- Providers, routes, senders, jobs and AI models: only a Super admin changes them, with a reason. The other roles that can see the area look.
- Notification copy: Super admins and Content editors change it.
- Reviewed lists: a Clinical reviewer or a Super admin drafts a version; a different Clinical reviewer signs it off.

The element library is not its own screen: the elements step of the builder shows it, read-only. Every screen is drawn on the [Solace Admin Portal](https://claude.ai/artifact/CF8vRe3ntoFM6FVaNgEzvf) canvas, plus a phone layout of the crisis queue for on-call responders.

## Workflows

Five workflows carry most admin work; each ends in an audit entry.

### Specialist application

> Diagram: Specialist application · 6 states, 7 admin or specialist actions (in the live doc, linked from START_HERE.md)

Reviewers check the licence with the issuing body, the identity document, each declared language in a short interview, and the services and prices. Approval needs every item ticked. A rejection or change request carries a reason the applicant sees in the app.

### Crisis alert

1. The safety monitor raises `ai.risk.flagged`. The alert enters the crisis queue, and the on-call crisis responder gets a push and an SMS.
2. An alert not acknowledged within 5 minutes escalates to the backup responder, and after 5 more minutes to a Super admin.
3. The responder sees only what the alert needs: the flagged text, its category, the contact the person gave, and any upcoming session.
4. They act: call, message, involve the person's specialist, or direct them to emergency services.
5. They record the outcome and close the alert.

### Refund or no-show dispute

1. A seeker or specialist contacts support, or attendance records flag a no-show.
2. Support opens the booking detail: timeline, join and leave times, payment.
3. The cancellation policy in force when the booking was made proposes the amount; support can change it with a reason.
4. Every refund then waits for a second approver in Finance, who cannot be the person who proposed it. Both steps are written to the audit log; the ledger posts, then the provider refund runs.
5. Both parties are told the result in their own language.

### Payout run

1. On the payout cadence, Finance opens a run: each specialist's released balance, net of commission.
2. Finance holds items with an open dispute or failed payout details.
3. A second Finance person or a Super admin approves, and the run is sent through the aggregator.
4. Failed payouts return to the specialist's record with a reason; the run report can be exported.

### Module publishing

As set out in Solace — Tracking Service Spec: Draft, In review, Published, Retired. The clinical reviewer who approves a version cannot be the person who drafted it. Health check versions follow the same steps, and every version keeps the locked safety rule.

## Rules every screen follows

- **Audit:** every change, and every view of a person's record or a crisis alert, writes an audit entry with who, what, when and the reason given.
- **Reasons:** changing money, a specialist's status, a policy, a setting or a module needs a written reason.
- **Two people for money:** a payout run and every refund, at any amount, need a second approver who did not prepare it.
- **No clinical content:** no screen shows tracking entries, notes, health check answers or transcripts; health check tests use made-up answer sets only. Crisis responders see only the flagged text. A Super admin can open more through break-glass access, which needs a reason and is reviewed.
- **Masked contacts:** phone numbers and emails show masked (for example +237 6•• •• •• 34). Revealing one is a logged action.
- **Bilingual content:** nothing seekers see can publish without both EN and FR; editors work side by side.
- **Admin language:** the portal itself is in EN and FR, set per staff member.
- **Sign-in:** SSO with a second factor for every role, a short idle timeout, and sessions ended when a role changes.
- **Devices:** designed for laptops at 1280 px and wider. The crisis queue also works at phone width, because responders are on call.
- **States:** every list has empty, loading and error states, and every destructive action asks for confirmation.

## Open questions

- [x] **Refund approval threshold:** settled. Every refund, at any amount, needs a second approver in Finance who did not propose it, and both steps go to the audit log.
- [x] **Crisis acknowledgement target:** settled at 5 minutes, then the backup responder; 5 minutes later, a Super admin.
- [x] **Staff sign-in:** settled. Keycloak accounts created by a Super admin, with a one-time temporary password (72 hours); first sign-in sets a new password and an authenticator app.
- [x] **Licence checks:** which bodies issue licences for each specialty in Cameroon, and can they be checked online?
- [x] **Data requests:** confirm the legal deadline for answering export and erasure requests.
