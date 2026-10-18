import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { core, type CoreSchemas, orThrow } from './core';

export type SpecialistCard = CoreSchemas['SpecialistCard'];
export type SpecialistProfile = CoreSchemas['SpecialistProfile'];
export type Modality = CoreSchemas['Modality'];

const PAGE = 20;

export type SearchFilters = { language: string; modality?: Modality; q?: string };

export const catalogueKeys = {
  search: (filters: SearchFilters, appLanguage: string) =>
    ['catalogue', 'specialists', filters, appLanguage] as const,
  profile: (id: string, appLanguage: string) => ['catalogue', 'specialist', id, appLanguage] as const,
};

/** Find a specialist: the session language first, pages of 20. Open to guests. */
export function useSpecialistSearch(filters: SearchFilters, appLanguage: string) {
  return useInfiniteQuery({
    queryKey: catalogueKeys.search(filters, appLanguage),
    initialPageParam: 0,
    queryFn: async ({ pageParam }) =>
      orThrow(
        await core.GET('/v1/catalogue/specialists', {
          params: {
            query: {
              language: filters.language,
              modality: filters.modality,
              q: filters.q?.trim() || undefined,
              limit: PAGE,
              offset: pageParam,
            },
          },
        }),
      ),
    getNextPageParam: (last, pages) => (last.hasMore ? pages.length * PAGE : undefined),
    // A seeker comes back to the list after opening a profile: keep it a little while.
    staleTime: 60_000,
  });
}

export function useSpecialistProfile(id: string, appLanguage: string) {
  return useQuery({
    queryKey: catalogueKeys.profile(id, appLanguage),
    queryFn: async () =>
      orThrow(await core.GET('/v1/catalogue/specialists/{id}', { params: { path: { id } } })),
  });
}
