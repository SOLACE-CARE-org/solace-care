import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { core, type CoreSchemas, orThrow } from './core';

export type Me = CoreSchemas['Me'];
export type Preferences = CoreSchemas['Preferences'];
export type Consents = CoreSchemas['Consents'];
export type ConsentPurpose = CoreSchemas['ConsentPurpose'];
export type SignupRequest = CoreSchemas['SignupRequest'];

export const accountKeys = {
  me: ['me'] as const,
  preferences: ['me', 'preferences'] as const,
  consents: ['me', 'consents'] as const,
  sessionLanguages: (language: string) => ['catalogue', 'session-languages', language] as const,
  helpTopics: (language: string) => ['catalogue', 'help-topics', language] as const,
};

/** Step 1 of sign-up. Core answers 202 whether or not the number is new. */
export async function startSignup(body: SignupRequest): Promise<void> {
  orThrow(await core.POST('/v1/signups', { body }));
}

/** The signed-in account; the first call after a code sign-in also activates it. */
export async function fetchMe(): Promise<Me> {
  return orThrow(await core.GET('/v1/me'));
}

export function useMe(enabled = true) {
  return useQuery({ queryKey: accountKeys.me, queryFn: fetchMe, enabled });
}

export function usePreferences() {
  return useQuery({
    queryKey: accountKeys.preferences,
    queryFn: async () => orThrow(await core.GET('/v1/me/preferences')),
  });
}

export function useSavePreferences() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: Preferences) =>
      orThrow(await core.PUT('/v1/me/preferences', { body })),
    onSuccess: (saved) => {
      queries.setQueryData(accountKeys.preferences, saved);
      queries.invalidateQueries({ queryKey: accountKeys.me });
    },
  });
}

export function useConsents() {
  return useQuery({
    queryKey: accountKeys.consents,
    queryFn: async () => orThrow(await core.GET('/v1/me/consents')),
  });
}

export function useSaveConsents() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (choices: { purpose: ConsentPurpose; granted: boolean }[]) =>
      orThrow(await core.PUT('/v1/me/consents', { body: { choices } })),
    onSuccess: (saved) => {
      queries.setQueryData(accountKeys.consents, saved);
      queries.invalidateQueries({ queryKey: accountKeys.me });
    },
  });
}

/** Session languages, named in the app language. */
export function useSessionLanguages(language: string) {
  return useQuery({
    queryKey: accountKeys.sessionLanguages(language),
    queryFn: async () => orThrow(await core.GET('/v1/catalogue/session-languages')),
    staleTime: 60 * 60_000,
  });
}

/** Help topics, labelled in the app language. */
export function useHelpTopics(language: string) {
  return useQuery({
    queryKey: accountKeys.helpTopics(language),
    queryFn: async () => orThrow(await core.GET('/v1/catalogue/help-topics')),
    staleTime: 60 * 60_000,
  });
}
