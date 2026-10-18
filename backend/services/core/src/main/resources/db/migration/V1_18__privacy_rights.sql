-- Data subject rights and retention (Phase 1, epic 11; D40).

-- An erased account stays as a tombstone with no contact, so bookings and the ledger keep their
-- accounting record.
ALTER TABLE user_account DROP CONSTRAINT user_account_status_check;
ALTER TABLE user_account ADD CONSTRAINT user_account_status_check
    CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'ERASED'));
ALTER TABLE user_account DROP CONSTRAINT user_account_check;
ALTER TABLE user_account ADD CONSTRAINT user_account_contact_check
    CHECK (status = 'ERASED' OR phone IS NOT NULL OR email IS NOT NULL);
ALTER TABLE user_account ADD COLUMN erased_at TIMESTAMPTZ;

-- Payer numbers go with erasure; guest numbers with retention.
ALTER TABLE payment ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE guest_contact ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE guest_contact ADD COLUMN purged_at TIMESTAMPTZ;

-- Export and erasure requests, with their deadline.
CREATE TABLE data_request (
    id           UUID        PRIMARY KEY,
    account_id   UUID        NOT NULL,
    kind         TEXT        NOT NULL CHECK (kind IN ('EXPORT', 'ERASURE')),
    status       TEXT        NOT NULL CHECK (status IN ('PREPARING', 'READY', 'EXPIRED', 'REQUESTED', 'DONE')),
    requested_at TIMESTAMPTZ NOT NULL,
    due_at       TIMESTAMPTZ NOT NULL,
    ready_at     TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    file_key     TEXT,
    completed_at TIMESTAMPTZ
);
CREATE INDEX data_request_account ON data_request (account_id, requested_at DESC);
CREATE INDEX data_request_open ON data_request (status) WHERE status IN ('PREPARING', 'REQUESTED', 'READY');
