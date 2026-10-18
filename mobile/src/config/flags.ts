/**
 * Features that ship in a later phase stay off until then (CLAUDE.md, Feature flags). Phase 0
 * builds the shells only. Later these come from Core's admin settings.
 */
export const flags = {
  /** The Track tab, Phase 2 (implementation-plan.md: "the Track tab appears only when this phase ships"). */
  trackTab: false,
  /** "Pick an interview time" on SpecReview: off until interview times are specified (D25). */
  specialistInterviewPicker: false,
  /** Bank transfer on SpecPayout: off until the bank details to collect are specified (D26). */
  payoutBankTransfer: false,
  /** Card payments on Checkout: cards follow mobile money as a second adapter (implementation-plan.md, epic 6). */
  cardPayments: false,
  /** Pay out now on Earnings: off, an off-cycle payout would skip the two-person run (D35). */
  payoutOnRequest: false,
  /** Talk to an on-call specialist on Urgent help: off until that service is specified (D39). */
  onCallSpecialistCall: false,
} as const;
