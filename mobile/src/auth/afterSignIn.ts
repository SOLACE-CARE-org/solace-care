import type { Href } from 'expo-router';

import { fetchMe, type Me } from '@/api/account';
import { queryClient } from '@/api/query-client';
import { accountKeys } from '@/api/account';
import i18n from '@/i18n';
import { type Role, useSession } from '@/state/session';

import { registerIfAllowed } from '@/notifications/push';

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
  useSession.getState().signedInAs(roleOf(me));
  if (me.appLanguage !== i18n.language) {
    useSession.getState().chooseLanguage(me.appLanguage);
    await i18n.changeLanguage(me.appLanguage);
  }
  const next = nextStep(me);
  // Once per device, where a person lands in their shell: offer reminders (AllowReminders, D38).
  const shell = next === '/(seeker)/home' || next === '/(specialist)/today';
  if (shell && !useSession.getState().remindersAsked) {
    return { pathname: '/reminders', params: { next: next as string } };
  }
  if (shell) void registerIfAllowed();
  return next;
}

/** The shell a member gets: specialists only once their application is approved. */
export function roleOf(me: Me): Role {
  if (me.role !== 'SPECIALIST') return 'seeker';
  return me.applicationStatus === 'APPROVED' || me.applicationStatus === 'SUSPENDED'
    ? 'specialist'
    : 'applicant';
}

/** The screen a signed-in person belongs on. */
export function nextStep(me: Me): Href {
  const role = roleOf(me);
  if (role === 'specialist') return '/(specialist)/today';
  if (role === 'applicant') {
    return me.applicationStatus === 'DRAFT'
      ? '/specialist-signup/resume'
      : '/specialist-signup/review';
  }
  if (!me.onboarding.preferences) return '/signup/preferences';
  if (!me.onboarding.privacy) return '/signup/privacy';
  return '/(seeker)/home';
}
