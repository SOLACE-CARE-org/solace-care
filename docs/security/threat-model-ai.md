# Threat model: AI

**Scope:** the AI service, its database (with pgvector), its model gateway and the model vendors behind it. The safety monitor is the only AI feature at public launch (D12); tracking insights and the pre-session brief come in Phase 3, the scribe and companion in Phase 4, each behind a feature flag. Status as of Phase 3: the safety monitor, the model gateway, prompt versions with evaluation and sign-off, tracking insights, translation drafts, moderation suggestions and the pre-session brief (D56).

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
4. AI → Tracking gRPC: consented trend summary (Phase 3). Tracking → AI gRPC: reword a stored recommendation from its evidence (`ai:insight.invoke`).
7. AI → Core gRPC: brief sources, with the health check summary already built by rules (`core:brief-sources.read`). Core → AI gRPC: translation drafts (`ai:translation.invoke`).
8. Specialist app → gateway → AI REST: the pre-session brief for their own booking.
5. AI → model gateway → model vendors, hosted or self-hosted. **This is where data may leave Cameroon.**
6. AI → object storage: scribe audio (Phase 4).

## Threats

| ID | STRIDE | Threat | Control | Status |
| --- | --- | --- | --- | --- |
| AI-1 | I | Health data sent to a foreign model without the transfer authorisation Cameroon's law requires | Every model call goes through one model gateway; health data goes to a non-local model only with authorisation, otherwise to a self-hosted model; Phase 3 gate requires it | In place in code: `ModelGateway` sends a prompt with health data only to a route marked local until `solace.ai.transfer-authorised` is set; no route means the rule-built text (`AiPlatformTest`). Vendors still open (decisions.md) |
| AI-2 | I | A vendor keeps or trains on Solace data | Contracts with no training and no retention; pseudonymous ids only; PII redaction in the model gateway | Partly in place: the gateway redacts names, phones and emails before any call (`Redactor`, `AiPlatformTest`); contracts open |
| AI-3 | S | Crisis text written to slip past the classifier (spelling, code-switching, Camfranglais, instructions to the model) | Independent classifier apart from any chatbot; conservative thresholds; crisis recall of at least 99% on EN and FR sets, including code-switched text, as a release gate | Partly in place: a bilingual phrase classifier tuned to over-flag passes the EN, FR and code-switched safety set (`PhraseClassifierTest`), flagged if any classifier flags; the 99% gate on a clinician-built set and a self-hosted model wait for clinicians and the model choice (D39) |
| AI-4 | D | The safety monitor is down or slow | Reviews are held for human moderation; the crisis button and urgent help never depend on AI | In place: on a failed check the review waits for a person (`ReviewSafetyTest`); Urgent help is bundled in the app and works offline (D39) |
| AI-5 | T | A model output gives a diagnosis, a severity label or medical advice | Input and output guardrails; a system policy that refuses to diagnose or prescribe; tracking insights only reword a rule's evidence | In place: `Guardrails` refuse clinical wording, numbers not in the evidence and long outputs, and the clinician-approved text is used instead; health check answers never reach a model (D8, D56) |
| AI-6 | I | Prompts and outputs kept longer than 30 days | In place: `ai_call` texts are cleared after `[30]` days by a scheduled job; erasure deletes the person's calls |
| AI-7 | E | The AI service reads more tracking data than the summary | Its client may only request `tracking:summary.read` | In place (`RealmsIntegrationTest.aiServiceCanOnlyReadTrackingSummaries`) |
| AI-8 | I | Scribe audio or transcripts kept or leaked | Recorded only when both people consent; encrypted storage; audio deleted after the note is signed or within 7 days | Planned (Phase 4) |
| AI-9 | R | Nobody can tell why a review was held or a flag raised | Each decision stores the model, prompt and policy version and is published as an event; prompts and outputs kept 30 days | In place for the safety monitor: each decision keeps the classifier and policy version and is published as `ai.risk.flagged`, ids and versions only; the text is never stored (`AiApplicationTest`) |
| AI-10 | I | Personal data ends up in the embeddings store | pgvector holds only vetted, bilingual content; the companion answers only from it | Planned (Phase 4) |
| AI-11 | D | Model costs run away | In place: monthly token budgets per route (`[1 000 000]` by default), fallback down the route list, `aiAssist` flag off by default |
| AI-12 | T | A prompt or policy changed without review | Versioned prompts and policies; changes evaluated on the bilingual sets before release; admin AI screen is Phase 3 | In place: a version is promoted only after a passing evaluation (`[95]`%) and a Clinical reviewer's sign-off, and a feature is switched on only while its live version passed; every step is audited (admin screen 26). The safety monitor's set waits for clinicians |
| AI-13 | I | Notes reach AI | Never, except through the seeker's separate D1 opt-in | Planned (Phase 4) |
| AI-14 | E | A specialist reads another booking's brief | The brief checks the booking belongs to the caller through Core, which audits each health check read; tracking lines need the seeker's sharing grant | In place (`AiPlatformTest`, `TrendSummaryEndpoint`) |
| AI-15 | T | An AI draft (translation, bio, moderation suggestion) goes live unchecked | Drafts only fill a field or add an advisory pill; a person saves, publishes or decides | In place (D56) |

## Findings

1. **Which vendor for the safety monitor's classifier (AI-1, AI-2).** Phase 1 needs a classifier for review text. If it is hosted abroad, the transfer authorisation is needed before launch; a self-hosted classifier avoids it. Decide with the lawyer before the safety-monitor epic starts.
2. **Over-flagging is a safety trade-off, not a bug.** A conservative classifier will flag harmless reviews; the human crisis queue absorbs them. Staff the queue for it (crisis on-call is still open in decisions.md).
3. **Prep notes are not AI input.** A specialist's private prep notes stay in Core and no model or rule reads them (D56).
