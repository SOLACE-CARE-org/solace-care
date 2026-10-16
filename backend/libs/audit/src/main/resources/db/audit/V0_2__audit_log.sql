-- Append-only audit log (System Design, Audit module; CLAUDE.md, Privacy and safety).
-- Every read of clinical data and every admin action writes one row. Rows are never changed
-- or removed: the triggers below refuse UPDATE, DELETE and TRUNCATE for every role.
-- Shipped by libs/audit and added to each service's Flyway locations automatically.

CREATE TABLE audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_type    TEXT        NOT NULL CHECK (actor_type IN ('SEEKER', 'SPECIALIST', 'GUEST', 'STAFF', 'SERVICE', 'SYSTEM')),
    -- Pseudonymous subject id of a person, or the client id of a service. Never a name or contact.
    actor_id      TEXT        NOT NULL,
    actor_roles   TEXT[]      NOT NULL DEFAULT '{}',
    -- What happened, dotted: tracking.entries.read, admin.refund.proposed.
    action        TEXT        NOT NULL,
    target_type   TEXT        NOT NULL,
    target_id     TEXT,
    -- Whose data was touched, pseudonymous. Null for actions on no one's data.
    subject_id    TEXT,
    outcome       TEXT        NOT NULL CHECK (outcome IN ('ALLOWED', 'DENIED')),
    -- Why: required for break-glass access, which is reviewed.
    reason        TEXT,
    trace_id      TEXT,
    -- Ids and field names only, never values typed by people.
    details       JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_log_subject_idx ON audit_log (subject_id, occurred_at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX audit_log_action_idx ON audit_log (action, occurred_at DESC);

CREATE FUNCTION audit_log_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER audit_log_no_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_is_append_only();
