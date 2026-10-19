-- Phase 3, review moderation (D56): the AI's suggestion for the moderator, who still decides.
ALTER TABLE rating ADD COLUMN ai_suggestion TEXT CHECK (ai_suggestion IN ('PUBLISH', 'HOLD'));
ALTER TABLE rating ADD COLUMN ai_reasons TEXT[] NOT NULL DEFAULT '{}';
