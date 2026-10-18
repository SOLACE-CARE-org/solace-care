import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { type Access, bookingKeys } from './bookings';
import { core, type CoreSchemas, orThrow } from './core';

export type Payment = CoreSchemas['Payment'];
export type PayMethod = 'MTN_MOMO' | 'ORANGE_MONEY';
export type Earnings = CoreSchemas['Earnings'];

export const paymentKeys = {
  latest: (bookingId: string) => ['payments', bookingId] as const,
  earnings: ['me', 'earnings'] as const,
};

const guest = (token: string) => ({ header: { 'X-Guest-Token': token } });

/** Asks the wallet for the price (Approve payment). The phone defaults to the proven one. */
export async function startPayment(
  { bookingId, guestToken }: Access,
  body: { method: PayMethod; phone?: string },
): Promise<Payment> {
  return guestToken
    ? orThrow(await core.POST('/v1/guest/booking/payments', { params: guest(guestToken), body }))
    : orThrow(
        await core.POST('/v1/bookings/{bookingId}/payments', {
          params: { path: { bookingId } },
          body,
        }),
      );
}

export function useStartPayment(access: Access) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (body: { method: PayMethod; phone?: string }) => startPayment(access, body),
    onSuccess: (payment) => queries.setQueryData(paymentKeys.latest(access.bookingId), payment),
  });
}

async function fetchLatest({ bookingId, guestToken }: Access, check: boolean): Promise<Payment> {
  return guestToken
    ? orThrow(
        await core.GET('/v1/guest/booking/payment', {
          params: { ...guest(guestToken), query: { check } },
        }),
      )
    : orThrow(
        await core.GET('/v1/bookings/{bookingId}/payment', {
          params: { path: { bookingId }, query: { check } },
        }),
      );
}

/**
 * The latest payment, asked again every few seconds while it waits for the wallet holder: the
 * provider's webhook confirms it on the server, this only watches.
 */
export function useLatestPayment(access: Access, enabled = true) {
  return useQuery({
    queryKey: paymentKeys.latest(access.bookingId),
    queryFn: () => fetchLatest(access, false),
    enabled,
    staleTime: 0,
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 5_000 : false),
  });
}

/** "I've approved it": asks Core to check with the provider now. */
export function useCheckPayment(access: Access) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: () => fetchLatest(access, true),
    onSuccess: (payment) => {
      queries.setQueryData(paymentKeys.latest(access.bookingId), payment);
      if (payment.status === 'COMPLETE') {
        void queries.invalidateQueries({ queryKey: bookingKeys.one(access.bookingId) });
      }
    },
  });
}

/** Earnings and payouts (specialist app). */
export function useEarnings() {
  return useQuery({
    queryKey: paymentKeys.earnings,
    queryFn: async () => orThrow(await core.GET('/v1/me/earnings')),
  });
}
