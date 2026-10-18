import Constants from 'expo-constants';
import * as Notifications from 'expo-notifications';
import { router } from 'expo-router';
import { Platform } from 'react-native';

import { core } from '@/api/core';
import { useAuth } from '@/auth/auth';
import { useSession } from '@/state/session';

let token: string | null = null;

/** Pushes show while the app is open too; the copy is neutral (notification-copy.md). */
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

/** Asks once (Turn on reminders), then registers this device with Core. */
export async function askAndRegister(): Promise<boolean> {
  useSession.getState().askedReminders();
  try {
    const { status } = await Notifications.requestPermissionsAsync();
    if (status !== 'granted') return false;
    await register();
    return true;
  } catch {
    return false;
  }
}

/** Registers when the person already allowed notifications (each start, after sign-in). */
export async function registerIfAllowed(): Promise<void> {
  try {
    const { status } = await Notifications.getPermissionsAsync();
    if (status === 'granted') await register();
  } catch {
    // Push is a convenience: nothing depends on it.
  }
}

export async function pushAllowed(): Promise<boolean> {
  try {
    return (await Notifications.getPermissionsAsync()).status === 'granted';
  } catch {
    return false;
  }
}

async function register(): Promise<void> {
  const projectId =
    Constants.expoConfig?.extra?.eas?.projectId ?? Constants.easConfig?.projectId ?? undefined;
  token = (await Notifications.getExpoPushTokenAsync(projectId ? { projectId } : undefined)).data;
  await core.PUT('/v1/me/devices', {
    body: {
      token,
      platform: Platform.OS === 'ios' ? 'IOS' : 'ANDROID',
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    },
  });
}

/** Signs out after telling Core to stop pushing to this device (D38). */
export async function signOut(): Promise<void> {
  if (token) {
    try {
      await core.DELETE('/v1/me/devices/{token}', { params: { path: { token } } });
    } catch {
      // Signing out must not fail on this.
    }
    token = null;
  }
  await useAuth.getState().signOut();
}

/** Tapping a push about a booking opens it. */
export function openTappedNotifications(): () => void {
  const subscription = Notifications.addNotificationResponseReceivedListener((response) => {
    const bookingId = response.notification.request.content.data?.bookingId;
    if (typeof bookingId === 'string') {
      router.push({ pathname: '/session/[id]', params: { id: bookingId } });
    }
  });
  return () => subscription.remove();
}
