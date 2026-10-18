import { Platform } from 'react-native';
import RNCallKeep from 'react-native-callkeep';

let ready = false;

/**
 * CallKit (iOS) and ConnectionService (Android) through react-native-callkeep (System Design,
 * Calls), so a session holds the phone's audio like a call and shows on the lock screen. Incoming
 * ringing needs VoIP push, which arrives with notifications (epic 9). Nothing here may stop a call:
 * every step is best-effort.
 */
export async function setUpCallKeep(appName: string): Promise<void> {
  if (ready) return;
  try {
    await RNCallKeep.setup({
      ios: { appName, supportsVideo: true, maximumCallGroups: '1', maximumCallsPerCallGroup: '1' },
      android: {
        alertTitle: appName,
        alertDescription: appName,
        cancelButton: 'Cancel',
        okButton: 'OK',
        additionalPermissions: [],
        selfManaged: true,
        foregroundService: {
          channelId: 'care.solace.app.session',
          channelName: appName,
          notificationTitle: appName,
        },
      },
    });
    if (Platform.OS === 'android') RNCallKeep.setAvailable(true);
    ready = true;
  } catch {
    // No native module (tests, Expo Go): sessions still work without the system call UI.
  }
}

/**
 * Tells the system a session started. The handle is the app's name, never the other person's:
 * the lock screen and call history must not reveal who the session is with (CLAUDE.md, privacy).
 */
export function callStarted(callId: string, appName: string, video: boolean): void {
  try {
    RNCallKeep.startCall(callId, appName, appName, 'generic', video);
    RNCallKeep.setCurrentCallActive(callId);
  } catch {
    // Best-effort.
  }
}

export function callEnded(callId: string): void {
  try {
    RNCallKeep.endCall(callId);
  } catch {
    // Best-effort.
  }
}
