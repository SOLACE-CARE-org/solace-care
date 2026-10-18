-- Phase 2, epic 3 (Tracking Service Spec, Signals; D50): cycles derived from period_start events
-- and flow_intensity day states, rebuilt by CycleReconciler after every change. Derived data only:
-- erasing or editing observations rebuilds it.
CREATE TABLE cycle (
    user_id          UUID    NOT NULL,
    start_date       DATE    NOT NULL,
    -- The day before the next cycle starts; null for the newest cycle.
    end_date         DATE,
    period_end_date  DATE,
    length_days      INTEGER,
    period_days      INTEGER,
    very_heavy_days  INTEGER NOT NULL DEFAULT 0,
    -- False for the newest cycle, which never counts.
    complete         BOOLEAN NOT NULL,
    PRIMARY KEY (user_id, start_date)
);
