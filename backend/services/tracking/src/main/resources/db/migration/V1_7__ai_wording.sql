-- Phase 3, Tracking insights (D56): a recommendation's plain wording from the AI service, from its
-- evidence only. The rule's reviewed message stays the fallback.
ALTER TABLE recommendation ADD COLUMN wording_en TEXT CHECK (length(wording_en) <= 400);
ALTER TABLE recommendation ADD COLUMN wording_fr TEXT CHECK (length(wording_fr) <= 400);
ALTER TABLE recommendation ADD COLUMN wording_version TEXT;
