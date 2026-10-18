import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

/**
 * Emergency numbers bundled with the app, so Urgent help works with no connection at all (System
 * Design, Crisis button; D39). Cameroon: police 117, fire 118, ambulance (SAMU) 119. When Core has
 * a published crisis resource for the country, its numbers replace these (D44); the owner confirms
 * them before launch.
 */
export type EmergencyNumber = {
  number: string;
  service: 'ambulance' | 'police' | 'fire' | 'helpline';
};

export const EMERGENCY_NUMBERS: EmergencyNumber[] = [
  { number: '119', service: 'ambulance' },
  { number: '117', service: 'police' },
  { number: '118', service: 'fire' },
];

/** The country whose numbers the app carries. */
export const CRISIS_COUNTRY = 'CM';

type CrisisState = {
  numbers: EmergencyNumber[];
  replace: (numbers: EmergencyNumber[]) => void;
};

/** The last published numbers, stored on the device; the bundled ones until then. */
export const useEmergencyNumbers = create<CrisisState>()(
  persist(
    (set) => ({
      numbers: EMERGENCY_NUMBERS,
      replace: (numbers) => set({ numbers: numbers.length > 0 ? numbers : EMERGENCY_NUMBERS }),
    }),
    {
      name: 'solace.crisis-numbers',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: ({ numbers }) => ({ numbers }),
    },
  ),
);

/**
 * Fetches the published crisis resource for the country, off the Urgent help screen so that screen
 * never waits on Solace's servers. Offline keeps the stored numbers.
 */
export async function refreshEmergencyNumbers(
  fetchNumbers: () => Promise<EmergencyNumber[] | undefined>,
) {
  try {
    const numbers = await fetchNumbers();
    if (numbers) useEmergencyNumbers.getState().replace(numbers);
  } catch {
    // Offline or Core unreachable: keep what the device has.
  }
}
