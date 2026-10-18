import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { type Href, router, useLocalSearchParams } from 'expo-router';
import { BellRing } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { askAndRegister } from '@/notifications/push';
import { useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';

/**
 * Turn on reminders? (AllowReminders.dc.html), once per device after the first sign-in. The
 * preview shows the neutral wording a lock screen will carry (notification-copy.md).
 */
export default function AllowReminders() {
  const { t } = useTranslation();
  const { next } = useLocalSearchParams<{ next?: string }>();
  const [busy, setBusy] = useState(false);
  const go = () => router.replace((next ?? '/(seeker)/home') as Href);

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.content}>
        <View style={styles.preview} aria-hidden>
          <View style={styles.previewIcon}>
            <BellRing size={18} strokeWidth={1.8} color={colors.onBrand} />
          </View>
          <View style={styles.flex}>
            <Text variant="meta" style={styles.bold}>
              {t('common.appName')}
            </Text>
            <Text variant="meta" color={colors.inkSoft}>
              {t('reminders.preview')}
            </Text>
          </View>
        </View>
        <Text variant="titleLg" accessibilityRole="header">
          {t('reminders.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted} style={styles.lead}>
          {t('reminders.body')}
        </Text>
      </View>
      <View style={styles.footer}>
        <Button
          onboarding
          label={t('reminders.turnOn')}
          disabled={busy}
          accessibilityState={{ disabled: busy, busy }}
          onPress={async () => {
            setBusy(true);
            await askAndRegister();
            go();
          }}
        />
        <Button
          variant="quiet"
          label={t('reminders.notNow')}
          onPress={() => {
            useSession.getState().askedReminders();
            go();
          }}
        />
        <Text variant="meta" color={colors.inkMuted} style={styles.center}>
          {t('reminders.later')}
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    flex: 1,
    justifyContent: 'center',
    padding: space.space24,
    gap: space.space16,
  },
  preview: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  previewIcon: {
    width: 36,
    height: 36,
    borderRadius: radius.radius10,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brand,
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
  lead: {
    fontSize: 16,
    lineHeight: 24,
  },
  footer: {
    gap: space.space8,
    paddingHorizontal: space.space24,
    paddingBottom: space.space16,
  },
  center: {
    textAlign: 'center',
  },
});
