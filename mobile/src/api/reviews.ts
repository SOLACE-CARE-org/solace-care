import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { type Access, bookingKeys } from './bookings';
import { core, type CoreSchemas, orThrow } from './core';

export type PublicReview = CoreSchemas['PublicReview'];
export type Rating = CoreSchemas['Rating'];
export type RatingTag = 'LISTENED' | 'PRACTICAL_TOOLS' | 'CALL_QUALITY' | 'ON_TIME';
export type RatingBody = { stars: number; tags?: RatingTag[]; text?: string; privateNote?: string };

export const reviewKeys = {
  mine: ['me', 'reviews'] as const,
  ofSpecialist: (id: string) => ['catalogue', 'reviews', id] as const,
};

/** Rate your session: once per completed booking (D37). */
export function useRateSession({ bookingId, guestToken }: Access) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: RatingBody) => {
      const result = guestToken
        ? await core.POST('/v1/guest/booking/rating', {
            params: { header: { 'X-Guest-Token': guestToken } },
            body,
          })
        : await core.POST('/v1/bookings/{bookingId}/rating', {
            params: { path: { bookingId } },
            body,
          });
      orThrow(result);
    },
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: bookingKeys.one(bookingId) });
      void queries.invalidateQueries({ queryKey: ['bookings', 'mine'] });
    },
  });
}

/** A listed specialist's published reviews (profile). */
export function useSpecialistReviews(specialistId: string) {
  return useQuery({
    queryKey: reviewKeys.ofSpecialist(specialistId),
    queryFn: async () =>
      orThrow(
        await core.GET('/v1/catalogue/specialists/{id}/reviews', {
          params: { path: { id: specialistId }, query: { limit: 10 } },
        }),
      ),
  });
}

/** The specialist's own rating and published reviews. */
export function useMyReviews() {
  return useQuery({
    queryKey: reviewKeys.mine,
    queryFn: async () => orThrow(await core.GET('/v1/me/reviews')),
  });
}

export function useReplyToReview() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async ({ reviewId, text }: { reviewId: string; text: string }) =>
      orThrow(
        await core.PUT('/v1/me/reviews/{reviewId}/reply', {
          params: { path: { reviewId } },
          body: { text },
        }),
      ),
    onSuccess: () => queries.invalidateQueries({ queryKey: reviewKeys.mine }),
  });
}
