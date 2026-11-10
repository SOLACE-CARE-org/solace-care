import { createTrackingClient, type TrackingSchemas } from '@solace/api-clients/tracking';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Middleware } from 'openapi-fetch';

import { accessToken } from '@/auth/auth';
import { env } from '@/config/env';
import i18n from '@/i18n';

import { cacheStore } from '@/tracking/store';

import { core, orThrow } from './core';

export type { TrackingSchemas };
export type Element = TrackingSchemas['Element'];
export type CatalogueModule = TrackingSchemas['CatalogueModule'];
export type Recommendation = TrackingSchemas['Recommendation'];
export type Observation = TrackingSchemas['Observation'];
export type PushEntry = TrackingSchemas['PushEntry'];
export type CycleSummary = TrackingSchemas['CycleSummary'];
export type ContextQuestion = TrackingSchemas['ContextQuestion'];
export type ContextAnswer = TrackingSchemas['ContextAnswerValue'];

const session: Middleware = {
  async onRequest({ request }) {
    const token = await accessToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    request.headers.set('Accept-Language', i18n.language || 'en');
    return request;
  },
};

/** Tracking through the gateway (contracts/openapi/tracking.yaml). Seekers only. */
export const tracking = createTrackingClient(`${env.apiUrl}/tracking`, session);

/** A module card image; images are not personal data and load without a token. */
export function imageUrl(id: string): string {
  return `${env.apiUrl}/tracking/v1/tracking/images/${id}`;
}

/** The phone's time zone, as tracking stores it (IANA). */
export function timeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Africa/Douala';
}

export const trackingKeys = {
  all: ['tracking'] as const,
  elements: ['tracking', 'elements'] as const,
  modules: (language: string) => ['tracking', 'modules', language] as const,
  recommendations: (language: string) => ['tracking', 'recommendations', language] as const,
  observations: ['tracking', 'observations'] as const,
  cycles: ['tracking', 'cycles'] as const,
  matches: (id: string, language: string) => ['tracking', 'matches', id, language] as const,
  questions: ['tracking', 'context-questions'] as const,
  answers: ['tracking', 'context-answers'] as const,
};

/**
 * Fetches, and keeps the answer on the phone; offline, the last answer is used instead (epic 9).
 */
async function cached<T>(key: string, fetch: () => Promise<T>): Promise<T> {
  try {
    const fresh = await fetch();
    await cacheStore.set(key, JSON.stringify(fresh)).catch(() => undefined);
    return fresh;
  } catch (e) {
    const kept = await cacheStore.get(key).catch(() => null);
    if (kept) return JSON.parse(kept) as T;
    throw e;
  }
}

/** The element library; it changes only with an app release, so it is kept for a day. */
export function useElements() {
  return useQuery({
    queryKey: trackingKeys.elements,
    queryFn: () =>
      cached('elements', async () => orThrow(await tracking.GET('/v1/tracking/elements'))),
    staleTime: 24 * 60 * 60 * 1000,
  });
}

/** Published modules, with what the person tracks. */
export function useModules(language: string, enabled = true) {
  return useQuery({
    queryKey: trackingKeys.modules(language),
    queryFn: () =>
      cached(`modules.${language}`, async () =>
        orThrow(await tracking.GET('/v1/tracking/modules')),
      ),
    enabled,
  });
}

export function useEnrol() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      code: string;
      answer?: string;
      answers?: Record<string, ContextAnswer>;
      reminderTime?: string;
    }) =>
      orThrow(
        await tracking.POST('/v1/tracking/modules/{code}/enrolment', {
          params: { path: { code: input.code } },
          body: {
            ...(input.answer ? { answer: input.answer } : {}),
            ...(input.answers && Object.keys(input.answers).length
              ? { answers: input.answers }
              : {}),
            ...(input.reminderTime ? { reminderTime: input.reminderTime } : {}),
          },
        }),
      ),
    onSuccess: () => queries.invalidateQueries({ queryKey: trackingKeys.all }),
  });
}

/**
 * The published context questions (D61): things about the person that can only switch a pattern
 * check off. Kept on the phone like the catalogue, so the screens work offline.
 */
export function useContextQuestions(enabled = true) {
  return useQuery({
    queryKey: trackingKeys.questions,
    queryFn: () =>
      cached('context-questions', async () =>
        orThrow(await tracking.GET('/v1/tracking/context-questions')),
      ),
    enabled,
  });
}

/** The person's answers by question code; a skipped question is absent. */
export function useContextAnswers(enabled = true) {
  return useQuery({
    queryKey: trackingKeys.answers,
    queryFn: async () => orThrow(await tracking.GET('/v1/tracking/context-answers')),
    enabled,
  });
}

/** Answers a question, or skips it again with null (the answer is then deleted). */
export function useAnswerContextQuestion() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (input: { code: string; value: ContextAnswer | null }) =>
      input.value === null
        ? orThrow(
            await tracking.DELETE('/v1/tracking/context-answers/{questionCode}', {
              params: { path: { questionCode: input.code } },
            }),
          )
        : orThrow(
            await tracking.PUT('/v1/tracking/context-answers/{questionCode}', {
              params: { path: { questionCode: input.code } },
              body: { value: input.value },
            }),
          ),
    onSuccess: () => queries.invalidateQueries({ queryKey: trackingKeys.answers }),
  });
}

export function useLeave() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (code: string) =>
      orThrow(
        await tracking.DELETE('/v1/tracking/modules/{code}/enrolment', {
          params: { path: { code } },
        }),
      ),
    onSuccess: () => queries.invalidateQueries({ queryKey: trackingKeys.all }),
  });
}

export function useSetReminder() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (input: { code: string; reminderTime: string | null }) =>
      orThrow(
        await tracking.PUT('/v1/tracking/modules/{code}/reminder', {
          params: { path: { code: input.code } },
          body: { reminderTime: input.reminderTime },
        }),
      ),
    onSuccess: () => queries.invalidateQueries({ queryKey: trackingKeys.all }),
  });
}

/** Open patterns and invitations, worded in the app language. */
export function useRecommendations(language: string, enabled = true) {
  return useQuery({
    queryKey: trackingKeys.recommendations(language),
    queryFn: async () => orThrow(await tracking.GET('/v1/tracking/recommendations')),
    enabled,
  });
}

type RecommendationAction = 'seen' | 'snooze' | 'dismissal';

export function useRecommendationAction() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: RecommendationAction }) => {
      const params = { params: { path: { id } } };
      if (action === 'seen') {
        return orThrow(await tracking.POST('/v1/tracking/recommendations/{id}/seen', params));
      }
      if (action === 'snooze') {
        return orThrow(
          await tracking.POST('/v1/tracking/recommendations/{id}/snooze', { ...params, body: {} }),
        );
      }
      return orThrow(await tracking.POST('/v1/tracking/recommendations/{id}/dismissal', params));
    },
    onSuccess: (_, { action }) => {
      if (action !== 'seen')
        void queries.invalidateQueries({ queryKey: ['tracking', 'recommendations'] });
    },
  });
}

/** Specialists for an invite, from Core (D52). */
export function useInviteMatches(id: string, language: string, enabled = true) {
  return useQuery({
    queryKey: trackingKeys.matches(id, language),
    queryFn: async () =>
      orThrow(
        await core.GET('/v1/me/invites/{recommendationId}/specialists', {
          params: { path: { recommendationId: id } },
        }),
      ),
    enabled,
  });
}

/** Cycles and today's cycle day; facts only, never a forecast (D50). */
export function useCycles(enabled = true) {
  return useQuery({
    queryKey: trackingKeys.cycles,
    queryFn: async () =>
      orThrow(
        await tracking.GET('/v1/tracking/cycles', { params: { query: { timeZone: timeZone() } } }),
      ),
    enabled,
  });
}

/** Everything the person logged, from every phone, pulled page by page. */
export async function pullAll(): Promise<Observation[]> {
  const live = new Map<string, Observation>();
  let since: number | undefined;
  for (let page = 0; page < 50; page++) {
    const result = orThrow(
      await tracking.GET('/v1/tracking/sync/pull', {
        params: { query: since === undefined ? {} : { since } },
      }),
    );
    for (const id of result.deleted) live.delete(id);
    for (const o of result.observations) live.set(o.id, o);
    since = result.cursor;
    if (!result.hasMore) break;
  }
  return [...live.values()];
}

export function useObservations(enabled = true) {
  return useQuery({ queryKey: trackingKeys.observations, queryFn: pullAll, enabled });
}

// --- Sharing with a specialist (D52) -------------------------------------------------------------

export type ShareGrant = TrackingSchemas['ShareGrant'];

/** The seeker's sharing grants, active and revoked. */
export function useShares(enabled: boolean) {
  return useQuery({
    queryKey: [...trackingKeys.all, 'shares'],
    queryFn: async () => orThrow(await tracking.GET('/v1/tracking/shares')),
    enabled,
  });
}

/** Shares what is logged from now on with the specialist, or stops sharing at once. */
export function useSetSharing() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async ({
      specialistId,
      share,
      grantId,
    }: {
      specialistId: string;
      share: boolean;
      grantId?: string;
    }) => {
      if (share) {
        orThrow(
          await tracking.POST('/v1/tracking/shares', {
            body: { specialistId, includesNotes: false },
          }),
        );
      } else if (grantId) {
        const result = await tracking.DELETE('/v1/tracking/shares/{grantId}', {
          params: { path: { grantId } },
        });
        if (!result.response.ok) orThrow(result);
      }
    },
    onSettled: () => void queries.invalidateQueries({ queryKey: [...trackingKeys.all, 'shares'] }),
  });
}
