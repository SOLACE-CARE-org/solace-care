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
