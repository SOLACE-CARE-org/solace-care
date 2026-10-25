-- Notification copy edited on the portal (D61). A row exists only once staff change a message;
-- until then the wording in copy.properties and copy_fr.properties, word for word from
-- notification-copy.md, is in force. Both languages are always set together. Texts are stored as
-- people write them: an apostrophe is one character here, escaped only when the message is built.
CREATE TABLE notification_copy (
    key             TEXT PRIMARY KEY CHECK (key ~ '^[a-z][a-zA-Z0-9._-]*$'),
    text_en         TEXT        NOT NULL CHECK (length(text_en) BETWEEN 1 AND 1000),
    text_fr         TEXT        NOT NULL CHECK (length(text_fr) BETWEEN 1 AND 1000),
    version         INTEGER     NOT NULL CHECK (version >= 1),
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

-- Every version, with its reason: the copy's audit trail.
CREATE TABLE notification_copy_version (
    key             TEXT        NOT NULL,
    version         INTEGER     NOT NULL,
    text_en         TEXT        NOT NULL,
    text_fr         TEXT        NOT NULL,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (key, version)
);
