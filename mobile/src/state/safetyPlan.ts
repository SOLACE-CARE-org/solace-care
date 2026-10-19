import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

import type { SafetyPlan } from '@/api/care';

type State = {
  /** The last plan Core sent or saved; undefined until the first answer, null when there is none. */
  plan: SafetyPlan | null | undefined;
  set: (plan: SafetyPlan | null) => void;
};

/**
 * The seeker's safety plan kept on the phone (D57), so it opens with no connection, like Urgent
 * help. Cleared on sign-out with the rest of the session.
 */
export const useSafetyPlan = create<State>()(
  persist(
    (set) => ({
      plan: undefined,
      set: (plan) => set({ plan }),
    }),
    {
      name: 'solace.safety-plan',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: ({ plan }) => ({ plan }),
    },
  ),
);
