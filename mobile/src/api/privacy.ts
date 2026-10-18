import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiProblem, core, type CoreSchemas, orThrow } from './core';

export type DataExport = CoreSchemas['DataExport'];

export const privacyKeys = { export: ['me', 'data-export'] as const };

/** The latest copy of my data, or null when none was ever asked for. */
export function useLatestExport() {
  return useQuery({
    queryKey: privacyKeys.export,
    queryFn: async (): Promise<DataExport | null> => {
      try {
        return orThrow(await core.GET('/v1/me/data-exports/latest'));
      } catch (e) {
        if (e instanceof ApiProblem && e.status === 404) return null;
        throw e;
      }
    },
    // While it is being prepared, look again now and then.
    refetchInterval: (query) => (query.state.data?.status === 'PREPARING' ? 10_000 : false),
  });
}

export function useRequestExport() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async () => orThrow(await core.POST('/v1/me/data-exports')),
    onSuccess: (made) => queries.setQueryData(privacyKeys.export, made),
  });
}

/** The copy itself, as text; only its owner gets it (D40). */
export async function downloadExport(exportId: string): Promise<string> {
  const result = await core.GET('/v1/me/data-exports/{exportId}/file', {
    params: { path: { exportId } },
    parseAs: 'text',
  });
  return orThrow(result) as unknown as string;
}

export function useEraseAccount() {
  return useMutation({
    mutationFn: async () => orThrow(await core.POST('/v1/me/erasure', { body: { confirm: true } })),
  });
}
