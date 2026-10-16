-- Transactional outbox (System Design, Async contracts): Spring Modulith's event publication
-- registry. An event is written here in the same transaction as the change that caused it, then
-- sent to Kafka after the commit and deleted once the broker has it (completion mode DELETE).
-- Schema copied from spring-modulith-events-jdbc 1.4 (schema-postgresql.sql).
-- Shipped by libs/event-envelope and added to each service's Flyway locations automatically.

CREATE TABLE IF NOT EXISTS event_publication
(
    id               UUID NOT NULL,
    listener_id      TEXT NOT NULL,
    event_type       TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
