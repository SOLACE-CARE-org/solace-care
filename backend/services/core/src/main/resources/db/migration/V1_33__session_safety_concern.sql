-- A specialist raises a safety concern from a session (SpecCall.dc.html): a crisis alert with source
-- SESSION, carrying the specialist's short note as the text the responder reads.
ALTER TABLE crisis_alert ADD COLUMN concern_note TEXT CHECK (length(concern_note) <= 500);
ALTER TABLE crisis_alert DROP CONSTRAINT crisis_alert_source_check;
ALTER TABLE crisis_alert ADD CONSTRAINT crisis_alert_source_check
    CHECK (source IN ('REVIEW', 'HEALTH_CHECK', 'COMPANION', 'SESSION'));
