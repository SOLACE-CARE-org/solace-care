import { colors, space } from '@solace/tokens/native';
import * as LocalAuthentication from 'expo-local-authentication';
import { router, usePathname } from 'expo-router';
import { Lock } from 'lucide-react-native';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AppState, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useAuth } from '@/auth/auth';
import { useSession } from '@/state/session';

import { Button } from './Button';
import { CrisisButton } from './CrisisButton';
import { Text } from './Text';

/** Asks the phone's own lock (PIN, fingerprint or face) to unlock; true when it did. */
export async function unlockWithDevice(prompt: string): Promise<boolean> {
  try {
    const result = await LocalAuthentication.authenticateAsync({
      promptMessage: prompt,
      disableDeviceFallback: false,
    });
    return result.success;
  } catch {
    return false;
  }
}

/** True when the phone has a screen lock or biometrics the app can ask for. */
export async function deviceCanLock(): Promise<boolean> {
  try {
    return (
      (await LocalAuthentication.getEnrolledLevelAsync()) !== LocalAuthentication.SecurityLevel.NONE
    );
  } catch {
    return false;
  }
}

/**
 * App lock (Settings.dc.html): with it on, Solace asks for the phone's PIN or fingerprint when it
 * opens and each time it comes back to the front. Urgent help stays one tap away while locked: the
 * lock screen has the crisis button, and the crisis screen is never covered.
 */
export function AppLockGate() {
  const { t } = useTranslation();
  const enabled = useSession((s) => s.appLock);
  const signedIn = useAuth((s) => s.status === 'signedIn');
  const pathname = usePathname();
  const [locked, setLocked] = useState(enabled && signedIn);
  const state = useRef(AppState.currentState);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (next) => {
      if (state.current === 'background' && next === 'active' && enabled && signedIn) {
        setLocked(true);
      }
      state.current = next;
    });
    return () => sub.remove();
  }, [enabled, signedIn]);

  const unlock = useCallback(async () => {
    if (await unlockWithDevice(t('appLock.prompt'))) setLocked(false);
  }, [t]);

  if (!locked || !enabled || !signedIn || pathname === '/crisis') return null;
  return (
    <View style={StyleSheet.absoluteFill}>
      <SafeAreaView style={styles.screen}>
        <View style={styles.center}>
          <View style={styles.icon} aria-hidden>
            <Lock size={28} strokeWidth={1.8} color={colors.brand} />
          </View>
          <Text variant="titleMd" accessibilityRole="header">
            {t('appLock.title')}
          </Text>
          <Button label={t('appLock.unlock')} onPress={() => void unlock()} />
        </View>
        <CrisisButton label={t('crisis.button')} onPress={() => router.push('/crisis')} />
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    padding: space.space20,
    gap: space.space16,
    backgroundColor: colors.surfacePage,
  },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: space.space16 },
  icon: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandTint,
  },
});
