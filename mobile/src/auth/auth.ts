import * as SecureStore from 'expo-secure-store';
import { create } from 'zustand';

import { refresh, type Tokens } from './keycloak';

const STORAGE_KEY = 'solace.tokens';
/** Refresh a little before the access token expires, so a request never races the expiry. */
const EARLY_MS = 30_000;

type AuthState = {
  /** 'unknown' until the stored tokens have been read at start-up. */
  status: 'unknown' | 'signedOut' | 'signedIn';
  tokens: Tokens | null;
  load: () => Promise<void>;
  signedIn: (tokens: Tokens) => Promise<void>;
  signOut: () => Promise<void>;
};

/**
 * The signed-in session. Tokens live in the platform's secure storage (Keychain, Keystore), never in
 * AsyncStorage (threat model CL-3), and only in memory otherwise.
 */
export const useAuth = create<AuthState>()((set) => ({
  status: 'unknown',
  tokens: null,
  load: async () => {
    const stored = await SecureStore.getItemAsync(STORAGE_KEY);
    const tokens = stored ? (JSON.parse(stored) as Tokens) : null;
    set({ tokens, status: tokens ? 'signedIn' : 'signedOut' });
  },
  signedIn: async (tokens) => {
    await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(tokens));
    set({ tokens, status: 'signedIn' });
  },
  signOut: async () => {
    await SecureStore.deleteItemAsync(STORAGE_KEY);
    set({ tokens: null, status: 'signedOut' });
  },
}));

let refreshing: Promise<string | null> | null = null;

/**
 * A valid access token, refreshed when needed; null when signed out or the session has ended
 * (which signs the person out).
 */
export async function accessToken(): Promise<string | null> {
  const { tokens, signedIn, signOut } = useAuth.getState();
  if (!tokens) return null;
  if (Date.now() < tokens.accessExpiresAt - EARLY_MS) return tokens.accessToken;
  refreshing ??= refresh(tokens.refreshToken)
    .then(async (next) => {
      if (!next) {
        await signOut();
        return null;
      }
      await signedIn(next);
      return next.accessToken;
    })
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}
