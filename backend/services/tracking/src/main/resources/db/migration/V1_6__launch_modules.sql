-- Phase 2, epic 10 (Tracking Service Spec, Modules and Launch rules; D54): the two launch modules,
-- PCOS & cycle v1 and Daily check-in v1, seeded as v1.0 drafts with the spec's bindings and rules.
-- They reach seekers only after the usual path: a card image and alt text are added in the
-- builder, a Clinical reviewer sets the bracketed thresholds and approves, then Operations
-- publishes. Every threshold below is the spec's bracketed placeholder, not clinical advice.

INSERT INTO module (id, code, accent_colour, created_by, created_at) VALUES
    ('0e1f6a10-0002-4000-8000-000000000001', 'pcos', 'track-pink', 'launch-seed', now()),
    ('0e1f6a10-0002-4000-8000-000000000002', 'daily_checkin', 'brand', 'launch-seed', now());

INSERT INTO module_version (id, module_id, major, minor, status, name_en, name_fr, description_en, description_fr,
                            audience_en, audience_fr, reminder_default, drafted_by, drafted_by_name, created_at) VALUES
    ('0e1f6a10-0003-4000-8000-000000000001', '0e1f6a10-0002-4000-8000-000000000001', 1, 0, 'DRAFT',
     'PCOS & cycle', 'SOPK et cycle',
     'Periods, flow, skin and hair', 'Règles, flux, peau et pilosité',
     'For anyone who wants to follow their periods and cycle', 'Pour toute personne qui veut suivre ses règles et son cycle',
     '20:00', 'launch-seed', 'Launch seed', now()),
    ('0e1f6a10-0003-4000-8000-000000000002', '0e1f6a10-0002-4000-8000-000000000002', 1, 0, 'DRAFT',
     'Daily check-in', 'Bilan du jour',
     'Mood, sleep and a private note', 'Humeur, sommeil et une note privée',
     'For anyone who wants to notice how their days go', 'Pour toute personne qui veut voir comment se passent ses journées',
     '20:00', 'launch-seed', 'Launch seed', now());

-- Elements from V1_1: mood 01, sleep_minutes 02, note 03, flow_intensity 04, period_start 05,
-- period_end 06, acne_severity 07, excess_hair 08.
INSERT INTO module_binding (module_version_id, element_id, required, position) VALUES
    ('0e1f6a10-0003-4000-8000-000000000001', '0e1f6a10-0001-4000-8000-000000000004', TRUE, 0),
    ('0e1f6a10-0003-4000-8000-000000000001', '0e1f6a10-0001-4000-8000-000000000005', TRUE, 1),
    ('0e1f6a10-0003-4000-8000-000000000001', '0e1f6a10-0001-4000-8000-000000000006', TRUE, 2),
    ('0e1f6a10-0003-4000-8000-000000000001', '0e1f6a10-0001-4000-8000-000000000007', FALSE, 3),
    ('0e1f6a10-0003-4000-8000-000000000001', '0e1f6a10-0001-4000-8000-000000000008', FALSE, 4),
    ('0e1f6a10-0003-4000-8000-000000000001', '0e1f6a10-0001-4000-8000-000000000003', FALSE, 5),
    ('0e1f6a10-0003-4000-8000-000000000002', '0e1f6a10-0001-4000-8000-000000000001', TRUE, 0),
    ('0e1f6a10-0003-4000-8000-000000000002', '0e1f6a10-0001-4000-8000-000000000002', TRUE, 1),
    ('0e1f6a10-0003-4000-8000-000000000002', '0e1f6a10-0001-4000-8000-000000000003', FALSE, 2);

INSERT INTO rule (id, module_version_id, code, conditions_json, unless_json, outcome, specialty_codes,
                  message_en, message_fr, cooldown_days, repeat_within_days, position) VALUES
    ('0e1f6a10-0004-4000-8000-000000000001', '0e1f6a10-0003-4000-8000-000000000001', 'pcos.long_cycles',
     '[{"signal":"cycle_length_days","operator":"consecutive_cycles","comparison":"gt","threshold":35,"count":3}]',
     '[{"field":"treatments","includes":"hormonal"}]',
     'INVITE', '{gynaecology,endocrinology}',
     'Each of your last {n} cycles ran longer than {threshold} days. Patterns like this are worth checking with a specialist. This is not a diagnosis.',
     'Chacun de vos {n} derniers cycles a duré plus de {threshold} jours. Ce type de schéma mérite d''être vérifié avec un spécialiste. Ce n''est pas un diagnostic.',
     30, NULL, 0),
    ('0e1f6a10-0004-4000-8000-000000000002', '0e1f6a10-0003-4000-8000-000000000001', 'pcos.long_periods',
     '[{"signal":"period_days","operator":"consecutive_cycles","comparison":"gte","threshold":8,"count":2}]',
     '[]',
     'INVITE', '{gynaecology}',
     'Your period lasted {threshold} days or more in each of your last {n} cycles. This is worth checking with a specialist. This is not a diagnosis.',
     'Vos règles ont duré {threshold} jours ou plus à chacun de vos {n} derniers cycles. Cela mérite d''être vérifié avec un spécialiste. Ce n''est pas un diagnostic.',
     30, NULL, 1),
    ('0e1f6a10-0004-4000-8000-000000000003', '0e1f6a10-0003-4000-8000-000000000001', 'pcos.no_period',
     '[{"signal":"daily_value","element":"flow_intensity","operator":"days_in_window","comparison":"in","threshold":["light","medium","heavy","very_heavy"],"mode":"at_most","count":0,"window":90,"minData":30}]',
     '[{"field":"trying_to_conceive","equals":true},{"field":"treatments","includes":"hormonal"}]',
     'INVITE', '{gynaecology}',
     'You logged no period flow in the last {window} days. This is worth checking with a specialist. This is not a diagnosis.',
     'Vous n''avez noté aucun flux de règles sur les {window} derniers jours. Cela mérite d''être vérifié avec un spécialiste. Ce n''est pas un diagnostic.',
     30, NULL, 2),
    ('0e1f6a10-0004-4000-8000-000000000004', '0e1f6a10-0003-4000-8000-000000000001', 'pcos.heavy_flow',
     '[{"signal":"very_heavy_days","operator":"consecutive_cycles","comparison":"gte","threshold":3,"count":2}]',
     '[]',
     'INVITE', '{gynaecology}',
     'You logged very heavy flow on {threshold} days or more in each of your last {n} cycles. This is worth checking with a specialist. This is not a diagnosis.',
     'Vous avez noté un flux très abondant {threshold} jours ou plus à chacun de vos {n} derniers cycles. Cela mérite d''être vérifié avec un spécialiste. Ce n''est pas un diagnostic.',
     30, NULL, 3),
    ('0e1f6a10-0004-4000-8000-000000000005', '0e1f6a10-0003-4000-8000-000000000002', 'checkin.low_mood',
     '[{"signal":"daily_value","element":"mood","operator":"days_in_window","comparison":"lte","threshold":2,"mode":"at_least","count":10,"window":14,"minData":10}]',
     '[]',
     'PATTERN', '{clinical-psychology,counselling}',
     'You logged a low mood on {n} of the last {window} days. Talking it through with someone can help.',
     'Vous avez noté une humeur basse {n} jours sur les {window} derniers. En parler avec quelqu''un peut aider.',
     30, 60, 0),
    ('0e1f6a10-0004-4000-8000-000000000006', '0e1f6a10-0003-4000-8000-000000000002', 'checkin.short_sleep',
     '[{"signal":"daily_value","element":"sleep_minutes","operator":"average_in_window","comparison":"lt","threshold":300,"window":14,"minData":10}]',
     '[]',
     'PATTERN', '{}',
     'Over the last {window} days you slept {average} minutes a night on average, less than {threshold}.',
     'Sur les {window} derniers jours, vous avez dormi en moyenne {average} minutes par nuit, moins que {threshold}.',
     14, NULL, 1);
