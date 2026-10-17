/**
 * Features that ship in a later phase stay off until then (CLAUDE.md, Feature flags). Phase 0
 * builds the shells only. Later these come from Core's admin settings.
 */
export const flags = {
  /** The Track tab, Phase 2 (implementation-plan.md: "the Track tab appears only when this phase ships"). */
  trackTab: false,
} as const;
