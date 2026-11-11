-- Phase 2b, step 4 (D61; Tracking Service Spec, Modules): how the app shows a module, so the app
-- stops checking module and element codes. A version's presentation sets the logging title and
-- save button, the episode model whose day shows in the header, what Trends charts, and the element
-- offered as one tap on Home. Bindings can be grouped under one EN/FR label.

ALTER TABLE module_version ADD COLUMN presentation_json JSONB;

ALTER TABLE module_binding ADD COLUMN group_code TEXT CHECK (group_code ~ '^[a-z][a-z0-9_]*$');
ALTER TABLE module_binding ADD COLUMN group_label_json JSONB;
ALTER TABLE module_binding ADD CONSTRAINT module_binding_group_label
    CHECK ((group_code IS NULL) = (group_label_json IS NULL));

-- The launch modules keep exactly what the app showed for them by code until now: the check-in's
-- own title, button and one-tap mood on Home; the cycle day and past cycles for PCOS & cycle, with
-- period start and end together as "Period dates". Every version of each gets it, published ones
-- too, because it describes what the app already did rather than a change.
UPDATE module_version v SET presentation_json = '{
    "title": {"en": "How was today?", "fr": "Comment s''est passée la journée ?"},
    "saveLabel": {"en": "Save check-in", "fr": "Enregistrer le bilan"},
    "trends": ["daily_value:mood", "daily_value:sleep_minutes"],
    "quickLog": "mood"
  }'::jsonb
  FROM module m
 WHERE m.id = v.module_id AND m.code = 'daily_checkin' AND v.presentation_json IS NULL;

UPDATE module_version v SET presentation_json = '{
    "episode": {
      "model": "menstrual_cycle",
      "dayLabel": {"en": "cycle day {{n}}", "fr": "jour {{n}} du cycle"},
      "historyTitle": {"en": "Your last cycles", "fr": "Vos derniers cycles"}
    },
    "trends": ["menstrual_cycle.length_days", "daily_value:acne_severity", "daily_value:excess_hair"]
  }'::jsonb
  FROM module m
 WHERE m.id = v.module_id AND m.code = 'pcos' AND v.presentation_json IS NULL;

UPDATE module_binding b
   SET group_code = 'period_dates',
       group_label_json = '{"en": "Period dates", "fr": "Dates des règles"}'::jsonb
  FROM element e
 WHERE e.id = b.element_id AND e.code IN ('period_start', 'period_end')
   AND b.group_code IS NULL;
