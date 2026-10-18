-- Approving a specialist takes two people (D29), and the reviewer may draft the missing-language
-- bio for the specialist to check (D30).

ALTER TABLE specialist_profile
    -- Set while an approval waits for a second staff member; cleared if it is withdrawn or the
    -- verified items change.
    ADD COLUMN approval_proposed_by_id    TEXT,
    ADD COLUMN approval_proposed_by_name  TEXT,
    ADD COLUMN approval_proposed_at       TIMESTAMPTZ,
    -- The bio language Solace drafted; the specialist checks it before the profile is listed.
    ADD COLUMN bio_drafted_language       TEXT CHECK (bio_drafted_language IN ('en', 'fr')),
    ADD COLUMN bio_draft_confirmed_at     TIMESTAMPTZ;

ALTER TABLE specialist_review_activity DROP CONSTRAINT specialist_review_activity_kind_check;
ALTER TABLE specialist_review_activity ADD CONSTRAINT specialist_review_activity_kind_check
    CHECK (kind IN ('SUBMITTED', 'OPENED', 'ITEM_VERIFIED', 'ITEM_UNVERIFIED', 'LANGUAGE_VERIFIED',
                    'BIO_ADDED', 'INTERVIEW_BOOKED', 'CHANGES_REQUESTED', 'APPROVAL_PROPOSED',
                    'APPROVAL_WITHDRAWN', 'APPROVED', 'REJECTED', 'SUSPENDED', 'REINSTATED'));
