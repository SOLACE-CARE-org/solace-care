# Solace — Tracking Service Spec

Oct 15, 2026 · @Banseka

Tracking is its own service, designed from scratch. Engineers ship the primitives in code: input views, rule operators and the episode algorithm. Admins compose everything else as reviewed data in the admin portal: elements, episode models, context questions, modules, rules and test histories (D61).

## Principles

Tracking is the patient's data substrate. It must be worth using on day two with nobody else looking, so it stays small. The limits below are part of the service, and admin-built modules cannot override them.

- **Small check-ins.** A module is a handful of elements; thirty seconds on 2G is the target. The builder warns above 6 elements.
- **No scores, no predictions, no diagnoses.** Rules state facts against thresholds ("3 cycles longer than 35 days"), never a severity, composite number or forecast.
- **Notes are storage.** Free text lives in `entry_note`, is never read by rules, never scanned, and is deleted for real when the patient deletes it. Only the AI layer's separate D1 opt-in can send notes anywhere.
- **No crisis detection.** The hard-coded, offline hotline link stays on every tracking surface. Nothing in tracking claims to detect an emergency.
- **Sharing is the patient's call.** Specialist access needs a live booking and an explicit, revocable, forward-only grant; notes need a separate `includes_notes` yes.
- **Append-only data.** Corrections tombstone and re-log. Days are local calendar days plus the time zone.
- **No guest tracking.** Tracking needs a durable account.

## Domain model

Each part has exactly one owner. Engineers ship primitives in code (input views, operators, the episode algorithm). Admins define elements, episode models and context questions, and arrange them into modules and rules; clinical review approves each before it is used. Patients create observations and context answers. Observations point at elements, so admins can change modules without touching anyone's history.

| Owner | Definitions (data) | Primitives (code) |
| --- | --- | --- |
| Engineers | | input views, operators and comparisons, the episode algorithm, the guards |
| Admins, approved by clinical review | elements, signals, episode models, context questions, modules, rules, test histories, the wording list, Tracking settings | |
| Patients | observations, notes, context answers, enrolments | |

> Diagram: Tracking domain model · 3 owners, 6 entities (in the live doc, linked from START_HERE.md)

Rules read only the signals of elements their module binds, and a firing becomes a recommendation the patient controls.

## Elements

An element is one thing a patient can log. Admins create elements in the admin portal from the input views the app already ships, and a clinical reviewer approves each one before any module can bind it. A new element needs no migration and no app release. Only a new input view does, because a view is app code.

Elements have the same lifecycle as modules: draft, in review, published, retired. A published version is frozen. The launch catalogue is seeded by migration as published v1 elements, so launch does not wait for the builder.

### What an element carries

| Field | Purpose |
| --- | --- |
| `code`, `version` | Stable identity, e.g. `flow_intensity` v1. Everything starts at v1. Adding a value is a minor version (v1.1); changing or removing values is a major version (v2). Old versions stay readable. |
| `kind` | `DAY_STATE` (at most one live value per user, element and local day; a newer push supersedes) or `EVENT` (any number per day). |
| `value_type` + constraints | `INT` with range, `MINUTES`, `ENUM` with allowed values, `BOOL`, `DATE`, or `TEXT` (notes only). Validated on push. |
| `input_view` + `view_props` | Key of the mobile component that logs it, and the settings that view takes (catalogue below). The view must accept the element's value type. |
| `icon` | Key into the app's own icon set, picked from that set in the builder. Drawn in the module's colour and works offline. |
| `signals` | Derived, not typed in: every element except a note gives a daily signal, and episode models that read it add theirs (below). |
| `labels` | EN/FR name, help text, and a label per enum value. A build check fails if EN and FR keys differ. |
| `min_app_version` | Taken from the input view registry: the oldest app build that ships the view. Older apps hide modules that use it. |
| `status`, review | DRAFT, IN\_REVIEW, PUBLISHED, RETIRED, with the drafter, the reviewer and their comments. A reviewer who drafted an element cannot approve it. |

### Input views

| `input_view` | Logs | Used by |
| --- | --- | --- |
| `scale_5` | Five labelled buttons, one tap | Mood |
| `duration_stepper` | Hours and minutes in 15-minute steps | Sleep |
| `choice_chips` | One value from a short enum | Acne, stress |
| `toggle` | Yes or no | Excess hair growth |
| `flow_calendar` | Day-by-day flow on a month view | Flow intensity |
| `event_marker` | One tap that records a dated event | Period start, period end |
| `free_text` | A private note | Note |

Engineers register each view in the `input_view` table with the value types it accepts, the props it takes (for example `values` for chips, `min`, `max`, `step` and `unit` for a stepper) and the app version that first ships it. The builder offers only registered views, and only those that accept the element's value type.

A new view is mobile work plus a migration that registers it; it is the only reason tracking ever needs a release to grow. New elements built from existing views, such as headache, energy or bloating, need nothing from engineering.

### Signals

Rules never read raw rows. Signals are definitions in a `signal_definition` table, and the rule builder offers only the signals of a module's elements and episode models.

- **Daily signals** (`daily:<element>`): the element's value per local day, created for every element except notes, including elements that also feed an episode model. Rules use them over a window: average, or the number of days meeting a condition.
- **Episode signals** (`<model>.<metric>`): computed per complete episode by an episode model (below), for example `menstrual_cycle.length_days`. The newest, incomplete episode never counts.
- **No signals:** notes.

The admin builder and the rule validator read the same list from one endpoint, so neither keeps its own copy.

### Episode models

Some things are tracked as episodes rather than days: a cycle, a migraine attack, a skin flare-up. An episode model says how episodes are found in what a patient logged and what is measured for each one. The algorithm is code; each model is a reviewed definition that admins build.

| Field | Purpose |
| --- | --- |
| `code`, `version` | e.g. `menstrual_cycle` v1. Versioned and reviewed like elements |
| Start element | An `EVENT` element that marks a start, e.g. `period_start` |
| End element | Optional `EVENT` element that marks an end, e.g. `period_end` |
| Activity element | Optional `DAY_STATE` element whose days count as active, e.g. `flow_intensity` |
| Active values | Which values of the activity element count as active, e.g. light, medium, heavy, very heavy |
| Gap days | Days without activity after which the next active day starts a new episode |
| Span | `UNTIL_NEXT_START` (an episode lasts until the next one starts, as cycles do) or `UNTIL_END` (it lasts until its end, as an attack does) |
| Metrics | Which numbers each complete episode produces, from a fixed list: `length_days`, `active_days`, `days_with_value` (of one named value) |
| Labels | EN/FR name of the model and of each metric, for the builder and the Trends screen |

The rules the algorithm follows are fixed: a logged start wins over an inferred one within the gap; two starts within the gap are one episode; the active run ends on a logged end, otherwise on the last active day, and one unlogged day inside the run does not end it; a day with nothing logged is unknown, never inactive; the newest episode is never complete and never counts.

After every push or delete that touches a model's elements, the episode reconciler rebuilds that patient's episodes for that model, in the same transaction and before the pattern check. Episodes are derived data, so erasing or editing observations rebuilds them. A module that binds a model's elements offers that model's signals.

The launch model is `menstrual_cycle` v1: start `period_start`, end `period_end`, activity `flow_intensity` with every value except none, gap \[10\] days, span until the next start, metrics `length_days`, `active_days` (period days) and `days_with_value` very\_heavy (very heavy days). It counts cycles exactly as the launch cycle model did.

### Launch catalogue

| Element | Kind | Value | View | Icon | Signals | Launch module |
| --- | --- | --- | --- | --- | --- | --- |
| `mood` v1 | Day state | 1–5 | `scale_5` | mood | daily value | Daily check-in |
| `sleep_minutes` v1 | Day state | 0–1440 min | `duration_stepper` | moon | daily value | Daily check-in |
| `note` v1 | Day state | Text in `entry_note` | `free_text` | pen | none | Both |
| `flow_intensity` v1 | Day state | none, light, medium, heavy, very\_heavy | `flow_calendar` | droplet | daily value; feeds `menstrual_cycle` (cycle length, period days, very heavy days) | PCOS & cycle |
| `period_start` v1 | Event | Date | `event_marker` | calendar-start | feeds `menstrual_cycle` | PCOS & cycle |
| `period_end` v1 | Event | Date | `event_marker` | calendar-end | feeds `menstrual_cycle` | PCOS & cycle |
| `acne_severity` v1 | Day state | none, mild, moderate, severe | `choice_chips` | skin | daily value | PCOS & cycle |
| `excess_hair` v1 | Day state | yes / no | `toggle` | hair | daily value | PCOS & cycle |
| `stress_level` v1 | Day state | low, some, high | `choice_chips` | bolt | daily value | None; for admin-built modules |

All nine ship in the first mobile release, seeded by migration as published elements. Each needs a clinician's wording review before its migration is merged. Elements added after launch go through the builder and clinical review instead.

## Modules

A module is a set of elements a patient chooses to track together, with the rules that watch them. Admins create modules entirely in the admin portal: no migration and no app release, as long as every element they pick is published and its view is supported by the app.

### What a module carries

| Field | Notes |
| --- | --- |
| Name, short description | EN and FR, both required to publish |
| Card image | Required. 3:2, at least 1200×800 px, JPG, PNG or WebP, at most 1 MB, uploaded to object storage and served in phone-sized renditions |
| Image alt text | EN and FR, required |
| Accent colour | Picked from the Solace palette, so every card passes contrast checks; element icons are drawn in it |
| Elements | Picked from the published library: element version, required or optional, display order, and an optional group with an EN/FR label (e.g. period start and end shown together as "Period dates"). 1–6 elements; the builder warns above 6 |
| Eligibility | Optional minimum age, never below 18, read from the Core profile. No sex or gender filter: seekers choose modules themselves |
| Who it's for | EN and FR, required. One line on the card, e.g. "For anyone who has periods" |
| Enrolment questions | Optional, at most 3 context questions (below) asked once when the seeker enrols, e.g. "Are you trying to conceive?" Answers stay in Tracking and can only switch rules off |
| Rules | Zero or more, defined per module version (see Rules) |
| Reminder default | Suggested daily reminder time, which the patient can change |
| Presentation | Optional EN/FR title and save-button wording for the logging screen, an episode model whose current day shows in the header ("day 12 of your cycle"), and which signals the Trends screen charts. The app reads these instead of checking module or element codes |

### Context questions

A context question is something about the patient that can switch a rule off, such as "Are you trying to conceive?" or "Are you on hormonal treatment?". Admins write them in the builder and a clinical reviewer approves them, like elements.

| Field | Purpose |
| --- | --- |
| `code`, `version` | e.g. `trying_to_conceive` v1 |
| Type | Yes/no, one choice, or several choices, with EN/FR wording for the question and each option |
| Scope | `PROFILE` (asked on the app's "About you for tracking" screen and editable there) or `ENROLMENT` (asked when enrolling in a module that lists it) |

Answers are stored per patient and question in Tracking, are never sent anywhere, and are erased with everything else. A patient can skip any question; an unanswered question never switches a rule off. Context questions replace the fixed profile fields of the first design (`pcos_status`, `trying_to_conceive`, `treatments`) and the single enrolment question.

### Versions and lifecycle

Every module starts at v1. Changing rules, thresholds, wording, the image, presentation, enrolment questions or optional elements makes a minor version (v1.1); adding or removing a required element makes a major version (v2). Only a draft can change; a published version is frozen.

> Diagram: Module version lifecycle · 4 states, 1 review gate (in the live doc, linked from START_HERE.md)

- **Draft:** an admin edits details, image, elements and rules, and previews it as a phone.
- **In review:** a user with the clinical reviewer role checks wording, elements and every threshold, then approves or sends it back with comments.
- **Published:** visible in the catalogue. Publishing a new version moves enrolled patients to it for new logs and retires the old one. After a major version, the app shows them what changed the next time they log.
- **Retired:** hidden from the catalogue; enrolled patients can no longer log, but keep and can export their history.

Observations belong to elements, not to modules. Changing or retiring a module never deletes or rewrites anything a patient logged, and two modules that share an element (say mood) share its history.

Two modules launch at v1. Admins build them in the portal and clinical review approves them, like any other module.

| Module | Required elements | Optional elements | Rules |
| --- | --- | --- | --- |
| PCOS & cycle v1 | `flow_intensity`, `period_start`, `period_end` | `acne_severity`, `excess_hair`, `note` | `pcos.long_cycles`, `pcos.long_periods`, `pcos.no_period`, `pcos.heavy_flow` |
| Daily check-in v1 | `mood`, `sleep_minutes` | `note` | `checkin.low_mood`, `checkin.short_sleep` |

Stress and Men's health, shown in the app designs, are examples of modules admins can add after launch.

## Rules and thresholds

Admins set rules and thresholds per module version in the builder, from a fixed form. A rule can show a patient their own pattern or invite them to talk to a type of specialist. It never notifies anyone else, never reads notes and never produces a score.

### What a rule is made of

| Part | Choices |
| --- | --- |
| Conditions | 1–3, all must hold. Each is one signal, one operator, one comparison and a threshold |
| Signal | Only signals offered by this module's elements and episode models |
| Operator | `consecutive_episodes`: the last N complete episodes each meet the comparison (episode signals only). `days_in_window`: at least N, or at most N, of the last W days meet it. `average_in_window`: the average over the last W days meets it (daily signals only). Operators are a fixed list in code; each declares which signal kinds it accepts and which form fields the builder shows |
| Comparison | greater than, at least, less than, at most, equal to, one of |
| Minimum data | Logged days required in the window (window operators); episodes never count until complete |
| Unless | Optional answers to published context questions that switch the rule off, e.g. `trying_to_conceive` is yes. They can never switch a rule on, and the builder lets admins pick them |
| Outcome | `PATTERN` (a card on the patient's Track tab) or `INVITE` (the card plus an invitation to a specialty from Core's taxonomy, with matched specialists in the patient's session language) |
| Message | EN and FR templates filled from the evidence, e.g. "Your last {n} cycles were longer than {threshold} days." `{n}`, `{threshold}` and the like come from the first condition; `{c2.n}`, `{c2.threshold}` and so on from later ones. A word list blocks diagnostic wording; the reviewer checks the rest. The word list is data that clinical reviewers maintain |
| Cooldown | Days before the same rule can surface again for that patient after it fires, is dismissed or is snoozed. Optional repeat window for PATTERN rules: a second firing within N days of the first becomes an INVITE |

There is no OR and no free-form expression: two rules express an either-or. That keeps every rule readable by a clinician and every evaluation cheap. Adding an operator is a code change with its own tests and review, never something the builder can do.

### How the pattern check runs

The pattern check runs inside the sync transaction and reads every threshold from rule rows, never from code. We don't use the word "triage": in mental health it means judging urgency, which Tracking never does.

1. **When:** after every sync push or delete, in the same transaction, right after the episode reconciler. Also nightly, so window rules stay correct as old days fall out of the window.
2. **Which rules:** only rules of the patient's current module versions, and only for modules whose elements the push changed. A mood log does not re-check cycle rules.
3. **Signals:** computed from observations and episodes. A day with nothing logged is unknown, never `none`. The newest, incomplete episode never counts.
4. **Conditions:** each is checked with its operator, threshold and minimum data. All must hold.
5. **Guards, in order:** "unless" context answers, then cooldown, then one open recommendation per rule.
6. **Outcome:** `PATTERN` writes a card and sends no push. `INVITE` writes a `recommendation` with the rule version, thresholds and exact episodes or days used, then emits `tracking.recommendation.created`. A repeat firing inside a rule's repeat window is raised to `INVITE`.
7. **Withdrawal:** if an edit or delete means an open recommendation's rule no longer holds, it becomes `SUPERSEDED`. Unseen, it disappears quietly; seen, it stays with a line saying the data has changed.
8. **Log:** every check whose conditions held, fired or blocked by a guard, and every withdrawal writes a `rule_evaluation` row. Clinical review reads it, and the rows become test vectors for the evaluator.

### How a patient sees it

The card appears on the Track tab in the app language, with the evidence drawn as a small chart. An invite shows matched specialists; the patient can book, snooze or dismiss. A pattern sends no push. An invite uses that day's one push from the nudge budget, in place of the logging reminder: push only, never SMS, neutral wording.

### Testing before publishing

The builder runs each rule against a library of synthetic patient histories and shows which ones fire and why. Real patient data is never used to test a draft.

Histories are data, not code. Each is a short recipe: which elements, a pattern (a constant value, alternating values, cycles or episodes of given lengths, sparse logging), how many days back from today, and the context answers. The launch histories are seeded; reviewers add more next to the rules they review. A version can be submitted only when each rule has at least one history that fires it and one that does not.

### Launch rules (thresholds to be set by clinicians)

| Rule | Conditions | Unless | Outcome | Cooldown |
| --- | --- | --- | --- | --- |
| `pcos.long_cycles` | cycle length greater than \[35\] days for \[3\] consecutive cycles | on hormonal treatment | Invite: gynaecology or endocrinology | \[30\] days |
| `pcos.long_periods` | period days at least \[8\] for \[2\] consecutive cycles | — | Invite: gynaecology | \[30\] days |
| `pcos.no_period` | flow above none on at most \[0\] of the last \[90\] days, \[30\] days logged | trying to conceive, or on hormonal treatment | Invite: gynaecology | \[30\] days |
| `pcos.heavy_flow` | very heavy days at least \[3\] for \[2\] consecutive cycles | — | Invite: gynaecology | \[30\] days |
| `checkin.low_mood` | mood at most \[2\] on at least \[10\] of the last \[14\] days, \[10\] days logged | — | Pattern; invite to a psychologist or counsellor if it fires again within \[60\] days | \[30\] days |
| `checkin.short_sleep` | average sleep less than \[300\] minutes over \[14\] days, \[10\] days logged | — | Pattern only | \[14\] days |

Numbers in brackets are placeholders, not clinical advice. Cycle conditions read `menstrual_cycle` signals. The two "unless" conditions read the launch context questions `trying_to_conceive` (yes/no) and `hormonal_treatment` (yes/no), both scoped to the profile and asked on enrolment in PCOS & cycle.

## Service boundary

Tracking runs as its own Spring Boot service with its own PostgreSQL database. It knows users only by the id in their token and reaches the rest of Solace through the ports below.

### Ports to the rest of Solace

| Concern | How Tracking handles it |
| --- | --- |
| Identity | User id from the JWT, no foreign keys into Core's database. Tracking stores no names, phones or emails |
| Specialist reads | gRPC call to Core on every read to confirm a live booking, never cached, 2-second deadline; any error or timeout denies access |
| Nudges | Tracking decides and enforces the budget, then publishes `tracking.nudge.requested`; Core sends the push |
| Account deletion | Core publishes `core.user.erased`; Tracking deletes every row and note for that user and replies `tracking.user.erased` |
| Specialty taxonomy and age | gRPC to Core: taxonomy cached for 1 hour, age read at enrolment |
| Modules | Created and published by admins through the admin API |

### APIs

Core tracking APIs, behind the gateway under `/v1/tracking`: sync push and pull, own observations and delete, cycle calendar and summary, profile, consent grant, list and revoke, specialist read, nudges.

Module and rule APIs:

- **Patients:** element library and module catalogue, enrol and leave a module, context questions and answers, recommendations list, snooze and dismiss.
- **Admins:** input view registry (read-only); element, episode model and context question drafts, versions, review, publish and retire; the rule vocabulary for a module (its signals, the operators and comparisons, the context questions "unless" may use); module drafts, versions, image upload (presigned URL), rules, synthetic histories, rule test runs, submit, approve or reject, publish, retire; the wording list and Tracking settings.

### Events

| Event | Direction | Purpose |
| --- | --- | --- |
| `tracking.recommendation.created` / `resolved` | Tracking → Core, AI | Core matches specialists and shows the invite; AI may phrase the evidence, never from notes |
| `tracking.module.published` | Tracking → Core | Refresh the catalogue cache and admin overview |
| `tracking.element.published` | Tracking → Core | Refresh the admin overview; carries the code and version only |
| `tracking.nudge.requested` | Tracking → Core | Send one push |
| `tracking.user.erased` | Tracking → Core | Confirm erasure for the audit log |
| `core.user.erased` | Core → Tracking | Delete everything for the user |

Tracking publishes nothing about individual observations. In particular there is no event that hands notes or check-ins to the AI service.

## Data model

Every table is new. User ids come from Core's tokens; no table has a foreign key outside Tracking.

| Table | Key columns | Written by |
| --- | --- | --- |
| `input_view` | key, value\_types, props\_schema\_json, min\_app\_version | Migrations only |
| `element` | id, code, version, status, kind, value\_type, constraints\_json, input\_view, view\_props\_json, icon, labels\_json, drafted\_by, approved\_by, published\_at | Admin API; launch elements by migration |
| `signal_definition` | code, kind (DAILY, EPISODE), element\_id or episode\_model\_id, metric, value\_type, labels\_json | Derived when an element or episode model is published |
| `episode_model` | id, code, version, status, start\_element, end\_element, activity\_element, active\_values, gap\_days, span, metrics\_json, labels\_json, drafted\_by, approved\_by | Admin API; launch model by migration |
| `episode` | user\_id, model\_code, start\_date, end\_date, active\_end\_date, complete, metrics\_json | Episode reconciler; derived |
| `context_question` | id, code, version, status, type, scope, labels\_json, options\_json, drafted\_by, approved\_by | Admin API |
| `context_answer` | user\_id, question\_code, value\_json, answered\_at | Patients |
| `module_question` | module\_version\_id, question\_code, position | Admin API |
| `synthetic_history` | id, code, labels\_json (EN/FR name), recipe\_json, context\_json, seeded, created\_by, created\_at | Seeded; Admin API (add only, never edited) |
| `wording_rule` | id, language, pattern, kind (BLOCK, ALLOW) | Clinical reviewers |
| `tracking_setting` | key, value, updated\_by, updated\_at | Admin API |
| `module` | id, code, accent\_colour, created\_by | Admin API |
| `module_version` | id, module\_id, number, status (DRAFT, IN\_REVIEW, PUBLISHED, RETIRED), name\_en/fr, description\_en/fr, image\_asset\_id, image\_alt\_en/fr, min\_age, audience\_en/fr, presentation\_json, reminder\_default, submitted\_by, approved\_by, published\_at | Admin API |
| `module_binding` | module\_version\_id, element\_id, required, position, group\_code, group\_label\_json | Admin API |
| `rule` | id, module\_version\_id, code, conditions\_json, unless\_json, outcome, specialty\_codes, message\_en/fr, cooldown\_days, repeat\_within\_days | Admin API |
| `rule_review` | module\_version\_id, reviewer\_id, decision, comments, decided\_at | Clinical reviewers |
| `enrolment` | user\_id, module\_id, module\_version\_id, enrolled\_at, left\_at, reminder\_time | Patients |
| `recommendation` | id, user\_id, rule\_id, evidence\_json, outcome, status (NEW, SEEN, BOOKED, SNOOZED, DISMISSED, SUPERSEDED), next\_eligible\_at, booking\_id | Rule evaluator, patient |
| `rule_evaluation` | id, user\_id, rule\_id, module\_version\_id, signals\_json, result (FIRED, BLOCKED\_UNLESS, BLOCKED\_COOLDOWN, BLOCKED\_OPEN, WITHDRAWN), recommendation\_id, evaluated\_at | Rule evaluator; append-only |
| `observation` | id, user\_id, element\_id, observed\_on, recorded\_tz, value, day\_state, device\_id, recorded\_at, deleted\_at | Patients via sync |
| `entry_note` | observation\_id, body | Patients; hard-deleted on delete |
| `consent`, `nudge`, `timeline_view` | as defined in the tracking design document | Patients; Tracking for nudges |

`observation` points at an element, never at a module, which is what lets admins change modules freely. `recommendation.next_eligible_at` carries the cooldown, so it survives a move to a new module version because the rule keeps its `code`.

## Admin builder and mobile rendering

### Library builder in the admin portal

Elements, episode models and context questions each have a list, a draft form, a phone preview in both languages and the same review path as modules: a clinical reviewer who did not draft it approves or returns it with comments, then Operations publishes it. Only published ones can be bound into a module. Every step is audited.

- **Element:** code, kind, value type and constraints, input view (only views that accept the value type) and its props, icon from the app's set, EN/FR name, help and value labels.
- **Episode model:** start, end and activity elements from the published library, active values, gap days, span, metrics, EN/FR labels; a test run against the synthetic histories shows the episodes it finds.
- **Context question:** type, scope, EN/FR question and options.

### Module builder in the admin portal

1. **Details:** EN/FR name and description, accent colour, card image with crop to 3:2, alt text, who it's for, minimum age, up to 3 enrolment questions, reminder default, presentation.
2. **Elements:** pick from the published library, shown with each element's icon and a preview of its logging view; set required or optional, group, and drag to order. Elements whose view is newer than the oldest supported app are flagged.
3. **Rules:** add rules from the form, which offers only this module's signals and the published context questions for "unless"; each shows its message preview in EN and FR and a test run against the synthetic histories.
4. **Preview:** the catalogue card and the logging screen exactly as a phone draws them, in both languages.
5. **Submit for review:** a clinical reviewer approves or returns it with comments; an approved version can be published, and later retired.

Every step is audited: who changed what, who approved, who published.

### On the phone

- **Catalogue:** published modules the patient is eligible for, as cards: image, name, short description, who it's for, and the icons of the elements it tracks. Enrolled modules show "Tracking".
- **Logging screen:** built from the module's bindings in order, one row per element using its icon, input view and view props, with grouped elements under their group label. Title, button wording and the episode-day header come from the module's presentation. The app never checks module or element codes. Elements shared by several enrolled modules (for example mood) are asked once a day, not once per module.
- **Unsupported elements:** if the app is older than an element's `min_app_version`, the module is hidden and the app asks for an update instead of showing half a screen.
- **Offline:** the catalogue, element library, bindings, context questions and labels are cached with the app's sync, so logging works without a connection.
- **About you for tracking:** one screen that renders the published profile-scoped context questions; every answer can be skipped or changed.

## Open questions

- [x] **Launch module bindings:** settled. PCOS & cycle v1 and Daily check-in v1 are defined under Modules.
- [x] **Daily check-in codes:** settled: mood, sleep\_minutes and note.
- [x] **Pattern check:** settled. Described under Rules and thresholds.
- [ ] **Launch thresholds:** clinicians to set every bracketed number in the launch rules.
- [x] **Eligibility:** settled. Solace never collects sex at birth. Seekers choose modules, each card says who it is for, and a module may ask one enrolment question.
- [ ] **Icon set:** which icon library the mobile app will use, so element `icon` keys can be fixed and the builder can offer them.
- [x] **Who defines elements:** settled (D61). Admins define elements from registered input views, with clinical review; only a new view needs a release.
- [ ] **Generalised episodes and context questions (D61):** adopted with the recommended defaults while the owner confirms.
- [x] **Invite pushes:** settled. An invite uses that day's one push in place of the logging reminder; a pattern sends none.
