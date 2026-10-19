import { useQuery } from '@tanstack/react-query';
import * as SecureStore from 'expo-secure-store';

import { core, type CoreSchemas, orThrow } from './core';

export type HealthCheckForm = CoreSchemas['HealthCheckForm'];
export type HealthCheckQuestion = CoreSchemas['HealthCheckFormQuestion'];
export type HealthCheckProgress = CoreSchemas['HealthCheckProgress'];
export type HealthCheckResult = CoreSchemas['HealthCheckResult'];
export type HealthCheckPreferences = CoreSchemas['HealthCheckPreferences'];
export type HealthCheckMatch = CoreSchemas['HealthCheckMatch'];
export type Answers = Record<string, string[]>;

const GUEST_KEY = 'solace.health-check.guest';

/**
 * A guest's response id and token. The token opens health data, so it lives in the Keychain or
 * Keystore, never in AsyncStorage (D55).
 */
export const guestResponse = {
  async get(): Promise<{ id: string; token: string } | null> {
    const kept = await SecureStore.getItemAsync(GUEST_KEY);
    return kept ? (JSON.parse(kept) as { id: string; token: string }) : null;
  },
  async set(id: string, token: string): Promise<void> {
    await SecureStore.setItemAsync(GUEST_KEY, JSON.stringify({ id, token }));
  },
  async clear(): Promise<void> {
    await SecureStore.deleteItemAsync(GUEST_KEY);
  },
};

function tokenHeader(token: string | null | undefined) {
  return token ? { 'X-Health-Check-Token': token } : {};
}

/** The published version's questions, in the app language. */
export function useHealthCheckForm(language: string) {
  return useQuery({
    queryKey: ['health-check', 'form', language],
    queryFn: async () => orThrow(await core.GET('/v1/health-check')),
    retry: false,
  });
}

export async function startHealthCheck(consentVersion: string) {
  return orThrow(await core.POST('/v1/health-check/responses', { body: { consentVersion } }));
}

export async function saveAnswers(id: string, token: string | null, answers: Answers) {
  return orThrow(
    await core.PUT('/v1/health-check/responses/{responseId}/answers', {
      params: { path: { responseId: id }, header: tokenHeader(token) },
      body: { answers },
    }),
  );
}

export async function completeHealthCheck(
  id: string,
  token: string | null,
  preferences: HealthCheckPreferences,
) {
  return orThrow(
    await core.POST('/v1/health-check/responses/{responseId}/completion', {
      params: { path: { responseId: id }, header: tokenHeader(token) },
      body: preferences,
    }),
  );
}

export async function requestCallback(id: string, token: string | null, phone: string) {
  return orThrow(
    await core.POST('/v1/health-check/responses/{responseId}/callback', {
      params: { path: { responseId: id }, header: tokenHeader(token) },
      body: { phone },
    }),
  );
}

/** After sign-up, a guest's answers become the account's. */
export async function claimGuestResponse(): Promise<void> {
  const kept = await guestResponse.get();
  if (!kept) return;
  try {
    orThrow(
      await core.POST('/v1/health-check/responses/{responseId}/claim', {
        params: { path: { responseId: kept.id }, header: tokenHeader(kept.token) },
      }),
    );
  } finally {
    await guestResponse.clear();
  }
}

export function useHealthCheckMatches(id: string | null, token: string | null) {
  return useQuery({
    queryKey: ['health-check', 'matches', id],
    queryFn: async () =>
      orThrow(
        await core.GET('/v1/health-check/responses/{responseId}/matches', {
          params: { path: { responseId: id! }, header: tokenHeader(token) },
        }),
      ),
    enabled: Boolean(id),
  });
}

export function useLatestHealthCheck(enabled: boolean) {
  return useQuery({
    queryKey: ['health-check', 'latest'],
    queryFn: async () => orThrow(await core.GET('/v1/health-check/responses/latest')),
    enabled,
    retry: false,
  });
}
