import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Check } from 'lucide-react-native';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useMe } from '@/api/account';
import { claimGuestResponse } from '@/api/healthCheck';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';

/**
 * Seeker sign-up, done (SeekerReady.dc.html). The paragraph about tracking modules and reminders,
 * and the health check card, describe Phase 2 features, so Phase 1 shows the title and the button.
 */
export default function SignupDone() {
  const { t } = useTranslation();
  const me = useMe();
  // Answers given as a guest become the new account's (D55).
  useEffect(() => {
    void claimGuestResponse().catch(() => undefined);
  }, []);
  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.hero} aria-hidden>
        <View style={styles.disc} />
        <View style={styles.dot} />
        <View style={styles.badge}>
          <Check size={40} strokeWidth={2.4} color={colors.accent} />
        </View>
      </View>
      <Text variant="titleLg" accessibilityRole="header" style={styles.title}>
        {t('signupDone.title', { name: me.data?.displayName ?? '' })}
      </Text>
      <View style={styles.spacer} />
      <Button label={t('signupDone.home')} onPress={() => router.replace('/(seeker)/home')} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    paddingTop: space.space24 * 2,
    paddingHorizontal: space.space24,
    paddingBottom: space.space32,
    gap: space.space24,
    backgroundColor: colors.surfacePage,
  },
  hero: {
    height: 220,
    borderRadius: radius.radius28,
    backgroundColor: colors.brandTint,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  disc: {
    position: 'absolute',
    width: 240,
    height: 240,
    borderRadius: 120,
    left: -100,
    bottom: -120,
    backgroundColor: colors.brandTintStrong,
  },
  dot: {
    position: 'absolute',
    width: 36,
    height: 36,
    borderRadius: 18,
    right: 36,
    top: 30,
    backgroundColor: colors.accent,
  },
  badge: {
    width: 104,
    height: 104,
    borderRadius: 52,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brand,
  },
  title: {
    marginTop: space.space8,
  },
  spacer: {
    flexGrow: 1,
  },
});
