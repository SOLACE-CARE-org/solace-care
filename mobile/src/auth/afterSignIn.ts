import type { Href } from 'expo-router';

import { fetchMe, type Me } from '@/api/account';
import { queryClient } from '@/api/query-client';
import { accountKeys } from '@/api/account';
import i18n from '@/i18n';
import { useSession } from '@/state/session';

import { useAuth } from './auth';
import type { Tokens } from './keycloak';

/**
 * Stores the session, reads the account (which activates it on the first sign-in) and says where
 * to go: the next unfinished sign-up step, or the shell for the person's role.
 */
export async function afterSignIn(tokens: Tokens): Promise<Href> {
  await useAuth.getState().signedIn(tokens);
  const me = await fetchMe();
  queryClient.setQueryData(accountKeys.me, me);
  const role = me.role === 'SPECIALIST' ? 'specialist' : 'seeker';
  useSession.getState().signedInAs(role);
  if (me.appLanguage !== i18n.language) {
    useSession.getState().chooseLanguage(me.appLanguage);
    await i18n.changeLanguage(me.appLanguage);
  }
  return nextStep(me);
}

/** The screen a signed-in person belongs on. */
export function nextStep(me: Me): Href {
  if (me.role === 'SPECIALIST') return '/(specialist)/today';
  if (!me.onboarding.preferences) return '/signup/preferences';
  if (!me.onboarding.privacy) return '/signup/privacy';
  return '/(seeker)/home';
}
