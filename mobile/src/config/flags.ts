import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

/**
 * Features that ship in a later phase, or wait for a spec, stay off until staff turn them on in
 * Settings and flags (CLAUDE.md, Feature flags; D42). These are the defaults until Core answers.
 */
const defaults = {
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
  /** The health check, Phase 2. */
  healthCheck: false,
  /** AI assist, Phase 3: the pre-session brief for specialists (D56). */
  aiAssist: false,
  /** The session scribe, Phase 4: per-session consent, note drafts for specialists (D57). */
  sessionScribe: false,
  /** The homework companion and the safety plan, Phase 4 (D57). */
  companion: false,
  /** Phone dial-in for sessions, Phase 4. */
  phoneDialIn: false,
};

export type Flags = typeof defaults;

type FlagState = Flags & { apply: (fromCore: Record<string, boolean>) => void };

/** The last flags Core sent, kept so the app starts with them offline. */
export const useFlags = create<FlagState>()(
  persist(
    (set) => ({
      ...defaults,
      apply: (fromCore) => {
        const next: Partial<Flags> = {};
        for (const key of Object.keys(defaults) as (keyof Flags)[]) {
          next[key] = fromCore[key] === true;
        }
        set(next);
      },
    }),
    {
      name: 'solace.flags',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: ({ apply: _apply, ...flags }) => flags,
    },
  ),
);

/** Reads the current flags outside React; components use {@link useFlags} to follow changes. */
export const flags: Readonly<Flags> = new Proxy(defaults, {
  get: (_target, key: string) => useFlags.getState()[key as keyof Flags],
});

/** Asks Core for the flags in force (`GET /v1/config`, no token). Offline keeps the last ones. */
export async function refreshFlags(fetchConfig: () => Promise<Record<string, boolean> | undefined>) {
  try {
    const fromCore = await fetchConfig();
    if (fromCore) useFlags.getState().apply(fromCore);
  } catch {
    // Offline or Core unreachable: the stored flags stay.
  }
}
