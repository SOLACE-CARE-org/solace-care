import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

export type AppLanguage = 'en' | 'fr';
/**
 * Picks the navigation shell. An applicant is a specialist whose application is not approved yet:
 * they see their application, not the specialist app.
 */
export type Role = 'seeker' | 'applicant' | 'specialist';

type SessionState = {
  /** The app language chosen on first launch; null until chosen. Not the session language. */
  language: AppLanguage | null;
  /** Set after sign-in from the token's role; null for guests. Picks the navigation shell. */
  role: Role | null;
  /** Turn on reminders was offered on this device (AllowReminders). */
  remindersAsked: boolean;
  /** Data saver: video calls start with the camera off (Settings). */
  dataSaver: boolean;
  askedReminders: () => void;
  setDataSaver: (on: boolean) => void;
  chooseLanguage: (language: AppLanguage) => void;
  signedInAs: (role: Role) => void;
  signedOut: () => void;
};

/** Device-local choices that survive restarts. Tokens never go here (they go to secure storage). */
export const useSession = create<SessionState>()(
  persist(
    (set) => ({
      language: null,
      role: null,
      remindersAsked: false,
      dataSaver: false,
      askedReminders: () => set({ remindersAsked: true }),
      setDataSaver: (dataSaver) => set({ dataSaver }),
      chooseLanguage: (language) => set({ language }),
      signedInAs: (role) => set({ role }),
      signedOut: () => set({ role: null }),
    }),
    {
      name: 'solace.session',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({
        language: state.language,
        role: state.role,
        remindersAsked: state.remindersAsked,
        dataSaver: state.dataSaver,
      }),
    },
  ),
);
