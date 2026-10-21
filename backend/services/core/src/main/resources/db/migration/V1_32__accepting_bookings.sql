-- A specialist can pause new bookings from Today (SpecialistToday.dc.html); booked sessions stay.
ALTER TABLE specialist_schedule ADD COLUMN accepting_bookings BOOLEAN NOT NULL DEFAULT true;

-- Licence renewal reminders, 60 days before the licence a reviewer recorded ends; once per date.
CREATE TABLE licence_reminder (
    specialist_id UUID NOT NULL,
    valid_until   DATE NOT NULL,
    sent_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (specialist_id, valid_until)
);
