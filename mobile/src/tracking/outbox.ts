import AsyncStorage from '@react-native-async-storage/async-storage';
import NetInfo from '@react-native-community/netinfo';
import * as Crypto from 'expo-crypto';
import { create } from 'zustand';

import { orThrow } from '@/api/core';
import { queryClient } from '@/api/query-client';
import { type PushEntry, tracking, trackingKeys } from '@/api/tracking';

import { cacheStore, pendingStore } from './store';

/** At most this many entries per push (contracts: PushRequest). */
const BATCH = 500;
const DEVICE_KEY = 'solace.tracking.device';

/** What the Track tab needs to know about an entry still on the phone; never its value. */
export interface PendingMeta {
  clientEntryId: string;
  element: string;
  observedOn: string;
  savedAt: string;
}

type OutboxState = {
  pending: PendingMeta[];
  syncing: boolean;
  refresh: () => Promise<void>;
};

export const useOutbox = create<OutboxState>()((set) => ({
  pending: [],
  syncing: false,
  refresh: async () => {
    const rows = await pendingStore.all();
    set({
      pending: rows.map((r) => {
        const e = JSON.parse(r.body) as PushEntry;
        return {
          clientEntryId: r.clientEntryId,
          element: e.element.code,
          observedOn: e.observedOn,
          savedAt: r.savedAt,
        };
      }),
    });
  },
}));

async function deviceId(): Promise<string> {
  const known = await AsyncStorage.getItem(DEVICE_KEY);
  if (known) return known;
  const fresh = Crypto.randomUUID();
  await AsyncStorage.setItem(DEVICE_KEY, fresh);
  return fresh;
}

export function newEntryId(): string {
  return Crypto.randomUUID();
}

/**
 * Saves entries on the phone first, then tries to send them. Logging never waits for the network
 * (Tracking Service Spec, offline first); the push is replay-safe on clientEntryId.
 */
export async function saveEntries(entries: PushEntry[]): Promise<void> {
  const savedAt = new Date().toISOString();
  await pendingStore.put(
    entries.map((e) => ({ clientEntryId: e.clientEntryId, body: JSON.stringify(e), savedAt })),
  );
  await useOutbox.getState().refresh();
  void flush();
}

let running: Promise<boolean> | null = null;

/** Sends what is waiting. True when nothing is left on the phone. */
export function flush(): Promise<boolean> {
  running ??= send().finally(() => {
    running = null;
  });
  return running;
}

async function send(): Promise<boolean> {
  const rows = await pendingStore.all();
  if (rows.length === 0) return true;
  useOutbox.setState({ syncing: true });
  try {
    const device = await deviceId();
    for (let i = 0; i < rows.length; i += BATCH) {
      const batch = rows.slice(i, i + BATCH);
      const result = orThrow(
        await tracking.POST('/v1/tracking/sync/push', {
          body: {
            deviceId: device,
            entries: batch.map((r) => JSON.parse(r.body) as PushEntry),
            deletions: [],
          },
        }),
      );
      // A rejected entry can never be accepted (unknown element, bad value, a future day): it is
      // dropped rather than retried for ever.
      await pendingStore.remove([
        ...result.accepted.map((a) => a.clientEntryId),
        ...result.rejected.map((r) => r.clientEntryId),
      ]);
    }
    // Screens refetch in the background; sending is done once the phone's queue is empty.
    void queryClient.invalidateQueries({ queryKey: trackingKeys.observations });
    void queryClient.invalidateQueries({ queryKey: trackingKeys.cycles });
    void queryClient.invalidateQueries({ queryKey: ['tracking', 'recommendations'] });
    return true;
  } catch {
    // Offline or refused: everything stays on the phone for the next try.
    return false;
  } finally {
    useOutbox.setState({ syncing: false });
    await useOutbox.getState().refresh();
  }
}

let unsubscribe: (() => void) | null = null;

/** Sends waiting entries whenever the phone comes back online. */
export function startSync(): void {
  if (unsubscribe) return;
  void useOutbox.getState().refresh();
  unsubscribe = NetInfo.addEventListener((state) => {
    if (state.isConnected && state.isInternetReachable !== false) void flush();
  });
}

/** Signing out: try to send, then leave nothing logged on the phone. */
export async function forgetOnSignOut(): Promise<void> {
  await flush().catch(() => false);
  await pendingStore.clear();
  await cacheStore.clear();
  useOutbox.setState({ pending: [] });
}
