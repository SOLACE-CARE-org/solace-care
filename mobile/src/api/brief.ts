import { type AiSchemas, createAiClient } from '@solace/api-clients/ai';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Middleware } from 'openapi-fetch';

import { accessToken } from '@/auth/auth';
import { env } from '@/config/env';
import i18n from '@/i18n';

import { core, orThrow } from './core';

export type Brief = AiSchemas['Brief'];

const session: Middleware = {
  async onRequest({ request }) {
    const token = await accessToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    request.headers.set('Accept-Language', i18n.language || 'en');
    return request;
  },
};

/** The AI service through the gateway (contracts/openapi/ai.yaml). */
export const ai = createAiClient(`${env.apiUrl}/ai`, session);

/** The pre-session brief for the booking's specialist (D56). */
export function useBrief(bookingId: string, language: string) {
  return useQuery({
    queryKey: ['brief', bookingId, language],
    queryFn: async () =>
      orThrow(
        await ai.GET('/v1/specialist/bookings/{bookingId}/brief', {
          params: { path: { bookingId } },
        }),
      ),
  });
}

/** The specialist's private prep notes; null when none yet. */
export function usePrepNote(bookingId: string) {
  return useQuery({
    queryKey: ['prep-note', bookingId],
    queryFn: async () => {
      const result = await core.GET('/v1/specialist/bookings/{bookingId}/prep-note', {
        params: { path: { bookingId } },
      });
      if (result.response.status === 204) return null;
      return orThrow(result);
    },
  });
}

export function useSavePrepNote(bookingId: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: string) =>
      orThrow(
        await core.PUT('/v1/specialist/bookings/{bookingId}/prep-note', {
          params: { path: { bookingId } },
          body: { body },
        }),
      ),
    onSuccess: (saved) => queries.setQueryData(['prep-note', bookingId], saved),
  });
}
