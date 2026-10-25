# Configuration inventory

Every value that changes how Solace behaves, where it lives today, and how it changes. Gathered from the code on 21 October 2026, as the first step towards one way of setting values from the admin portal without a rebuild or restart. The strategy is section 7; it was added on 25 October 2026 and is a proposal until confirmed.

**How a value changes today**

| Mark | Meaning |
| --- | --- |
| **Live** | Stored in a database table and read when used. Changed in the admin portal; takes effect within a minute, no restart. |
| **Restart** | Read from `application.yml` or an environment variable when the service starts. Changing it means changing the deployment and restarting the service. |
| **Rebuild** | Written in Java code. Changing it means a code change, a build and a deploy. |

**Candidate** says whether the value should be settable by staff. **Yes**: a business value that staff may need to tune. **Show only**: a fixed decision (CLAUDE.md, "numbers without brackets"), shown in the portal but not editable. **No**: infrastructure, secrets or internal limits that must stay in deployment config or code.

## 1. Already live from the admin portal (Core only)

Core keeps `platform_setting` and `feature_flag` rows (D42). The Settings and flags screen (admin screen 20) changes them with a reason; Core re-reads them every `[30]` seconds (`solace.admin.settings-refresh`). The value in `application.yml` or code is only the fallback when no row exists.

| Setting | Default | Used in |
| --- | --- | --- |
| `solace.specialists.pricing.fee-percent` | 20 % | LivePricing |
| `solace.specialists.pricing.min-price` | 5,000 FCFA | LivePricing |
| `solace.specialists.pricing.max-price` | 20,000 FCFA | LivePricing |
| `solace.sessions.no-show-after` | 15 min | Sessions |
| `solace.sessions.complete-after` | 15 min | Sessions |
| `solace.reviews.window` | 14 days | Ratings |
| `solace.reviews.min-ratings-shown` | 3 | Ratings |
| `solace.notifications.quiet-start` / `quiet-end` | 21:00 / 07:00 | Notifier |
| `solace.privacy.export-lifetime` | 7 days | DataRights |
| `solace.privacy.request-deadline` | 30 days | DataRights |
| `solace.privacy.guest-retention` | 365 days | Guests |
| `solace.privacy.message-retention` | 180 days | Notifier |
| `solace.tracking.sharing-after-session` | 7 days | BookingAccessEndpoint |
| `solace.health-check.unclaimed-retention` | 30 days | Responses |
| `solace.health-check.retake-after` | 90 days | Responses |
| `solace.health-check.also-fit-within` | 1 point | Responses |
| `solace.health-check.price-band-1` / `-2` | 15,000 / 25,000 FCFA | HealthCheckController |
| `solace.guests.code-lifetime` | 10 min | Guests |
| `solace.guests.code-attempts` | 3 | Guests |
| Shown, not editable: refund approvers (2), slot hold (10 min), crisis escalation (5 min), staff idle sign-out (15 min, set in Keycloak) | | |

**Feature flags (live):** safetyMonitor (locked on), cardPayments, specialistInterviewPicker, payoutBankTransfer, payoutOnRequest (locked off), onCallSpecialistCall, trackTab, healthCheck, aiAssist, sessionScribe, companion, adminCopilot, phoneDialIn.

**Other content already live (own tables and admin screens):** cancellation policy versions (screen 14), catalogue entries (screen 14), content and crisis numbers (screens 15–16), tracking modules and rule thresholds (screens 21–25), health check questions and scoring (screens 28–31), AI prompt and policy versions with evaluation and sign-off (screen 26), the on-call rota (screen 19).

So min and max price and the commission **are already live**: the values in `application.yml` and code are only fallbacks. Tracking and AI have **no** settings table yet.

## 2. Business values that need a restart (config files)

| Service | Key | Value | Candidate | Note |
| --- | --- | --- | --- | --- |
| AI | `solace.ai.models` | none | **Yes** | Each model: name, URL, model name, local or not. The API key must stay a secret (see section 5). |
| AI | `solace.ai.routes` | none | **Yes** | Ordered models per feature. |
| AI | `solace.ai.budgets` | 1,000,000 tokens a month per feature | **Yes** | |
| AI | `solace.ai.speech` / `solace.ai.embeddings` | none | **Yes** | Speech-to-text and embedding models. |
| AI | `solace.ai.transfer-authorised` | false | **Yes, Super admin with a reason** | Lets health data reach non-local models; legal decision. |
| AI | `solace.ai.retention-days` | 30 | **Yes** | Prompt and output texts kept for audit. |
| AI | `solace.ai.eval-gate` | 95 % | **Yes** | Evaluation pass mark. |
| AI | `solace.ai.homework.default-due` | 7 days | **Yes** | |
| AI | `solace.safety.recall-gate` | 99 % | **Show only** | Release gate from the System Design. |
| AI | `solace.safety.model.enabled` / `url` / `name` | off | **Yes, Super admin** | The self-hosted safety model. |
| AI | `solace.safety.policy-version` | safety-1 | No | Set by a release with the policy. |
| Core | `solace.brief.notice` | 60 min | **Yes** | When the Brief ready push goes. |
| Core | `solace.insight.booking-drop-percent` | 50 % | **Yes** | Copilot alert. |
| Core | `solace.insight.payment-failure-factor` | 2× | **Yes** | Copilot alert. |
| Core | `solace.insight.min-volume` | 5 | **Yes** | Copilot alert. |
| Core | `solace.insight.min-cell` | 5 | **Yes, Super admin** | Small-cell suppression for de-identified metrics. |
| Core | `solace.specialists.pricing.durations` | 30, 50, 80 min | **Yes** | Session lengths specialists can offer (D43 left them in config until a spec says who changes them). |
| Core | `solace.specialists.documents.max-file-size` | 10 MB | Maybe | |
| Core | `solace.bookings.guest-token-after-end` | 1 day | **Yes** | How long a guest link works after the session. |
| Core | `solace.identity.unverified-signup-ttl` | 24 h | **Yes** | Unfinished sign-ups are removed after this. |
| Core | `solace.identity.terms-version` / `privacy-notice-version` | v1 | No | Superseded by published legal content (D44). |
| Core | `solace.specialists.terms-version` / `code-of-conduct-version` | v1 | No | Same. |
| Core | `solace.privacy.offered-purposes` | AI_ASSIST, AI_SCRIBE | **Yes** | Which consents the app offers. |
| Core | `solace.privacy.wording-versions` | per purpose | No | Follows the consent wording release. |
| Core | `solace.payments.poll-interval` | 20 s | No | Technical. |
| Core | `solace.payments.pending-timeout` | 10 min | **Show only** | Equals the slot hold. |
| Core | `solace.payments.reconciliation-cron` | 03:00 daily | Maybe | |
| Core | `solace.payments.provider` | sandbox or notchpay | No | Deployment choice. |
| Core | `solace.notifications.link-base` | `https://slc.cm/g/` | No | Domain, deployment. |
| Core | `solace.notifications.default-time-zone` | Africa/Douala | Maybe | |
| Core | `solace.sessions.dial-in.number` | none | **Yes** | The number seekers call. |
| Core | `solace.sessions.dial-in.trunks` | none | No | LiveKit SIP trunk ids, deployment. |
| Core | `solace.admin.settings-refresh` | 30 s | No | Technical. |
| Tracking | `solace.tracking.snooze-days` | 7 | **Yes** | |
| Tracking | `solace.tracking.nudges.reminder-window` | 2 h | **Yes** | |
| Tracking | `solace.tracking.nudges.risk-pause` | 14 days | **Yes** | |
| Tracking | `solace.tracking.nudges.default-zone` | Africa/Douala | Maybe | |
| Tracking | `solace.tracking.cycle.new-period-gap-days` | 10 | **Yes, Clinical reviewer** | Changes how cycles are counted. |
| Tracking | `solace.tracking.pattern-check-cron` | 02:30 daily | Maybe | |
| Gateway | `solace.gateway.rate-limit` | 20/s, burst 40 | Maybe, Super admin | |
| Keycloak | member code length / lifetime / tries / resend delay | 6 / 10 min / 3 / 60 s | **Yes** | Set in the Keycloak authenticator's config today, not in Solace. |

## 3. Business values that need a rebuild (written in code)

| Service | Where | Value | Candidate | Note |
| --- | --- | --- | --- | --- |
| Core | `CrisisAlerts.TO_BACKUP` / `TO_ADMINS` | 5 / 10 min | **Show only** | D11. |
| Core | `Team.PASSWORD_VALIDITY` | 72 h | **Show only** | D7. |
| Core | `Sessions.JOIN_OPENS` | 10 min before | **Show only** | D18. |
| Core | `Sessions.TOKEN_AFTER_END` | 15 min after | **Show only** | System Design, Join flow. |
| Core | `LiveKit.RECONNECT` | 2 min | **Show only** | D18. |
| Core | `Signups.MINIMUM_AGE` | 18 | **Show only** | |
| Core | `Sms.MAX_LENGTH` | 160 | No | GSM-7 limit. |
| Core | `BookingAccessEndpoint.AFTER_SESSION` | 7 days | Already live | Fallback of the live setting. |
| Core | `Slots.HORIZON_DAYS` | 14 days ahead | **Yes** | How far ahead seekers can book. |
| Core | `TimeOffController.LONGEST` | 60 days | **Yes** | Longest time-off block. |
| Core | `WeeklyHoursController` defaults and allowed values | break 0/10/15 min, notice 2/12/24 h | **Yes** | The choices on SpecAvailability. |
| Core | `Applications.MAX_FOCUS_AREAS` | 5 | **Yes** | |
| Core | `Documents.MAX_IDENTITY_FILES` | 2 | No | |
| Core | `Reviews.BOOKABLE` levels | Native, Fluent, Professional | **Show only** | D16. |
| Core | `LicenceRenewals.DAYS_BEFORE` | 60 days | **Show only** | From the notification copy. |
| Core | `Messages.LINK_AFTER_END` | 1 day | **Yes** | Guest SMS link lifetime. |
| Core | `OverviewController.SLOW_PAYMENT` | 5 min | **Yes** | When mobile money shows "slow". |
| Core | `HealthCheckController.MATCHES` / `InviteController.MATCHES` | 10 / 5 specialists | **Yes** | |
| Core | `DirectoryStore.CANDIDATES` | 300 | No | Search performance. |
| Core | `AuditLogController.EXPORT` | 10,000 entries | Maybe | |
| Core | `AdminPeopleController.NEWEST` / page sizes | 50 / 100 / 200 | No | |
| Core | Scheduled times | data requests 08:00, licence reminders 09:00, retention 02:15–03:15, anomalies hourly | Maybe | |
| AI | `Notes.DRAFT_LIFE` | 7 days | **Show only** | System Design, Recording. |
| AI | `Homeworks` limits | 1–14 times, due within 60 days | **Yes** | |
| AI | Output length limits | brief 500, insight 320, note section 2,000, reflection 400 characters | **Yes, with the prompt** | Belong with each prompt version. |
| AI | `ReviewModeration.REASONS` | contact details, offensive, not about the session, looks fake | Maybe | |
| AI | Companion steps per exercise | thought record 4, sleep diary 3, worry time 1, custom 1 | Maybe, Clinical reviewer | |
| Tracking | `Nudges.IGNORED_LIMIT` | 5 | **Yes** | |
| Tracking | `TrendSummaryEndpoint.WINDOW` | 14 days | **Yes** | Days of tracking in the brief. |
| Tracking | `Modules.ELEMENT_WARNING` | 6 | Maybe | |
| Tracking | `ImageAssets` limits | 1 MB, at least 1200×800, 600 px rendition | No | |
| Tracking | `Sync.PULL_LIMIT` | 1,000 | No | |

## 4. Prompts, policies, word lists and copy

| What | Where | Today | Candidate | Note |
| --- | --- | --- | --- | --- |
| AI prompts and the shared policy | `DefaultPrompts` (code) seeds v1.0 into `prompt_version` | **Live**: new versions are created, evaluated, signed off and promoted on AI controls (screen 26) | Already live | The code text is only the first draft. |
| How each prompt's input is laid out | `NoteDrafter`, `Companion`, `Copilot`, `Translations`, `Insights`, `Briefs` | Rebuild | Maybe | Tied to how the output is parsed. |
| Guardrail word lists | Reviewed list `ai.guardrails.clinical` (AI) | **Live (D61)**: drafted and signed off by two clinicians | Built | Always keeps "triage". |
| Rule message word list | Reviewed list `tracking.rules.wording` (Tracking) | **Live (D61)** | Built | Blocked patterns and allowed phrases. |
| Contact-detail patterns | `Redactor` (AI), review contact check (Core) | Rebuild | No | Safety code, tested. |
| Safety monitor phrases | Reviewed list `ai.safety.phrases` (AI) | **Live (D61)**: signed off only once the safety set reaches 99% in EN and FR | Built | The classifier version records the list version. |
| Companion questions and closing lines | Reviewed list `ai.companion.copy` (AI) | **Live (D61)**, by clinicians | Built | EN and FR, same placeholders, no exclamation marks. |
| Notification copy | Core `notification_copy` rows over `notifications/copy*.properties` | **Live (D61)**: edited on the portal by Super admins and Content editors, both languages at once, rules checked on save | Built | The SMS rule holds: GSM-7, the French accent rule, and no longer than the approved text outside its placeholders. |
| Error titles and brief or metric labels | `messages*.properties` in each service | Rebuild | Maybe | |
| App and portal text | `packages/i18n` | Rebuild of the apps | No | Released with the apps. |
| Evaluation sets | Reviewed lists `ai.eval.<feature>` and `ai.eval.safety-monitor` (AI) | **Live (D61)** | Built | The files are version 0. |
| Companion corpus | Reviewed list `ai.companion.corpus` over `companion_passage` (AI) | **Live (D61)** | Built | A changed passage is embedded again. |

## 5. Must stay in deployment config (not admin-editable)

These are infrastructure, identity or secrets. They change with a deployment, and secrets come from the vault.

- **Service addresses and ports:** database, Kafka, Redis, gRPC channels, the gateway's service map, Keycloak issuers and admin URL, LiveKit URLs, MinIO or S3 endpoints and buckets, CORS origins, public paths.
- **Secrets and keys:**
  - The health check, safety plan and notes encryption keys.
  - LiveKit API key and secret; Notch Pay keys and webhook secret.
  - VAPID keys; the Expo access token; S3 credentials.
  - Keycloak client secrets; any AI model API key.
- **Platform choices:** the payment provider, the push sender, event partitions and replicas, OpenTelemetry.

If AI models become editable in the portal, a model's API key should still live in the vault. The portal would only refer to it by name.

## 6. Scheduled jobs (triggers)

Built (D61). Every job was a Spring `@Scheduled` method that always ran, with its schedule in code or in `application.yml`. Each one is now a `@Job` (`backend/libs/jobs`) with a row in a **trigger table in its service's database**:

| Column | Meaning |
| --- | --- |
| name | Stable code, for example `core.bookings.expire-unpaid` |
| description | What the job does, in plain words (EN and FR for the portal) |
| status | `ACTIVE` or `PAUSED`. Every job can be paused and rescheduled (D61) |
| schedule | A cron expression with its time zone, or a fixed interval (`PT1M`) |
| last run, last outcome, next run | Shown in the portal so staff see that a job runs |

Each service reads its trigger rows when it starts and registers only the jobs whose row is ACTIVE (D61). Pausing, resuming or rescheduling a job takes effect at the service's next restart, with no code change.

| Service | Trigger name | What it does | Schedule today | Can be paused? |
| --- | --- | --- | --- | --- |
| Core | `core.crisis.escalate` | Escalates unacknowledged crisis alerts to the backup at 5 min and Super admins at 10 min | every 30 s | Yes, with care |
| Core | `core.bookings.expire-unpaid` | Expires bookings not paid within the 10-minute hold and frees the time | every 30 s (`solace.bookings.expiry-interval`) | Yes, with care |
| Core | `core.payments.poll` | Asks the payment providers about payments, refunds and payouts still on their way | every 20 s (`solace.payments.poll-interval`) | Yes, with care |
| Core | `core.payments.draft-payout-runs` | Drafts the twice-monthly payout run when its period closes | hourly | Yes |
| Core | `core.payments.reconcile` | Compares yesterday's ledger with the provider's records | 03:00 daily (`solace.payments.reconciliation-cron`) | Yes |
| Core | `core.sessions.settle` | Marks no-shows and completes sessions the media server did not report | every minute | Yes, with care |
| Core | `core.sessions.dial-in-rules` | Opens and closes phone dial-in rules as joining opens and ends | every minute | Yes |
| Core | `core.messaging.reminders` | Day-before, joining-opens, 10-minutes-before and Brief ready pushes | every minute | Yes, with care |
| Core | `core.messaging.deliver-waiting` | Sends pushes held back by quiet hours once they end | every minute | Yes |
| Core | `core.messaging.licence-renewals` | Reminds specialists 60 days before their licence ends | 09:00 daily | Yes |
| Core | `core.messaging.forget-old-messages` | Deletes delivered messages past their retention | 02:30 daily | Yes |
| Core | `core.privacy.prepare-exports` | Builds requested data exports | every 30 s | Yes |
| Core | `core.privacy.expire-exports` | Deletes export files past their lifetime | 02:15 daily | Yes |
| Core | `core.privacy.data-request-reminders` | Emails Operations about data requests due this week | 08:00 daily | Yes |
| Core | `core.identity.remove-unverified` | Removes sign-ups never verified within 24 h | every 15 min (`solace.identity.unverified-cleanup-interval`) | Yes |
| Core | `core.health-check.forget-unclaimed` | Deletes guest health check answers never claimed | 03:15 daily | Yes |
| Core | `core.insight.anomalies` | Checks for booking drops and failed-payment spikes for the copilot | hourly | Yes |
| Core | `core.admin.settings-reload` | Reloads platform settings and flags | every 30 s (`solace.admin.settings-refresh`) | Yes, with care |
| Core | `core.payments.providers-reload` | Reloads payment providers and routes (D61) | every 30 s | Yes, with care |
| Core | `core.notifications.copy-reload` | Reloads notification copy edited on the portal (D61) | every 30 s | Yes, with care |
| Core | `core.guests.purge-contacts` | Removes guest phone numbers `[12]` months after their last booking | 02:45 daily | Yes |
| AI | `ai.gateway.forget-texts` | Clears prompt and output texts older than `[30]` days | 02:30 daily | Yes, with care |
| AI | `ai.scribe.sweep` | Deletes unsigned drafts and session audio after 7 days | hourly | Yes, with care |
| AI | `ai.scribe.draft-waiting` | Transcribes and drafts notes for waiting session audio | every minute | Yes |
| Tracking | `tracking.nudges.reminders` | Daily tracking reminders and invite pushes | every minute (`solace.tracking.nudges.every`) | Yes |
| Tracking | `tracking.rules.nightly` | Runs the pattern check for everyone enrolled | 02:30 daily (`solace.tracking.pattern-check-cron`) | Yes |

## Summary

| | Live today | Needs a restart | Needs a rebuild |
| --- | --- | --- | --- |
| Settings | 22 Core settings + 13 flags | about 30 business values (AI models and routes, Tracking nudges, Core insight and session lengths, Keycloak codes) | about 25 business numbers, half of them fixed decisions to show only |
| Content | policies, catalogue, content, modules, health check, prompts, rota | none | guardrail and rule word lists, safety phrases, notification and companion copy, evaluation sets |

Main gaps: **Tracking and AI have no settings table**; **AI models are config-only**; **notification copy and the clinical word lists are in code**.

## 7. Strategy

**Status:** accepted as D61 on 25 October 2026; the answers to the open questions are at the end of this section.

- **Built:** the Core values of tranche 1 (live on screen 20, with the fixed decisions shown beside them), payment providers and routes, and SMS and email senders (tranche 4), the trigger table for every job (tranche 3), and editable notification copy (part of tranche 5). Each has its API, contract, tests, EN and FR texts and audit.
- **Built since:** tranche 2 (Tracking and AI settings from Core), AI models, routes and budgets, and the reviewed lists of tranche 5.
- **Also built:** session lengths, the consents the app offers and the dial-in number, with list, code and phone setting types.
- **Kept in code:** the weekly-hours break and notice choices. The designed availability screen offers exactly three of each.
- **Still to do:** the admin screens for the registries, jobs, copy and lists.

### Principles

1. **Each business value has one place to change it: the admin portal.** There is one mechanism for each kind of value:
   - settings
   - flags
   - triggers
   - content with review
   - provider registries

   A value that is not a candidate (sections 2 to 5, "No") stays in deployment config or code.
2. **Core owns every platform setting and flag, including those Tracking and AI use.**
   - This is what the System Design already says. Admin & config: "Versioned settings with audit trail; changes publish a config-changed event". Event catalog: `core.config.changed` lets Tracking and AI "refresh cached settings, feature flags, specialties".
   - Today Core publishes the event, but no service consumes it.
   - Tracking and AI therefore get **no settings tables of their own**. This also keeps "no service reads another's tables": they ask Core.
3. **The definition lives in code, the value in data.**
   - Every setting has a definition in Core's catalogue (`SettingCatalogue`): key, owning service, group, unit, the spec's bracketed default, minimum and maximum, who may change it, and whether a second person must approve.
   - Its label and help text are in `packages/i18n` in EN and FR.
   - A value without a definition cannot be set. The `application.yml` value stays as the fallback, as now.
4. **Fixed decisions are shown, never stored.**
   - Values without brackets stay constants in code. The catalogue lists them as not editable.
   - A unit test checks that each listed value equals its constant, so the screen can never show a number the code does not use.
5. **Every change follows the same path.**
   - It is a new version with a reason, written to the audit log.
   - It takes effect within a minute, with no restart. The screen shows "in force since".
   - The previous version can be restored in one step, which is itself a new version.
6. **Riskier values get stronger checks.** The rules mirror ones that already exist:

   | Kind of value | Check | Existing rule it copies |
   | --- | --- | --- |
   | Money or legal: fee, transfer authorisation, small-cell size, payment routes | A second person who did not propose it | D14 |
   | Changes what a clinical value means: cycle gap, clinical word lists | A Clinical reviewer proposes; a different one approves | Tracking modules |
   | Safety phrases and evaluation sets | Must pass the evaluation gate before promotion | Prompts, screen 26 |
   | Notification copy | Validated for both languages, 160 GSM-7 characters with placeholders filled, and the French accent rule | notification-copy.md rules |

7. **Read values when they are used, not at startup.**
   - Code reads a live value at the moment of use, as `LivePricing` does, not through a field bound once.
   - A schedule that depends on a value is rescheduled when the value changes (see triggers).
8. **External providers are registries.**
   - The adapter type is code; each account is a row; keys stay in Vault, referenced by name.
   - Keys are mounted as files, so a new key needs no restart.
   - This covers payment providers (designed in `docs/proposals/payment-providers.md`), AI models and their routes, and the SMS and email senders, for which no real adapter exists yet.

### How Tracking and AI read Core's settings

Contract first: a new gRPC call on Core, `ConfigService.GetSettings(service)`. It returns the values in force for that service's keys, plus the flags. It has the standard 2-second deadline and a circuit breaker.

| Step | What happens |
| --- | --- |
| Start | The service loads its settings from Core. If Core does not answer, it starts with its own `application.yml` fallbacks and retries. |
| Change | Core publishes `core.config.changed` (already in the contract). The service reloads that key from Core, idempotently on `eventId`. |
| Safety net | The service also reloads every `[5]` minutes, in case an event is missed. |
| Fallback | If Core is unavailable, the service keeps the last values it loaded and logs it. It never falls back silently to a default that differs from what staff set while it still has a cached value. |

### Where each value goes

**Tranche 1: Core values into the existing catalogue.** No new mechanism.

- **Editable:**
  - `solace.brief.notice`, `solace.insight.*` (`min-cell` with a second approver), `solace.specialists.pricing.durations`, `solace.bookings.guest-token-after-end`, `solace.identity.unverified-signup-ttl`, `solace.privacy.offered-purposes`, `solace.sessions.dial-in.number`.
  - From code: `Slots.HORIZON_DAYS`, `TimeOffController.LONGEST`, the `WeeklyHoursController` choices, `Applications.MAX_FOCUS_AREAS`, `Messages.LINK_AFTER_END`, `OverviewController.SLOW_PAYMENT`, and the two `MATCHES` counts.
  - Check whether `guest-token-after-end` and `Messages.LINK_AFTER_END` are the same rule (both 1 day). If so, they become one setting.
- **Shown, not editable:** crisis escalation 5 and 10 minutes, the 72-hour temporary password, joining 10 minutes before, the token 15 minutes after the end, 2-minute reconnection, minimum age 18, licence reminder 60 days, bookable language levels, the safety recall gate of 99%, and the pending timeout equal to the hold.

**Tranche 2: Tracking and AI values through Core.** This needs the gRPC call above and a consumer of `core.config.changed` in each service.

- **Tracking:**
  - Snooze days, the reminder window, the risk pause, `Nudges.IGNORED_LIMIT`, `TrendSummaryEndpoint.WINDOW`.
  - `solace.tracking.cycle.new-period-gap-days`, which needs Clinical reviewer approval by two different people.
- **AI:**
  - `retention-days`, `eval-gate`, the default homework due date and homework limits, and budgets for each feature.
  - `transfer-authorised`: Super admin, with a second Super admin, because it is a legal decision.
  - Output length limits stay with each prompt version on screen 26, not here.

**Tranche 3: triggers (section 6).**

- Schedules are runtime state (last run, next run, outcome) owned by the service that runs them. So each service keeps a `job_trigger` table, as section 6 proposes.
- The portal shows all of them on one screen through each service's admin API, the way it already reaches Tracking for modules and AI for prompts.
- Every job can be paused and rescheduled; the portal warns beside the jobs that safety or money depend on.

**Tranche 4: registries.**

- **Payment providers:** per the proposal document.
- **AI models, routes, speech and embedding models:** move into AI's database as `ai_model` and `ai_route`, with keys referenced in Vault. A new route takes effect only after the feature's evaluation set passes on it.
- **SMS and email senders:** the same pattern. This is also needed before the pilot, because only the logging sender exists.

**Tranche 5: content written in code.**

- **Notification copy:** becomes versioned templates in Core, with both languages required and the SMS checks above.
- **Clinical word lists and safety phrases** (`Guardrails.CLINICAL`, `RuleRules`, `PhraseClassifier`): move into AI and Tracking tables, behind clinical review and the evaluation gate.
- **Evaluation sets:** move into AI's database, so clinicians can add cases.
- **Companion questions and closing lines:** these are content, edited by a Content editor in EN and FR.
- **Companion corpus:** needs its admin screen.

**Stays where it is:** section 5, and every "No" in sections 2 to 4.

- Contact-detail patterns stay as tested safety code.
- App and portal text is released with the apps.
- Prompt input layouts stay in code, because the output parsing depends on them.

Every new screen or screen change needs a design first: settings for Tracking and AI on screen 20, triggers, payment providers and routes, model routes, notification templates, word lists and the corpus.

### Decided (D61)

1. **Clinical settings:** settings stay Super admin only. Clinical reviewers do not change settings, so the cycle gap is a Super admin setting and the clinical word lists stay with clinical review through their own screens, not screen 20.
2. **Notification copy:** editable in the portal, in EN and FR, with the SMS rules checked on save. The database becomes the source; `notification-copy.md` is the first version.
3. **Member sign-in codes:** stay in the Keycloak authenticator's configuration. They are not settings in Solace.
4. **Second person:** not needed for payment providers and routes; a Super admin alone changes them. Refunds and payout runs keep their second approver (D14).
5. **Scheduled jobs:**
   - Every job has a row in its service's trigger table.
   - A job whose row is PAUSED is not registered when the service starts, so it does not run. Pausing or resuming takes effect at the next restart, with no code change.
   - Every job can be paused and its schedule changed, crisis escalation included. "Yes, with care" in section 6 marks the jobs that safety or money depend on.
