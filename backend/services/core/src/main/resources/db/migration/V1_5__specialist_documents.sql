-- Credential documents (SpecDocuments). The files live in object storage (bucket
-- solace-credentials, encrypted at rest); this table records what each specialist uploaded.
-- Only the verification team opens them, and every opening is audited.
CREATE TABLE specialist_document (
    id            UUID PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    kind          TEXT        NOT NULL CHECK (kind IN ('LICENCE', 'IDENTITY', 'PHOTO', 'CV')),
    object_key    TEXT        NOT NULL UNIQUE,
    -- As the specialist named it, shown back to them; never used as a path.
    file_name     TEXT        NOT NULL CHECK (length(file_name) BETWEEN 1 AND 200),
    content_type  TEXT        NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes    BIGINT      NOT NULL CHECK (size_bytes > 0),
    uploaded_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX specialist_document_account_idx ON specialist_document (account_id, kind);
