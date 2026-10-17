-- A specialist's weekly hours (SpecAvailability; later Calendar & availability). System Design,
-- Core data model: availability_rule. Times are local to the specialist's time zone; slots are
-- computed on read (epic 4). Date exceptions come with epic 4.
CREATE TABLE specialist_schedule (
    specialist_id         UUID PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    time_zone             TEXT        NOT NULL,
    break_minutes         INTEGER     NOT NULL CHECK (break_minutes IN (0, 10, 15)),
    minimum_notice_hours  INTEGER     NOT NULL CHECK (minimum_notice_hours IN (2, 12, 24)),
    saved_at              TIMESTAMPTZ NOT NULL
);

CREATE TABLE availability_rule (
    id             UUID PRIMARY KEY,
    specialist_id  UUID     NOT NULL REFERENCES specialist_schedule (specialist_id) ON DELETE CASCADE,
    -- ISO weekday: 1 = Monday … 7 = Sunday.
    weekday        SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time     TIME     NOT NULL,
    end_time       TIME     NOT NULL,
    CHECK (start_time < end_time)
);

CREATE INDEX availability_rule_specialist_idx ON availability_rule (specialist_id, weekday);
