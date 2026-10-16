-- Idempotent consumers: one row per consumer and event id handled. A redelivered event finds
-- its row and is skipped. Shipped by libs/event-envelope.

CREATE TABLE processed_event
(
    consumer     TEXT        NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
CREATE INDEX processed_event_processed_at_idx ON processed_event (processed_at);
