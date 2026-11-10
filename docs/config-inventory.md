# Configuration inventory

Every value that changes how Solace behaves, where it lives today, and how it changes. Gathered from the code on 21 October 2026, as the first step towards one way of setting values from the admin portal without a rebuild or restart. The strategy comes after this list.

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
| Tracking | `episode_model.gap_days` of `menstrual_cycle` (was `solace.tracking.cycle.new-period-gap-days`) | 10 | **Yes, Clinical reviewer** | Changes how cycles are counted. Part of the reviewed episode model since D61, not a config key. |
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
| Guardrail word lists | `Guardrails.CLINICAL` (AI) | Rebuild | **Yes, Clinical reviewer** | |
| Rule message word list | `RuleRules` (Tracking) | Rebuild | **Yes, Clinical reviewer** | |
| Contact-detail patterns | `Redactor` (AI), review contact check (Core) | Rebuild | No | Safety code, tested. |
| Safety monitor phrases | `PhraseClassifier` (AI) | Rebuild | **Yes, Clinical reviewer, with evaluation** | Every change must pass the 99% safety set. |
| Companion questions and closing lines | AI `messages.properties` | Rebuild | **Yes, Content editor** | EN and FR. |
| Notification copy | Core `notifications/copy*.properties` | Rebuild | **Yes, Content editor** | Must keep the SMS 160-character rule. |
| Error titles and brief or metric labels | `messages*.properties` in each service | Rebuild | Maybe | |
| App and portal text | `packages/i18n` | Rebuild of the apps | No | Released with the apps. |
| Evaluation sets | AI `resources/eval/*.json` | Rebuild | **Yes, Clinical reviewer** | |
| Companion corpus | AI `companion_passage` | Live in the database, no admin screen yet | **Yes, Clinical reviewer** | Needs a screen. |

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

Every job today is a Spring `@Scheduled` method: the schedule is in code or in `application.yml`, it always runs, and changing it needs a restart or a rebuild. The proposal is a **trigger table in each service's database**:

| Column | Meaning |
| --- | --- |
| name | Stable code, for example `core.bookings.expire-unpaid` |
| description | What the job does, in plain words (EN and FR for the portal) |
| status | `ACTIVE` or `PAUSED`; plus **locked**, meaning staff cannot pause it (safety, money or fixed decisions) |
| schedule | A cron expression with its time zone, or a fixed interval (`PT1M`) |
| last run, last outcome, next run | Shown in the portal so staff see that a job runs |

Each service reads its trigger rows and reschedules a job within a minute of a change, so no restart is needed. A locked trigger shows in the portal but cannot be paused, and its schedule can be changed only within safe limits.

| Service | Trigger name | What it does | Schedule today | Can be paused? |
| --- | --- | --- | --- | --- |
| Core | `core.crisis.escalate` | Escalates unacknowledged crisis alerts to the backup at 5 min and Super admins at 10 min | every 30 s | **No, locked** (D11) |
| Core | `core.bookings.expire-unpaid` | Expires bookings not paid within the 10-minute hold and frees the time | every 30 s (`solace.bookings.expiry-interval`) | **No, locked** |
| Core | `core.payments.poll` | Asks the mobile money provider about payments still pending | every 20 s (`solace.payments.poll-interval`) | **No, locked** |
| Core | `core.payments.draft-payout-runs` | Drafts the twice-monthly payout run when its period closes | hourly | Yes |
| Core | `core.payments.reconcile` | Compares yesterday's ledger with the provider's records | 03:00 daily (`solace.payments.reconciliation-cron`) | Yes |
| Core | `core.sessions.settle` | Marks no-shows and completes sessions the media server did not report | every minute | **No, locked** (money follows it) |
| Core | `core.sessions.dial-in-rules` | Opens and closes phone dial-in rules as joining opens and ends | every minute | Yes |
| Core | `core.messaging.reminders` | Day-before, joining-opens, 10-minutes-before and Brief ready pushes | every minute | **No, locked** (joining-opens is required) |
| Core | `core.messaging.deliver-waiting` | Sends pushes held back by quiet hours once they end | every minute | Yes |
| Core | `core.messaging.licence-renewals` | Reminds specialists 60 days before their licence ends | 09:00 daily | Yes |
| Core | `core.messaging.forget-old-messages` | Deletes delivered messages past their retention | 02:30 daily | Yes |
| Core | `core.privacy.prepare-exports` | Builds requested data exports | every 30 s | Yes |
| Core | `core.privacy.expire-exports` | Deletes export files past their lifetime | 02:15 daily | Yes |
| Core | `core.privacy.data-request-reminders` | Emails Operations about data requests due this week | 08:00 daily | Yes |
| Core | `core.identity.remove-unverified` | Removes sign-ups never verified within 24 h | every 15 min (`solace.identity.unverified-cleanup-interval`) | Yes |
| Core | `core.health-check.forget-unclaimed` | Deletes guest health check answers never claimed | 03:15 daily | Yes |
| Core | `core.insight.anomalies` | Checks for booking drops and failed-payment spikes for the copilot | hourly | Yes |
| Core | `core.admin.settings-reload` | Reloads platform settings and flags | every 30 s (`solace.admin.settings-refresh`) | **No, internal**: not listed in the portal |
| Core | `core.guests.purge-contacts` | Removes guest phone numbers `[12]` months after their last booking | 02:45 daily | Yes |
| AI | `ai.gateway.forget-texts` | Clears prompt and output texts older than `[30]` days | 02:30 daily | **No, locked** (data rule) |
| AI | `ai.scribe.sweep` | Deletes unsigned drafts and session audio after 7 days | hourly | **No, locked** (System Design, Recording) |
| AI | `ai.scribe.draft-waiting` | Transcribes and drafts notes for waiting session audio | every minute | Yes |
| Tracking | `tracking.nudges.reminders` | Daily tracking reminders and invite pushes | every minute (`solace.tracking.nudges.every`) | Yes |
| Tracking | `tracking.rules.nightly` | Runs the pattern check for everyone enrolled | 02:30 daily (`solace.tracking.pattern-check-cron`) | Yes |

## Summary

| | Live today | Needs a restart | Needs a rebuild |
| --- | --- | --- | --- |
| Settings | 22 Core settings + 13 flags | about 30 business values (AI models and routes, Tracking nudges, Core insight and session lengths, Keycloak codes) | about 25 business numbers, half of them fixed decisions to show only |
| Content | policies, catalogue, content, modules, health check, prompts, rota | none | guardrail and rule word lists, safety phrases, notification and companion copy, evaluation sets |

Main gaps: **Tracking and AI have no settings table**; **AI models are config-only**; **notification copy and the clinical word lists are in code**.
