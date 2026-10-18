import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { core, type CoreSchemas, orThrow } from './core';

export type InboxItem = CoreSchemas['InboxItem'];
export type NotificationSettings = CoreSchemas['NotificationSettings'];

export const messagingKeys = {
  inbox: ['me', 'notifications'] as const,
  settings: ['me', 'notification-settings'] as const,
};

/** The in-app inbox (Notifications), newest first. */
export function useInbox() {
  return useQuery({
    queryKey: messagingKeys.inbox,
    queryFn: async () => orThrow(await core.GET('/v1/me/notifications')),
  });
}

export function useMarkAllRead() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      orThrow(await core.POST('/v1/me/notifications/read'));
    },
    onSuccess: () => queries.invalidateQueries({ queryKey: messagingKeys.inbox }),
  });
}

export function useNotificationSettings() {
  return useQuery({
    queryKey: messagingKeys.settings,
    queryFn: async () => orThrow(await core.GET('/v1/me/notification-settings')),
  });
}

export function useSaveNotificationSettings() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (body: NotificationSettings) =>
      orThrow(await core.PUT('/v1/me/notification-settings', { body })),
    onSuccess: (saved) => queries.setQueryData(messagingKeys.settings, saved),
  });
}
