# Threat model: Tracking

**Scope:** the Tracking service, its database and its gRPC API (`solace.tracking.v1`). Tracking holds the most sensitive data on the platform: daily health observations and free-text notes, keyed by pseudonymous user id. Status as of Phase 0: the service skeleton, security and gRPC endpoint exist; tracking itself is Phase 2.

## What is protected

| Asset | Sensitivity |
| --- | --- |
| Observations (mood, sleep, flow, period events, acne, hair growth, stress, ...) | **Health data** |
| Notes (`entry_note`) | **Health data, free text.** Storage only: never read by rules, never scanned, hard-deleted on delete |
| Specialist access grants (forward-only, revocable; notes need a separate yes) | Controls who sees health data |
| Recommendations and their evidence | **Health data** |
| Context answers (e.g. trying to conceive, hormonal treatment) | **Health data.** Read only by the "unless" guard, never sent anywhere |
| Modules, elements, episode models, context questions and rules | Integrity-critical: a bad definition or rule shows a seeker a wrong pattern or invite. Admin-defined since D61 |

## Trust boundaries and flows

1. Mobile app → gateway → Tracking REST (`/v1/tracking`): sync push and pull, own observations, consent grants, specialist reads.
2. Tracking → Core gRPC: live-booking check on every specialist read (never cached), taxonomy (cached 1 hour).
3. AI → Tracking gRPC: consented trend summary only (`TrendSummaryService`, scope `tracking:summary.read`).
4. Core → Tracking events: user registered, erased, consent changed, bookings. Tracking → Core events: module published, recommendation created and resolved, user erased.
5. Admin portal → Core → Tracking: module builder and rules (Phase 2).

## Threats

| ID | STRIDE | Threat | Control | Status |
| --- | --- | --- | --- | --- |
| TR-1 | E | A specialist reads a seeker's entries without a live booking | Core's `CheckLiveBooking` on every read, never cached; if Core does not answer in 2 seconds, access is denied | In place: `LiveBookings` through `ResilientCalls`, fallback deny (D52; `CareIntegrationTest`) |
| TR-2 | E | A specialist keeps reading after the seeker revokes access | Grant checked on every read; forward-only, so revoking stops all further reads | In place (D52; `CareIntegrationTest`) |
| TR-3 | I | Notes shown to a specialist without the separate yes | Notes need their own grant, apart from entries; without it the note column is not even selected | In place (D52; `CareIntegrationTest`) |
| TR-4 | I | Notes read by rules, AI or anything else | Rules never read `entry_note`; only the AI layer's separate D1 opt-in can send notes anywhere; nothing scans them | In place: `NotesNeverLeaveTest` checks every seeker, specialist and admin read, the outbox and the audit log, and that only `Sync.java` touches `entry_note` (the Phase 2 gate) |
| TR-5 | I | Notes or values in events, logs or traces | Tracking sends no event about individual entries; envelope carries only `subjectId`; log scrubber | Envelope and scrubber in place; Planned use |
| TR-6 | I | Deleted notes recoverable | Hard delete on delete (not `deleted_at`); erasure deletes every row for the user | Planned (Phase 2); backups keep them until they expire, see Findings 2 |
| TR-7 | T | A seeker writes entries into someone else's history through sync | Writes accepted only for the token's subject; idempotent on device and client ids | Planned (Phase 2) |
| TR-8 | R | A specialist's read of entries leaves no trace | Audit entry on every read of tracking entries and notes, and on every refusal | In place (D52) |
| TR-9 | S / E | Another service calls Tracking's gRPC methods | Audience `tracking` and a scope per method; network policy allows only Core (and AI for the summary) | Scopes in place (`RealmsIntegrationTest.trackingServiceGetsOnlyItsOwnScopes`, `aiServiceCanOnlyReadTrackingSummaries`); see Findings 1 |
| TR-10 | I | AI gets more than the consented summary | One method, one scope; consent checked before answering | Scope in place; consent check Planned (Phase 3) |
| TR-11 | T | A rule shows a harmful pattern or invite | Rules state facts against thresholds only, no scores or diagnoses (D10); clinical review by someone other than the author; test runs on synthetic histories | Planned (Phase 2) |
| TR-12 | I | Recommendation evidence quotes a note | Evidence comes from observations only, never notes | Planned (Phase 2) |
| TR-13 | I | Staff browse individual seekers' entries in the admin portal | Not specified: the portal has module and rule tools, not entry views | Open; Findings 3 |
| TR-15 | T | An admin publishes an element, episode model or context question that misleads or collects more than it should | Same review path as modules: drafted by Operations, approved by a Clinical reviewer who did not draft it, published by Operations, every step audited; elements only from registered views; frozen once published (D61) | Elements in place (`ElementBuilderIntegrationTest`, `ElementRulesTest`); episode models and context questions are still seeded by migration |
| TR-16 | I | Context answers used for more than switching rules off | Read only by the "unless" guard; never in events, evidence or the AI summary; an unanswered question never switches a rule off (D61) | Planned (Phase 2b) |
| TR-14 | D | Core down blocks tracking | Seekers keep logging (offline-first, sync later); specialist reads fail closed; taxonomy from cache | Planned (Phase 2) |
| TR-16 | I | A nudge reveals what someone tracks | `tracking.nudge.requested` carries the kind only; Core sends the neutral copy, push only, and pauses after `ai.risk.flagged` | In place (D52) |
| TR-15 | I | Erasure misses Tracking | Consumes `core.user.erased`, deletes everything (grants, nudges and pauses too), confirms with `tracking.user.erased`, which Core writes to its audit log | In place (D40, D52) |
| TR-17 | I | The trend summary for the brief leaks more than the seeker shared | `GetTrendSummary` checks the seeker's grant to that specialist, covers [14] days, returns rule-built lines only (no notes) and audits the read | In place (D56) |
| TR-18 | T | An AI rewording changes what a recommendation says | AI only rewords from the stored evidence, through guardrails; the clinician-approved message stays when it fails or AI is down; the app says when wording is by AI | In place (D56, `RecommendationWording`) |

## Findings

1. **AI → Tracking network path (TR-9).** The network policy (`infra/k8s/base/network-policies.yaml`) lets only Core call gRPC today. When the pre-session brief arrives (Phase 3), add AI to Tracking's gRPC rule, for that port only.
2. **Deleted notes in backups (TR-6).** Point-in-time recovery (RPO 15 minutes) means a hard-deleted note survives in backups until they expire. State the backup retention in the register and in the privacy notice; do not restore deleted notes when restoring a backup (replay erasures after any restore).
3. **Staff access to entries (TR-13).** The specs give staff no view of individual entries. Confirm that none is wanted, including break-glass during a crisis (the System Design's break-glass access is for clinical data "while a booking relationship is active", which concerns specialists). If break-glass is wanted for crisis responders, it needs a reason, an audit entry and a review.
