import { useQuery } from '@tanstack/react-query';

import type { Access } from './bookings';
import { core, type CoreSchemas, orThrow } from './core';

export type JoinToken = CoreSchemas['JoinToken'];

/** A media token for the booking's room, from 10 minutes before the start (D18). */
export async function joinSession({ bookingId, guestToken }: Access): Promise<JoinToken> {
  return guestToken
    ? orThrow(
        await core.POST('/v1/guest/booking/join', {
          params: { header: { 'X-Guest-Token': guestToken } },
        }),
      )
    : orThrow(
        await core.POST('/v1/bookings/{bookingId}/join', { params: { path: { bookingId } } }),
      );
}

export type DialIn = CoreSchemas['DialIn'];

/** The number and PIN to join by phone (Phase 4, D59); absent while it is not offered. */
export function useDialIn({ bookingId, guestToken }: Access, enabled: boolean) {
  return useQuery({
    queryKey: ['dial-in', bookingId],
    queryFn: async () =>
      guestToken
        ? orThrow(
            await core.GET('/v1/guest/booking/dial-in', {
              params: { header: { 'X-Guest-Token': guestToken } },
            }),
          )
        : orThrow(
            await core.GET('/v1/bookings/{bookingId}/dial-in', { params: { path: { bookingId } } }),
          ),
    enabled,
    retry: false,
  });
}
