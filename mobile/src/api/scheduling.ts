import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { core, type CoreSchemas, orThrow } from './core';

export type Slots = CoreSchemas['Slots'];
export type SlotHold = CoreSchemas['SlotHold'];
export type Calendar = CoreSchemas['Calendar'];
export type TimeOffReason = CoreSchemas['TimeOffReason'];

export const schedulingKeys = {
  slots: (id: string, modality: string) => ['scheduling', 'slots', id, modality] as const,
  calendar: (from: string) => ['me', 'calendar', from] as const,
};

/** A week of starts for Choose a session; taken ones come marked. Open to guests. */
export function useSlots(specialistId: string, modality: CoreSchemas['Modality']) {
  return useQuery({
    queryKey: schedulingKeys.slots(specialistId, modality),
    queryFn: async () =>
      orThrow(
        await core.GET('/v1/catalogue/specialists/{id}/slots', {
          params: { path: { id: specialistId }, query: { modality, days: 7 } },
        }),
      ),
    // Starts go quickly: always ask again when the screen comes back.
    staleTime: 0,
  });
}

/** Holds a start for 10 minutes while the seeker checks out. */
export function useHoldSlot() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: CoreSchemas['HoldRequest']) =>
      orThrow(await core.POST('/v1/slot-holds', { body })),
    onSettled: (_hold, _error, body) =>
      queries.invalidateQueries({ queryKey: schedulingKeys.slots(body.specialistId, body.modality) }),
  });
}

export async function releaseHold(holdId: string): Promise<void> {
  await core.DELETE('/v1/slot-holds/{holdId}', { params: { path: { holdId } } });
}

/** The specialist's week (SpecCalendar). */
export function useCalendar(from: string) {
  return useQuery({
    queryKey: schedulingKeys.calendar(from),
    queryFn: async () =>
      orThrow(await core.GET('/v1/me/calendar', { params: { query: { from, days: 7 } } })),
  });
}

export function useBlockTimeOff() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: CoreSchemas['TimeOffRequest']) =>
      orThrow(await core.POST('/v1/me/time-off', { body })),
    onSuccess: () => queries.invalidateQueries({ queryKey: ['me', 'calendar'] }),
  });
}

export function useRemoveTimeOff() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (timeOffId: string) => {
      await core.DELETE('/v1/me/time-off/{timeOffId}', { params: { path: { timeOffId } } });
    },
    onSuccess: () => queries.invalidateQueries({ queryKey: ['me', 'calendar'] }),
  });
}
