import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { accountKeys } from './account';
import { ApiProblem, core, type CoreSchemas, orThrow } from './core';

export type PublishedContent = CoreSchemas['PublishedContent'];
export type LegalAcceptance = CoreSchemas['LegalAcceptance'];

/** The app's legal routes and the content they show (D44). */
export const LEGAL_SLUGS: Record<string, string> = {
  terms: 'terms',
  privacy: 'privacy-notice',
  'specialist-terms': 'specialist-terms',
  'code-of-conduct': 'code-of-conduct',
};

/** A published item in the app language; null while it has never been published. */
export function useContent(slug: string, language: string) {
  return useQuery({
    queryKey: ['content', slug, language],
    queryFn: async (): Promise<PublishedContent | null> => {
      try {
        return orThrow(await core.GET('/v1/content/{slug}', { params: { path: { slug } } }));
      } catch (e) {
        if (e instanceof ApiProblem && e.status === 404) return null;
        throw e;
      }
    },
  });
}

/** Records that the seeker accepted the current version of a legal text. */
export function useAcceptLegal() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (accepted: LegalAcceptance[]) =>
      orThrow(await core.POST('/v1/me/legal-acceptance', { body: { accepted } })),
    onSuccess: () => queries.invalidateQueries({ queryKey: accountKeys.me }),
  });
}
