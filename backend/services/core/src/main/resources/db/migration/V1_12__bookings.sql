-- Guest access and bookings (Phase 1, epic 5; D32-D34).

-- System Design, Core data model: guest_contact. A guest is a phone number proven by a code.
CREATE TABLE guest_contact (
    id                     UUID PRIMARY KEY,
    phone                  TEXT        NOT NULL UNIQUE CHECK (phone ~ '^[+][1-9][0-9]{7,14}$'),
    preferred_locale       TEXT        NOT NULL CHECK (preferred_locale IN ('en', 'fr')),
    verified_at            TIMESTAMPTZ NOT NULL,
    -- Set when a member's first sign-in proves the same phone (D6, D34).
    claimed_by_account_id  UUID REFERENCES user_account (id) ON DELETE SET NULL,
    claimed_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Booking-scoped guest tokens (D34). Only a hash is stored; the token goes to the guest once.
CREATE TABLE guest_token (
    token_hash        TEXT PRIMARY KEY,
    guest_contact_id  UUID        NOT NULL REFERENCES guest_contact (id) ON DELETE CASCADE,
    booking_id        UUID        NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX guest_token_booking_idx ON guest_token (booking_id);

-- Versioned cancellation and reschedule policy (D33). A booking keeps the version it was made under.
CREATE TABLE cancellation_policy (
    version                            INTEGER PRIMARY KEY,
    full_refund_hours                  INTEGER     NOT NULL CHECK (full_refund_hours >= 0),
    partial_refund_hours               INTEGER     NOT NULL CHECK (partial_refund_hours >= 0),
    partial_refund_percent             INTEGER     NOT NULL CHECK (partial_refund_percent BETWEEN 0 AND 100),
    seeker_no_show_refund_percent      INTEGER     NOT NULL CHECK (seeker_no_show_refund_percent BETWEEN 0 AND 100),
    specialist_no_show_refund_percent  INTEGER     NOT NULL CHECK (specialist_no_show_refund_percent BETWEEN 0 AND 100),
    max_reschedules                    INTEGER     NOT NULL CHECK (max_reschedules >= 0),
    reschedule_min_hours               INTEGER     NOT NULL CHECK (reschedule_min_hours >= 0),
    published_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by                       TEXT        NOT NULL,
    CHECK (full_refund_hours >= partial_refund_hours)
);

INSERT INTO cancellation_policy (version, full_refund_hours, partial_refund_hours,
                                 partial_refund_percent, seeker_no_show_refund_percent,
                                 specialist_no_show_refund_percent, max_reschedules,
                                 reschedule_min_hours, published_by)
VALUES (1, 24, 2, 50, 0, 100, 2, 2, 'system: D33 defaults');

-- System Design, Core data model: booking. The session's format, length and price are copied at
-- booking time: offerings change, a booking's price does not.
CREATE TABLE booking (
    id                 UUID PRIMARY KEY,
    seeker_account_id  UUID REFERENCES user_account (id) ON DELETE SET NULL,
    guest_contact_id   UUID REFERENCES guest_contact (id) ON DELETE SET NULL,
    specialist_id      UUID        NOT NULL REFERENCES user_account (id),
    modality           TEXT        NOT NULL CHECK (modality IN ('AUDIO', 'VIDEO')),
    duration_min       INTEGER     NOT NULL CHECK (duration_min > 0),
    price              BIGINT      NOT NULL CHECK (price >= 0),
    currency           TEXT        NOT NULL DEFAULT 'XAF' CHECK (currency = 'XAF'),
    session_language   TEXT        NOT NULL REFERENCES session_language (code),
    -- The language the seeker uses the app in, for messages about this booking.
    seeker_locale      TEXT        NOT NULL CHECK (seeker_locale IN ('en', 'fr')),
    starts_at          TIMESTAMPTZ NOT NULL,
    ends_at            TIMESTAMPTZ NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'IN_PROGRESS',
                                                          'COMPLETED', 'EXPIRED', 'CANCELLED', 'NO_SHOW')),
    source             TEXT        NOT NULL CHECK (source IN ('SEARCH', 'HEALTH_CHECK', 'TRACKING_RECOMMENDATION')),
    policy_version     INTEGER     NOT NULL REFERENCES cancellation_policy (version),
    reschedules        INTEGER     NOT NULL DEFAULT 0,
    pay_by             TIMESTAMPTZ,
    confirmed_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    cancelled_by       TEXT CHECK (cancelled_by IN ('SEEKER', 'SPECIALIST', 'STAFF', 'SYSTEM')),
    cancel_reason      TEXT CHECK (cancel_reason IN ('TIME_NO_LONGER_WORKS', 'FEELING_BETTER',
                                                     'DIFFERENT_SPECIALIST', 'OTHER')),
    refund_amount      BIGINT CHECK (refund_amount >= 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (seeker_account_id IS NOT NULL OR guest_contact_id IS NOT NULL),
    CHECK (starts_at < ends_at)
);

CREATE INDEX booking_seeker_idx ON booking (seeker_account_id, starts_at);
CREATE INDEX booking_guest_idx ON booking (guest_contact_id, starts_at);
CREATE INDEX booking_specialist_idx ON booking (specialist_id, starts_at);
CREATE INDEX booking_unpaid_idx ON booking (pay_by) WHERE status = 'PENDING_PAYMENT';
