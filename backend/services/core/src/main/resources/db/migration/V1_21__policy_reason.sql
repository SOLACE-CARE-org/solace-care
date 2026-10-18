-- Catalogue and policies (admin screen 14; D43): a new cancellation policy version records why.
ALTER TABLE cancellation_policy ADD COLUMN reason TEXT CHECK (length(reason) <= 500);
