-- Member accounts (System Design, Core data model: user_account). Keycloak holds the sign-in;
-- Core holds the person. The account id is the pseudonymous subjectId used in every event and in
-- Tracking and AI; it is also the Keycloak username, so tokens never carry a phone number.
CREATE TABLE user_account (
    id                      UUID PRIMARY KEY,
    keycloak_id             TEXT UNIQUE,
    type                    TEXT        NOT NULL CHECK (type IN ('SEEKER', 'SPECIALIST')),
    status                  TEXT        NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    -- E.164; at most one account per number.
    phone                   TEXT UNIQUE CHECK (phone ~ '^[+][1-9][0-9]{7,14}$'),
    -- Stored lower-case; at most one account per address.
    email                   TEXT UNIQUE CHECK (email = lower(email)),
    preferred_locale        TEXT        NOT NULL CHECK (preferred_locale IN ('en', 'fr')),
    terms_version           TEXT        NOT NULL,
    privacy_notice_version  TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- First sign-in with a code: the account becomes ACTIVE.
    verified_at             TIMESTAMPTZ,
    -- When a code sent to each contact was first used. Guest bookings are claimed only through a
    -- contact proven this way, never through one merely typed in at sign-up.
    phone_verified_at       TIMESTAMPTZ,
    email_verified_at       TIMESTAMPTZ,
    CHECK (phone IS NOT NULL OR email IS NOT NULL)
);

CREATE INDEX user_account_pending_idx ON user_account (created_at) WHERE status = 'PENDING';

CREATE TABLE seeker_profile (
    account_id               UUID PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    -- What they want to be called; only their specialist sees it.
    display_name             TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 60),
    date_of_birth            DATE NOT NULL,
    specialist_gender        TEXT NOT NULL DEFAULT 'ANY' CHECK (specialist_gender IN ('ANY', 'WOMAN', 'MAN')),
    -- Set when the seeker saves sign-up step 2 (Make it yours); null until then.
    preferences_saved_at     TIMESTAMPTZ
);

CREATE TABLE seeker_session_language (
    account_id     UUID NOT NULL REFERENCES seeker_profile (account_id) ON DELETE CASCADE,
    language_code  TEXT NOT NULL REFERENCES session_language (code),
    PRIMARY KEY (account_id, language_code)
);

CREATE TABLE seeker_help_topic (
    account_id  UUID NOT NULL REFERENCES seeker_profile (account_id) ON DELETE CASCADE,
    topic_code  TEXT NOT NULL REFERENCES help_topic (code),
    PRIMARY KEY (account_id, topic_code)
);
