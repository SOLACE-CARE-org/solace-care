-- Phase 4, admin copilot (admin screen 27; D58): anomaly alerts over aggregates, one per kind a day.
CREATE TABLE insight_alert (
    id          UUID PRIMARY KEY,
    kind        TEXT        NOT NULL CHECK (kind IN ('BOOKING_DROP', 'PAYMENT_FAILURE_SPIKE')),
    day         DATE        NOT NULL,
    value       NUMERIC(10, 2) NOT NULL,
    baseline    NUMERIC(10, 2) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    UNIQUE (kind, day)
);
