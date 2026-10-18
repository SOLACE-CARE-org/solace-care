import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as SecureStore from 'expo-secure-store';

import { core, type CoreSchemas, orThrow } from './core';

export type Booking = CoreSchemas['Booking'];
export type BookingStatus = CoreSchemas['BookingStatus'];
export type CancellationQuote = CoreSchemas['CancellationQuote'];
export type CancelReason =
  'TIME_NO_LONGER_WORKS' | 'FEELING_BETTER' | 'DIFFERENT_SPECIALIST' | 'OTHER';
export type Scope = 'UPCOMING' | 'PAST';

/**
 * Who is asking about a booking: a signed-in member (by booking id) or a guest (by the token from
 * checkout or the SMS link, D34).
 */
export type Access = { bookingId: string; guestToken?: string };

export const bookingKeys = {
  mine: (scope: Scope) => ['bookings', 'mine', scope] as const,
  one: (bookingId: string) => ['bookings', bookingId] as const,
  quote: (bookingId: string) => ['bookings', bookingId, 'quote'] as const,
};

// --- Guest tokens: secure storage only, one per booking ----------------------------------------

const GUEST_TOKENS = 'solace.guestBookings';

async function guestTokens(): Promise<Record<string, string>> {
  try {
    return JSON.parse((await SecureStore.getItemAsync(GUEST_TOKENS)) ?? '{}');
  } catch {
    return {};
  }
}

export async function rememberGuestBooking(bookingId: string, token: string): Promise<void> {
  const all = await guestTokens();
  all[bookingId] = token;
  await SecureStore.setItemAsync(GUEST_TOKENS, JSON.stringify(all));
}

export async function guestTokenFor(bookingId: string): Promise<string | undefined> {
  return (await guestTokens())[bookingId];
}

/**
 * The guest token saved for this booking on this device, or null for a member's booking. Screens
 * that serve both wait for this before asking Core.
 */
export function useGuestToken(bookingId: string) {
  return useQuery({
    queryKey: ['guestToken', bookingId],
    queryFn: async () => (await guestTokenFor(bookingId)) ?? null,
    staleTime: Infinity,
  });
}

const guest = (token: string) => ({ header: { 'X-Guest-Token': token } });

// --- Reading ------------------------------------------------------------------------------------

export function useMyBookings(scope: Scope, enabled = true) {
  return useQuery({
    queryKey: bookingKeys.mine(scope),
    queryFn: async () =>
      orThrow(await core.GET('/v1/me/bookings', { params: { query: { scope } } })),
    enabled,
  });
}

export function useBooking({ bookingId, guestToken }: Access, enabled = true) {
  return useQuery({
    enabled,
    queryKey: bookingKeys.one(bookingId),
    queryFn: async () =>
      guestToken
        ? orThrow(await core.GET('/v1/guest/booking', { params: guest(guestToken) }))
        : orThrow(await core.GET('/v1/bookings/{bookingId}', { params: { path: { bookingId } } })),
  });
}

/** The booking a guest token is for (GuestJoin, opened from the SMS link). */
export function useGuestBooking(token: string) {
  return useQuery({
    queryKey: ['bookings', 'guest', token],
    queryFn: async () => orThrow(await core.GET('/v1/guest/booking', { params: guest(token) })),
    retry: false,
  });
}

export function useCancellationQuote({ bookingId, guestToken }: Access, enabled = true) {
  return useQuery({
    enabled,
    queryKey: bookingKeys.quote(bookingId),
    queryFn: async () =>
      guestToken
        ? orThrow(await core.GET('/v1/guest/booking/cancellation', { params: guest(guestToken) }))
        : orThrow(
            await core.GET('/v1/bookings/{bookingId}/cancellation', {
              params: { path: { bookingId } },
            }),
          ),
    // The refund depends on how close the start is.
    staleTime: 0,
  });
}

// --- Booking, cancelling, moving ----------------------------------------------------------------

function useSettle() {
  const queries = useQueryClient();
  return (booking: Booking) => {
    queries.setQueryData(bookingKeys.one(booking.id), booking);
    void queries.invalidateQueries({ queryKey: ['bookings', 'mine'] });
    void queries.invalidateQueries({ queryKey: ['scheduling', 'slots'] });
  };
}

export function useCreateBooking() {
  const settle = useSettle();
  return useMutation({
    mutationFn: async (body: CoreSchemas['BookingRequest']) =>
      orThrow(await core.POST('/v1/bookings', { body })),
    onSuccess: settle,
  });
}

export function useSendGuestCode() {
  return useMutation({
    mutationFn: async (body: { phone: string; appLanguage: 'en' | 'fr' }) => {
      orThrow(await core.POST('/v1/guest/codes', { body }));
    },
  });
}

export function useCreateGuestBooking() {
  const settle = useSettle();
  return useMutation({
    mutationFn: async (body: CoreSchemas['GuestBookingRequest']) => {
      const made = orThrow(await core.POST('/v1/guest/bookings', { body }));
      await rememberGuestBooking(made.booking.id, made.guestToken);
      return made;
    },
    onSuccess: (made) => settle(made.booking),
  });
}

export function useCancelBooking({ bookingId, guestToken }: Access) {
  const settle = useSettle();
  return useMutation({
    mutationFn: async (reason: CancelReason | undefined) => {
      const body = reason ? { reason } : {};
      return guestToken
        ? orThrow(
            await core.POST('/v1/guest/booking/cancellation', { params: guest(guestToken), body }),
          )
        : orThrow(
            await core.POST('/v1/bookings/{bookingId}/cancellation', {
              params: { path: { bookingId } },
              body,
            }),
          );
    },
    onSuccess: settle,
  });
}

export function useRescheduleBooking({ bookingId, guestToken }: Access) {
  const settle = useSettle();
  return useMutation({
    mutationFn: async (holdId: string) =>
      guestToken
        ? orThrow(
            await core.POST('/v1/guest/booking/reschedule', {
              params: guest(guestToken),
              body: { holdId },
            }),
          )
        : orThrow(
            await core.POST('/v1/bookings/{bookingId}/reschedule', {
              params: { path: { bookingId } },
              body: { holdId },
            }),
          ),
    onSuccess: settle,
  });
}
