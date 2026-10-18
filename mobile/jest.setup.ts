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
