-- SMS and email senders as rows (D61), like payment providers: the adapter is code, each account
-- is a row, and keys stay in Vault, mounted as files and named by secret_ref. Messages go to the
-- ACTIVE sender of their channel with the lowest priority number, and to the next when it fails.
CREATE TABLE message_sender (
    code            TEXT PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]{1,30}$'),
    channel         TEXT        NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
    -- HTTP_SMS: any SMS gateway with an HTTP API; SMTP: any mail server; LOGGING: local runs only.
    adapter         TEXT        NOT NULL CHECK (adapter IN ('HTTP_SMS', 'SMTP', 'LOGGING')),
    display_name    TEXT        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
    status          TEXT        NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    priority        INTEGER     NOT NULL CHECK (priority BETWEEN 1 AND 10),
    secret_ref      TEXT        NOT NULL CHECK (secret_ref ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    -- The adapter's options, never a key: the gateway address and field names, or the mail host.
    settings        JSONB       NOT NULL DEFAULT '{}',
    version         INTEGER     NOT NULL CHECK (version >= 1),
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CHECK ((channel = 'SMS' AND adapter IN ('HTTP_SMS', 'LOGGING'))
        OR (channel = 'EMAIL' AND adapter IN ('SMTP', 'LOGGING')))
);

CREATE TABLE message_sender_version (
    code            TEXT        NOT NULL REFERENCES message_sender (code),
    version         INTEGER     NOT NULL,
    snapshot        JSONB       NOT NULL,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (code, version)
);
