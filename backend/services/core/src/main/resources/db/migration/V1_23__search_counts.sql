-- Searches per day for the booking funnel (admin screen 17; D46). A count only: searches carry no
-- identity, no filters and no text.
CREATE TABLE search_day (
    day       DATE   PRIMARY KEY,
    searches  BIGINT NOT NULL DEFAULT 0
);
