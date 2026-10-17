-- Staff review of specialist applications (admin screens 4 to 6, Phase 1, epic 2).

ALTER TABLE specialist_profile
    -- Who took the application when opening it for review. Staff live in Keycloak's staff realm;
    -- the name is kept as shown in the queue and the activity log.
    ADD COLUMN assigned_staff_id     TEXT,
    ADD COLUMN assigned_staff_name   TEXT,
    ADD COLUMN review_started_at     TIMESTAMPTZ,
    -- Set while the application is back with the applicant after a change request.
    ADD COLUMN changes_requested_at  TIMESTAMPTZ,
    ADD COLUMN decided_at            TIMESTAMPTZ,
    -- Recorded by the reviewer when they verify the licence; the record reminds 60 days before.
    ADD COLUMN licence_valid_until   DATE,
    -- Reviewer notes, staff only.
    ADD COLUMN staff_notes           TEXT CHECK (length(staff_notes) <= 4000);

-- The level a reviewer confirmed in the interview. Only PROFESSIONAL and above are bookable.
ALTER TABLE specialist_language
    ADD COLUMN verified_level     TEXT CHECK (verified_level IN ('NATIVE', 'FLUENT', 'PROFESSIONAL', 'BELOW_PROFESSIONAL')),
    ADD COLUMN verified_by_id     TEXT,
    ADD COLUMN verified_by_name   TEXT;

-- Items a reviewer ticks. Languages are verified one by one above, and services follow from the
-- price bands, so neither has a row here.
CREATE TABLE specialist_review_item (
    account_id        UUID        NOT NULL REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    item              TEXT        NOT NULL CHECK (item IN ('IDENTITY', 'LICENCE', 'PROFILE', 'PAYOUT')),
    verified_by_id    TEXT        NOT NULL,
    verified_by_name  TEXT        NOT NULL,
    verified_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, item)
);

-- What happened to an application, as the review screen's Activity list shows it. Codes only: the
-- portal words each kind; reasons for the applicant stay in specialist_profile.review_note.
CREATE TABLE specialist_review_activity (
    id          UUID PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES specialist_profile (account_id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL,
    kind        TEXT        NOT NULL CHECK (kind IN ('SUBMITTED', 'OPENED', 'ITEM_VERIFIED', 'ITEM_UNVERIFIED',
                                                     'LANGUAGE_VERIFIED', 'BIO_ADDED', 'INTERVIEW_BOOKED',
                                                     'CHANGES_REQUESTED', 'APPROVED', 'REJECTED',
                                                     'SUSPENDED', 'REINSTATED')),
    subject     TEXT,
    value       TEXT,
    staff_id    TEXT,
    staff_name  TEXT
);

CREATE INDEX specialist_review_activity_account_idx ON specialist_review_activity (account_id, at DESC);
CREATE INDEX specialist_profile_search_idx ON specialist_profile (lower(full_name));
