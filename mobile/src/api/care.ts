import type { AiSchemas } from '@solace/api-clients/ai';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ai } from './brief';
import { type CoreSchemas, core, orThrow } from './core';

/** Phase 4 (D57): the session scribe, notes, homework and the companion, and the safety plan. */

export type NoteSummary = AiSchemas['NoteSummary'];
export type Note = AiSchemas['Note'];
export type NoteFormat = AiSchemas['NoteFormat'];
export type NoteSection = AiSchemas['NoteSection'];
export type Homework = AiSchemas['Homework'];
export type HomeworkAssignment = AiSchemas['HomeworkAssignment'];
export type CompanionTurn = AiSchemas['CompanionTurn'];
export type SessionScribe = CoreSchemas['SessionScribe'];
export type SafetyPlan = CoreSchemas['SafetyPlan'];
export type SafetyPlanEdit = CoreSchemas['SafetyPlanEdit'];

// --- The scribe for one session ------------------------------------------------------------------

export function useSessionScribe(bookingId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['scribe', bookingId],
    queryFn: async () =>
      orThrow(
        await core.GET('/v1/bookings/{bookingId}/scribe', { params: { path: { bookingId } } }),
      ),
    enabled,
  });
}

export function useChooseScribe(bookingId: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (on: boolean) =>
      orThrow(
        await core.PUT('/v1/bookings/{bookingId}/scribe', {
          params: { path: { bookingId } },
          body: { on },
        }),
      ),
    onSuccess: (state) => queries.setQueryData(['scribe', bookingId], state),
  });
}

// --- Notes -----------------------------------------------------------------------------------------

export function useNotes(status: 'DRAFT' | 'SIGNED', q: string) {
  return useQuery({
    queryKey: ['notes', status, q],
    queryFn: async () =>
      orThrow(
        await ai.GET('/v1/specialist/notes', {
          params: { query: { status, ...(q ? { q } : {}) } },
        }),
      ),
  });
}

export function useNote(noteId: string) {
  return useQuery({
    queryKey: ['note', noteId],
    queryFn: async () =>
      orThrow(await ai.GET('/v1/specialist/notes/{noteId}', { params: { path: { noteId } } })),
  });
}

function useNoteChange<T>(noteId: string, call: (input: T) => Promise<Note>) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: call,
    onSuccess: (note) => {
      queries.setQueryData(['note', noteId], note);
      void queries.invalidateQueries({ queryKey: ['notes'] });
    },
  });
}

export function useEditNote(noteId: string) {
  return useNoteChange(noteId, async (body: { format: NoteFormat; sections: NoteSection[] }) =>
    orThrow(await ai.PUT('/v1/specialist/notes/{noteId}', { params: { path: { noteId } }, body })),
  );
}

export function useRedraftNote(noteId: string) {
  return useNoteChange(noteId, async (format: NoteFormat) =>
    orThrow(
      await ai.POST('/v1/specialist/notes/{noteId}/redraft', {
        params: { path: { noteId } },
        body: { format },
      }),
    ),
  );
}

export function useSignNote(noteId: string) {
  return useNoteChange(noteId, async (_: void) =>
    orThrow(
      await ai.POST('/v1/specialist/notes/{noteId}/signature', { params: { path: { noteId } } }),
    ),
  );
}

export function useDiscardNote(noteId: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const result = await ai.DELETE('/v1/specialist/notes/{noteId}', {
        params: { path: { noteId } },
      });
      if (!result.response.ok) orThrow(result);
    },
    onSuccess: () => void queries.invalidateQueries({ queryKey: ['notes'] }),
  });
}

// --- Homework and the companion --------------------------------------------------------------------

export function useAssignHomework(bookingId: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: HomeworkAssignment) =>
      orThrow(
        await ai.POST('/v1/specialist/bookings/{bookingId}/homework', {
          params: { path: { bookingId } },
          body,
        }),
      ),
    onSuccess: () => void queries.invalidateQueries({ queryKey: ['homework', bookingId] }),
  });
}

export function useBookingHomework(bookingId: string) {
  return useQuery({
    queryKey: ['homework', bookingId],
    queryFn: async () =>
      orThrow(
        await ai.GET('/v1/specialist/bookings/{bookingId}/homework', {
          params: { path: { bookingId } },
        }),
      ),
  });
}

export function useMyHomework(enabled: boolean) {
  return useQuery({
    queryKey: ['my-homework'],
    queryFn: async () => orThrow(await ai.GET('/v1/me/homework')),
    enabled,
  });
}

export function useCompanion(homeworkId: string, language: string) {
  return useQuery({
    queryKey: ['companion', homeworkId, language],
    queryFn: async () =>
      orThrow(
        await ai.GET('/v1/me/homework/{homeworkId}/companion', {
          params: { path: { homeworkId } },
        }),
      ),
  });
}

export function useAnswerCompanion(homeworkId: string, language: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (answer: string) =>
      orThrow(
        await ai.POST('/v1/me/homework/{homeworkId}/companion', {
          params: { path: { homeworkId } },
          body: { answer },
        }),
      ),
    onSuccess: (turn) => {
      queries.setQueryData(['companion', homeworkId, language], turn);
      void queries.invalidateQueries({ queryKey: ['my-homework'] });
    },
  });
}

// --- Safety plan -----------------------------------------------------------------------------------

export async function fetchSafetyPlan(): Promise<SafetyPlan | null> {
  const result = await core.GET('/v1/me/safety-plan');
  if (result.response.status === 204) return null;
  return orThrow(result);
}

export async function saveSafetyPlan(body: SafetyPlanEdit): Promise<SafetyPlan> {
  return orThrow(await core.PUT('/v1/me/safety-plan', { body }));
}
