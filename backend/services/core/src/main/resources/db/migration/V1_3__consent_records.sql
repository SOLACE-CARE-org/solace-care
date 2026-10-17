-- Versioned consent per purpose (System Design, Controls: Consent; data model consent_record).
-- Every choice is a new row; the current choice is the latest row per purpose. Rows are never
-- changed. They are removed only when the account is erased (epic 11), which deletes the account.
CREATE TABLE consent_record (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    purpose      TEXT        NOT NULL CHECK (purpose IN ('TRACKING', 'AI_ASSIST', 'SPECIALIST_SHARING', 'AI_SCRIBE', 'HEALTH_CHECK')),
    granted      BOOLEAN     NOT NULL,
    -- Version of the wording the person saw when choosing.
    version      TEXT        NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX consent_record_current_idx ON consent_record (account_id, purpose, recorded_at DESC, id DESC);

CREATE FUNCTION consent_record_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'consent_record rows are never changed';
END;
$$;

CREATE TRIGGER consent_record_no_update
    BEFORE UPDATE ON consent_record
    FOR EACH ROW EXECUTE FUNCTION consent_record_is_append_only();
