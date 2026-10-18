-- Platform settings, feature flags and the staff directory (admin screens 19 and 20; D42).

-- System Design, Core data model: platform_setting (key, value_json, version, updated_by). A row
-- exists only once staff change a setting; until then the configured default is in force.
CREATE TABLE platform_setting (
    key             TEXT PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9.-]*$'),
    value           TEXT        NOT NULL,
    version         INTEGER     NOT NULL CHECK (version >= 1),
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

-- Every version, kept: the settings audit trail (System Design, Admin & config).
CREATE TABLE platform_setting_version (
    key             TEXT        NOT NULL,
    version         INTEGER     NOT NULL,
    value           TEXT        NOT NULL,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (key, version)
);

CREATE TABLE feature_flag (
    key             TEXT PRIMARY KEY CHECK (key ~ '^[a-zA-Z][a-zA-Z0-9]*$'),
    enabled         BOOLEAN     NOT NULL,
    version         INTEGER     NOT NULL CHECK (version >= 1),
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE feature_flag_version (
    key             TEXT        NOT NULL,
    version         INTEGER     NOT NULL,
    enabled         BOOLEAN     NOT NULL,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (key, version)
);

-- Staff as Core knows them: names for activity and audit screens, and when their sessions were
-- ended (role change, deactivation, new password), so older tokens stop working at once. Keycloak
-- stays the identity provider (D23); passwords and second factors live only there.
CREATE TABLE staff_member (
    id               TEXT PRIMARY KEY,
    name             TEXT        NOT NULL,
    email            TEXT,
    roles            TEXT[]      NOT NULL DEFAULT '{}',
    status           TEXT        NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DEACTIVATED')),
    added_at         TIMESTAMPTZ NOT NULL,
    added_by         TEXT,
    last_active_at   TIMESTAMPTZ,
    sessions_ended_at TIMESTAMPTZ
);
