import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { accessToken } from '@/auth/auth';
import { env } from '@/config/env';
import i18n from '@/i18n';

import { accountKeys } from './account';
import { ApiProblem, core, type CoreSchemas, orThrow } from './core';

export type SpecialistApplication = CoreSchemas['SpecialistApplication'];
export type Practice = CoreSchemas['Practice'];
export type SpecialistLanguage = CoreSchemas['SpecialistLanguage'];
export type Offering = CoreSchemas['Offering'];
export type PayoutDetails = CoreSchemas['PayoutDetails'];
export type WeeklyHours = CoreSchemas['WeeklyHours'];
export type DocumentKind = CoreSchemas['DocumentKind'];
export type SpecialistSignupRequest = CoreSchemas['SpecialistSignupRequest'];

export const specialistKeys = {
  application: ['me', 'specialist-application'] as const,
  weeklyHours: ['me', 'weekly-hours'] as const,
  professions: (language: string) => ['catalogue', 'professions', language] as const,
};

/** Step 1 (SpecSignup). Core answers 202 whether or not the number is new. */
export async function startSpecialistSignup(body: SpecialistSignupRequest): Promise<void> {
  orThrow(await core.POST('/v1/specialist-signups', { body }));
}

export async function fetchApplication(): Promise<SpecialistApplication> {
  return orThrow(await core.GET('/v1/me/specialist-application'));
}

export function useApplication() {
  return useQuery({ queryKey: specialistKeys.application, queryFn: fetchApplication });
}

/** Professions, labelled in the app language. */
export function useProfessions(language: string) {
  return useQuery({
    queryKey: specialistKeys.professions(language),
    queryFn: async () => orThrow(await core.GET('/v1/catalogue/professions')),
    staleTime: 60 * 60_000,
  });
}

/** Saves one step; every step answers with the whole application. */
function useStep<T>(save: (body: T) => Promise<SpecialistApplication>) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: save,
    onSuccess: (application) => {
      queries.setQueryData(specialistKeys.application, application);
      queries.invalidateQueries({ queryKey: accountKeys.me });
    },
  });
}

export function useSavePractice() {
  return useStep(async (body: Practice) =>
    orThrow(await core.PUT('/v1/me/specialist-application/practice', { body })),
  );
}

export function useSaveLanguages() {
  return useStep(
    async (languages: { code: string; proficiency: SpecialistLanguage['proficiency'] }[]) =>
      orThrow(await core.PUT('/v1/me/specialist-application/languages', { body: { languages } })),
  );
}

export function useSaveServices() {
  return useStep(async (offerings: Offering[]) =>
    orThrow(await core.PUT('/v1/me/specialist-application/services', { body: { offerings } })),
  );
}

export function useSavePayout() {
  return useStep(async (body: PayoutDetails) =>
    orThrow(await core.PUT('/v1/me/specialist-application/payout', { body })),
  );
}

/** The specialist checks the bio Solace drafted (D30), with corrections if any. */
export function useConfirmBio() {
  return useStep(async (text: string | undefined) =>
    orThrow(
      await core.POST('/v1/me/specialist-application/bio-confirmation', {
        body: text ? { text } : {},
      }),
    ),
  );
}

export function useSubmitApplication() {
  return useStep(async (_: void) =>
    orThrow(await core.POST('/v1/me/specialist-application/submission')),
  );
}

/** A file picked on the device, as React Native's FormData takes it. */
export type PickedFile = { uri: string; name: string; mimeType?: string };

/**
 * Uploads one document kind (IDENTITY may have two files). Multipart goes through fetch directly:
 * React Native's FormData takes `{ uri, name, type }` parts, which the generated client does not
 * model.
 */
export function useUploadDocument() {
  return useStep(async ({ kind, files }: { kind: DocumentKind; files: PickedFile[] }) => {
    const form = new FormData();
    for (const file of files) {
      form.append('file', {
        uri: file.uri,
        name: file.name,
        type: file.mimeType ?? 'application/octet-stream',
      } as unknown as Blob);
    }
    const token = await accessToken();
    const response = await fetch(
      `${env.apiUrl}/core/v1/me/specialist-application/documents/${kind}`,
      {
        method: 'PUT',
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          'Accept-Language': i18n.language || 'en',
        },
        body: form,
      },
    );
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as {
        code?: string;
        title?: string;
      };
      throw new ApiProblem(response.status, problem.code, problem.title);
    }
    return (await response.json()) as SpecialistApplication;
  });
}

export function useWeeklyHours() {
  return useQuery({
    queryKey: specialistKeys.weeklyHours,
    queryFn: async () => orThrow(await core.GET('/v1/me/weekly-hours')),
  });
}

export function useSaveWeeklyHours() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: WeeklyHours) =>
      orThrow(await core.PUT('/v1/me/weekly-hours', { body })),
    onSuccess: (saved) => {
      queries.setQueryData(specialistKeys.weeklyHours, saved);
      queries.invalidateQueries({ queryKey: specialistKeys.application });
    },
  });
}

/** The seven steps in order, with the screen each one lives on. */
export const SPECIALIST_STEPS = 7;

const STEP_ROUTES = [
  ['practice', '/specialist-signup/practice'],
  ['languages', '/specialist-signup/languages'],
  ['documents', '/specialist-signup/documents'],
  ['services', '/specialist-signup/services'],
  ['hours', '/specialist-signup/hours'],
  ['payout', '/specialist-signup/payout'],
] as const;

export type SpecialistRoute = (typeof STEP_ROUTES)[number][1] | '/specialist-signup/review';

/** The next step to fill in: the first unfinished one, else the last (payouts and submit). */
export function stepRoute(application: SpecialistApplication): SpecialistRoute {
  const next = STEP_ROUTES.find(([step]) => !application.steps[step]);
  return next ? next[1] : '/specialist-signup/payout';
}

/**
 * Where an applicant lands on opening the app: the review screen once submitted, or when changes
 * were requested (to read the reviewer's reason first); otherwise the next step to fill in.
 */
export function resumeRoute(application: SpecialistApplication): SpecialistRoute {
  if (application.status !== 'DRAFT' || application.reviewNote) return '/specialist-signup/review';
  return stepRoute(application);
}

/** Whether the specialist takes new bookings (SpecialistToday.dc.html). */
export function useAcceptingBookings() {
  return useQuery({
    queryKey: ['accepting-bookings'],
    queryFn: async () => orThrow(await core.GET('/v1/me/accepting-bookings')),
  });
}

export function useSetAcceptingBookings() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (accepting: boolean) =>
      orThrow(await core.PUT('/v1/me/accepting-bookings', { body: { accepting } })),
    onSuccess: (data) => queries.setQueryData(['accepting-bookings'], data),
  });
}
