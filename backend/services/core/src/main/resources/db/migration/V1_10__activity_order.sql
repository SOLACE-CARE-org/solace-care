-- Entries written in one transaction share a timestamp; the insertion order tells them apart.
ALTER TABLE specialist_review_activity ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
