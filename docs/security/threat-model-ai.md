# Threat model: AI

**Scope:** the AI service, its database (with pgvector), its model gateway and the model vendors behind it. The safety monitor is the only AI feature at public launch (D12); tracking insights and the pre-session brief come in Phase 3, the scribe and companion in Phase 4, each behind a feature flag. Status as of Phase 0: the service skeleton, security and the `ModerationService` contract exist.

## What is protected

| Asset | Sensitivity |
| --- | --- |
| Text sent to the safety monitor: reviews (Phase 1), companion messages (Phase 4) | Personal, free text, may hold **health data** |
| Prompts and outputs, kept 30 days for safety audit | As above |
| Risk flags (`ai.risk.flagged`) | **Health data** |
| Session audio and transcripts (Phase 4) | **Health data**, the most sensitive on the platform |
| Draft notes (Phase 4) | **Health data** |
| Vetted content and its embeddings | Not personal |
| Model, prompt and policy versions; evaluation sets | Integrity-critical |

## Trust boundaries and flows

1. Core → AI gRPC: moderate a review (fallback: held for human moderation).
2. Kafka → AI: review submitted, consent changed, bookings, session ended, user erased.
3. AI → Kafka: risk flagged, moderation decided, note drafted.
4. AI → Tracking gRPC: consented trend summary (Phase 3).
5. AI → model gateway → model vendors, hosted or self-hosted. **This is where data may leave Cameroon.**
6. AI → object storage: scribe audio (Phase 4).

## Threats

| ID | STRIDE | Threat | Control | Status |
| --- | --- | --- | --- | --- |
| AI-1 | I | Health data sent to a foreign model without the transfer authorisation Cameroon's law requires | Every model call goes through one model gateway; health data goes to a non-local model only with authorisation, otherwise to a self-hosted model; Phase 3 gate requires it | Planned; vendors still open (decisions.md) |
| AI-2 | I | A vendor keeps or trains on Solace data | Contracts with no training and no retention; pseudonymous ids only; PII redaction in the model gateway | Planned (Phase 1 for the safety monitor's model) |
| AI-3 | S | Crisis text written to slip past the classifier (spelling, code-switching, Camfranglais, instructions to the model) | Independent classifier apart from any chatbot; conservative thresholds; crisis recall of at least 99% on EN and FR sets, including code-switched text, as a release gate | Partly in place: a bilingual phrase classifier tuned to over-flag passes the EN, FR and code-switched safety set (`PhraseClassifierTest`), flagged if any classifier flags; the 99% gate on a clinician-built set and a self-hosted model wait for clinicians and the model choice (D39) |
| AI-4 | D | The safety monitor is down or slow | Reviews are held for human moderation; the crisis button and urgent help never depend on AI | In place: on a failed check the review waits for a person (`ReviewSafetyTest`); Urgent help is bundled in the app and works offline (D39) |
| AI-5 | T | A model output gives a diagnosis, a severity label or medical advice | Input and output guardrails; a system policy that refuses to diagnose or prescribe; tracking insights only reword a rule's evidence | Planned (Phase 3) |
| AI-6 | I | Prompts and outputs kept longer than 30 days | Retention job deletes them at 30 days | Planned (Phase 1) |
| AI-7 | E | The AI service reads more tracking data than the summary | Its client may only request `tracking:summary.read` | In place (`RealmsIntegrationTest.aiServiceCanOnlyReadTrackingSummaries`) |
| AI-8 | I | Scribe audio or transcripts kept or leaked | Recorded only when both people consent; encrypted storage; audio deleted after the note is signed or within 7 days | Planned (Phase 4) |
| AI-9 | R | Nobody can tell why a review was held or a flag raised | Each decision stores the model, prompt and policy version and is published as an event; prompts and outputs kept 30 days | In place for the safety monitor: each decision keeps the classifier and policy version and is published as `ai.risk.flagged`, ids and versions only; the text is never stored (`AiApplicationTest`) |
| AI-10 | I | Personal data ends up in the embeddings store | pgvector holds only vetted, bilingual content; the companion answers only from it | Planned (Phase 4) |
| AI-11 | D | Model costs run away | Per-feature budgets in the model gateway; features behind flags | Planned |
| AI-12 | T | A prompt or policy changed without review | Versioned prompts and policies; changes evaluated on the bilingual sets before release; admin AI screen is Phase 3 | Planned |
| AI-13 | I | Notes reach AI | Never, except through the seeker's separate D1 opt-in | Planned (Phase 4) |

## Findings

1. **Which vendor for the safety monitor's classifier (AI-1, AI-2).** Phase 1 needs a classifier for review text. If it is hosted abroad, the transfer authorisation is needed before launch; a self-hosted classifier avoids it. Decide with the lawyer before the safety-monitor epic starts.
2. **Over-flagging is a safety trade-off, not a bug.** A conservative classifier will flag harmless reviews; the human crisis queue absorbs them. Staff the queue for it (crisis on-call is still open in decisions.md).
