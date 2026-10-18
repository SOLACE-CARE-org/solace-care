-- Phase 2, epic 5 (Tracking Service Spec, How the pattern check runs; D51).

-- Profile conditions a rule's "unless" can read. They can only switch rules off.
CREATE TABLE tracking_profile (
    user_id            UUID PRIMARY KEY,
    pcos_status        TEXT CHECK (pcos_status IN ('DIAGNOSED', 'NOT_DIAGNOSED', 'NOT_SURE')),
    trying_to_conceive BOOLEAN,
    treatments         TEXT[] NOT NULL DEFAULT '{}',
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE recommendation (
    id               UUID PRIMARY KEY,
    user_id          UUID        NOT NULL,
    rule_id          UUID        NOT NULL REFERENCES rule (id),
    rule_code        TEXT        NOT NULL,
    module_id        UUID        NOT NULL REFERENCES module (id),
    -- The rule version's thresholds and the exact cycles or days used; never notes.
    evidence_json    JSONB       NOT NULL,
    outcome          TEXT        NOT NULL CHECK (outcome IN ('PATTERN', 'INVITE')),
    specialty_codes  TEXT[]      NOT NULL DEFAULT '{}',
    status           TEXT        NOT NULL CHECK (status IN ('NEW', 'SEEN', 'BOOKED', 'SNOOZED', 'DISMISSED', 'SUPERSEDED')),
    created_at       TIMESTAMPTZ NOT NULL,
    seen_at          TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    -- The cooldown: the same rule code cannot surface again for this person before this.
    next_eligible_at TIMESTAMPTZ NOT NULL,
    snoozed_until    TIMESTAMPTZ,
    booking_id       UUID
);
CREATE INDEX recommendation_user ON recommendation (user_id, created_at DESC);
-- One open recommendation per person and rule.
CREATE UNIQUE INDEX recommendation_open ON recommendation (user_id, rule_code)
    WHERE status IN ('NEW', 'SEEN', 'SNOOZED');

-- Every check whose conditions held, fired or blocked, and every withdrawal. Append-only.
CREATE TABLE rule_evaluation (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           UUID        NOT NULL,
    rule_id           UUID        NOT NULL,
    module_version_id UUID        NOT NULL,
    signals_json      JSONB       NOT NULL,
    result            TEXT        NOT NULL CHECK (result IN ('FIRED', 'BLOCKED_UNLESS', 'BLOCKED_COOLDOWN', 'BLOCKED_OPEN', 'WITHDRAWN')),
    recommendation_id UUID,
    evaluated_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX rule_evaluation_rule ON rule_evaluation (rule_id, evaluated_at DESC);

CREATE FUNCTION rule_evaluation_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    -- Erasure removes a person's rows; nothing else may change them.
    IF TG_OP = 'DELETE' AND current_setting('solace.erasure', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'rule_evaluation is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER rule_evaluation_append_only
    BEFORE UPDATE OR DELETE ON rule_evaluation
    FOR EACH ROW EXECUTE FUNCTION rule_evaluation_is_append_only();
