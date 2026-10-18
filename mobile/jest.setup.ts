// AsyncStorage has no native module under Jest.
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);

// Secure storage (Keychain, Keystore) has no native module under Jest: an in-memory stand-in.
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    setItemAsync: jest.fn(async (key: string, value: string) => void store.set(key, value)),
    deleteItemAsync: jest.fn(async (key: string) => void store.delete(key)),
  };
});

// Calls (epic 7): WebRTC and the system call UI have no native modules under Jest.
jest.mock('@livekit/react-native', () => ({
  registerGlobals: jest.fn(),
  AudioSession: {
    startAudioSession: jest.fn(async () => undefined),
    stopAudioSession: jest.fn(async () => undefined),
    selectAudioOutput: jest.fn(async () => undefined),
  },
  VideoTrack: () => null,
}));
jest.mock('react-native-callkeep', () => ({
  __esModule: true,
  default: {
    setup: jest.fn(async () => undefined),
    setAvailable: jest.fn(),
    startCall: jest.fn(),
    setCurrentCallActive: jest.fn(),
    endCall: jest.fn(),
  },
}));

// Notifications (epic 9): no push service under Jest.
jest.mock('expo-notifications', () => ({
  setNotificationHandler: jest.fn(),
  requestPermissionsAsync: jest.fn(async () => ({ status: 'granted' })),
  getPermissionsAsync: jest.fn(async () => ({ status: 'granted' })),
  getExpoPushTokenAsync: jest.fn(async () => ({ data: 'ExponentPushToken[test]' })),
  addNotificationResponseReceivedListener: jest.fn(() => ({ remove: jest.fn() })),
}));

// Notifications (epic 9): no push service under Jest.
jest.mock('expo-notifications', () => ({
  setNotificationHandler: jest.fn(),
  requestPermissionsAsync: jest.fn(async () => ({ status: 'granted' })),
  getPermissionsAsync: jest.fn(async () => ({ status: 'granted' })),
  getExpoPushTokenAsync: jest.fn(async () => ({ data: 'ExponentPushToken[test]' })),
  addNotificationResponseReceivedListener: jest.fn(() => ({ remove: jest.fn() })),
}));

// Privacy (epic 11): files and the share sheet have no native modules under Jest.
jest.mock('expo-sharing', () => ({ shareAsync: jest.fn(async () => undefined) }));
jest.mock('expo-file-system', () => {
  const written: Record<string, string> = {};
  class File {
    uri: string;
    constructor(_dir: unknown, name: string) {
      this.uri = `file:///cache/${name}`;
    }
    get exists() {
      return this.uri in written;
    }
    delete() {
      delete written[this.uri];
    }
    write(text: string) {
      written[this.uri] = text;
    }
  }
  return { File, Paths: { cache: 'cache' }, __written: written };
});

// Offline tracking (Phase 2, epic 9): no SQLite or network monitor under Jest. The pending store
// is an in-memory stand-in with the same calls.
jest.mock('@/tracking/store', () => {
  const rows = new Map<string, { clientEntryId: string; body: string; savedAt: string }>();
  const cache = new Map<string, string>();
  return {
    pendingStore: {
      put: jest.fn(async (list: { clientEntryId: string; body: string; savedAt: string }[]) => {
        for (const r of list) rows.set(r.clientEntryId, r);
      }),
      all: jest.fn(async () => [...rows.values()]),
      remove: jest.fn(async (ids: string[]) => {
        for (const id of ids) rows.delete(id);
      }),
      clear: jest.fn(async () => rows.clear()),
    },
    cacheStore: {
      get: jest.fn(async (key: string) => cache.get(key) ?? null),
      set: jest.fn(async (key: string, body: string) => void cache.set(key, body)),
      clear: jest.fn(async () => cache.clear()),
    },
  };
});
jest.mock('@react-native-community/netinfo', () =>
  require('@react-native-community/netinfo/jest/netinfo-mock.js'),
);

// expo-crypto has no native module under Jest: Node's crypto stands in.
jest.mock('expo-crypto', () => {
  const node = jest.requireActual('crypto') as {
    randomUUID: () => string;
    randomBytes: (n: number) => Uint8Array;
  };
  return {
    randomUUID: () => node.randomUUID(),
    getRandomBytesAsync: async (n: number) => new Uint8Array(node.randomBytes(n)),
  };
});
