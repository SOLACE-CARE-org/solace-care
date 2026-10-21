import {
  BricolageGrotesque_500Medium,
  BricolageGrotesque_600SemiBold,
  BricolageGrotesque_700Bold,
} from '@expo-google-fonts/bricolage-grotesque';
import {
  Figtree_400Regular,
  Figtree_500Medium,
  Figtree_600SemiBold,
  Figtree_700Bold,
} from '@expo-google-fonts/figtree';
import { registerGlobals } from '@livekit/react-native';
import { colors } from '@solace/tokens/native';
import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { queryClient } from '@/api/query-client';
import { useAuth } from '@/auth/auth';
import { deviceLanguage, initI18n } from '@/i18n';
import { openTappedNotifications, registerIfAllowed } from '@/notifications/push';
import { useSession } from '@/state/session';
import { core } from '@/api/core';
import { refreshFlags } from '@/config/flags';
import { CRISIS_COUNTRY, refreshEmergencyNumbers } from '@/content/crisis';
import { AppLockGate } from '@/ui/AppLockGate';

// Keep the native splash (brand-splash with the lockup) up until fonts, strings and the saved
// session are ready, so the first frame is already in the right language and typefaces.
SplashScreen.preventAutoHideAsync();

// WebRTC for LiveKit's client (sessions, epic 7).
registerGlobals();

export default function RootLayout() {
  // Bundled with the app from the Expo Google Fonts packages: no font CDN at runtime.
  const [fontsLoaded, fontError] = useFonts({
    BricolageGrotesque_500Medium,
    BricolageGrotesque_600SemiBold,
    BricolageGrotesque_700Bold,
    Figtree_400Regular,
    Figtree_500Medium,
    Figtree_600SemiBold,
    Figtree_700Bold,
  });
  const hydrated = useSessionHydrated();
  const authKnown = useAuth((s) => s.status !== 'unknown');
  const language = useSession((s) => s.language);
  const [stringsReady, setStringsReady] = useState(false);

  // Read the stored tokens (secure storage) before the first screen decides where to go.
  useEffect(() => {
    useAuth
      .getState()
      .load()
      .then(() => {
        // Each start: keep Core's push token and time zone for this device fresh (D38).
        if (useAuth.getState().status === 'signedIn') void registerIfAllowed();
      })
      .catch(() => useAuth.getState().signOut());
    // Feature flags staff set in the admin portal (D42); the stored ones serve offline.
    void refreshFlags(async () => (await core.GET('/v1/config')).data?.flags);
    // Published crisis numbers, stored for Urgent help, which never waits on the network (D44).
    void refreshEmergencyNumbers(async () => {
      const { data } = await core.GET('/v1/content', {
        params: { query: { type: 'CRISIS_RESOURCE', country: CRISIS_COUNTRY } },
      });
      return data?.[0]?.numbers;
    });
    return openTappedNotifications();
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    initI18n(language ?? deviceLanguage()).then(() => setStringsReady(true));
  }, [hydrated, language]);

  const ready = (fontsLoaded || fontError !== null) && stringsReady && authKnown;

  useEffect(() => {
    if (ready) SplashScreen.hide();
  }, [ready]);

  if (!ready) return null;

  return (
    <SafeAreaProvider>
      <QueryClientProvider client={queryClient}>
        <StatusBar style="dark" />
        <Stack
          screenOptions={{
            headerShown: false,
            contentStyle: { backgroundColor: colors.surfacePage },
          }}
        >
          <Stack.Screen name="splash" options={{ animation: 'fade' }} />
          <Stack.Screen
            name="session/change"
            options={{
              presentation: 'transparentModal',
              animation: 'slide_from_bottom',
              contentStyle: { backgroundColor: 'transparent' },
            }}
          />
        </Stack>
        <AppLockGate />
      </QueryClientProvider>
    </SafeAreaProvider>
  );
}

/** True once the persisted session (language, role) has been read from storage. */
function useSessionHydrated() {
  return useSyncExternalStore(
    (changed) => useSession.persist.onFinishHydration(changed),
    () => useSession.persist.hasHydrated(),
  );
}
