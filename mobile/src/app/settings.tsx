import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import Constants from 'expo-constants';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { usePreferences, useSavePreferences } from '@/api/account';
import { useNotificationSettings, useSaveNotificationSettings } from '@/api/messaging';
import i18n from '@/i18n';
import { pushAllowed } from '@/notifications/push';
import { type AppLanguage, useSession } from '@/state/session';
import { deviceCanLock, unlockWithDevice } from '@/ui/AppLockGate';
import { BackButton } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';

/**
 * Settings (Settings.dc.html), Phase 1: app language, session reminders, discreet notifications and
 * data saver. The tracking reminder arrives with Tracking (Phase 2); app lock waits for the
 * biometrics work, behind its flag.
 */
export default function Settings() {
  const { t } = useTranslation();
  const language = useSession((s) => s.language) ?? 'en';
  const dataSaver = useSession((s) => s.dataSaver);
  const appLock = useSession((s) => s.appLock);
  const [lockProblem, setLockProblem] = useState<string | null>(null);

  // Turning the lock on or off is confirmed with the phone's own lock, so nobody else can change it.
  const toggleLock = async () => {
    setLockProblem(null);
    if (!appLock && !(await deviceCanLock())) {
      setLockProblem(t('settings.appLockUnavailable'));
      return;
    }
    if (await unlockWithDevice(t('appLock.prompt'))) {
      useSession.getState().setAppLock(!appLock);
    }
  };
  const settings = useNotificationSettings();
  const save = useSaveNotificationSettings();
  const preferences = usePreferences();
  const savePreferences = useSavePreferences();
  const [pushOn, setPushOn] = useState(true);

  useEffect(() => {
    void pushAllowed().then(setPushOn);
  }, []);

  const changeLanguage = (next: AppLanguage) => {
    useSession.getState().chooseLanguage(next);
    void i18n.changeLanguage(next);
    // Core words messages in the app language, so it hears about the change too.
    if (preferences.data) {
      savePreferences.mutate({ ...preferences.data, appLanguage: next });
    }
  };
  const s = settings.data;

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.titleRow}>
          <BackButton />
          <Text variant="titleMd" accessibilityRole="header">
            {t('settings.title')}
          </Text>
        </View>

        <Segmented
          label={t('settings.appLanguage')}
          value={language}
          onChange={changeLanguage}
          options={[
            { value: 'en', label: t('common.english') },
            { value: 'fr', label: t('common.french') },
          ]}
        />

        <Text
          variant="meta"
          color={colors.inkMuted}
          style={styles.section}
          accessibilityRole="header"
        >
          {t('settings.notifications').toUpperCase()}
        </Text>
        {!pushOn ? (
          <Text variant="meta" color={colors.warningInk} style={styles.notice}>
            {t('settings.pushOff')}
          </Text>
        ) : null}
        {s ? (
          <View style={styles.card}>
            <SwitchRow
              title={t('settings.sessionReminders')}
              meta={t('settings.sessionRemindersMeta')}
              on={s.sessionReminders}
              onToggle={() => save.mutate({ ...s, sessionReminders: !s.sessionReminders })}
            />
            <SwitchRow
              title={t('settings.discreet')}
              meta={t('settings.discreetMeta')}
              on={s.discreet}
              onToggle={() => save.mutate({ ...s, discreet: !s.discreet })}
            />
          </View>
        ) : (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        )}

        <Text
          variant="meta"
          color={colors.inkMuted}
          style={styles.section}
          accessibilityRole="header"
        >
          {t('settings.security').toUpperCase()}
        </Text>
        <View style={styles.card}>
          <SwitchRow
            title={t('settings.appLock')}
            meta={lockProblem ?? t('settings.appLockMeta')}
            on={appLock}
            onToggle={() => void toggleLock()}
          />
          <SwitchRow
            title={t('settings.dataSaver')}
            meta={t('settings.dataSaverMeta')}
            on={dataSaver}
            onToggle={() => useSession.getState().setDataSaver(!dataSaver)}
          />
        </View>

        <Text variant="meta" color={colors.inkFaint} style={styles.version}>
          {t('settings.version', { version: Constants.expoConfig?.version ?? '' })}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space12,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  section: {
    marginTop: space.space8,
    fontFamily: fontFamily.body[700],
    letterSpacing: 0.5,
  },
  notice: {
    padding: space.space12,
    borderRadius: radius.radius12,
    backgroundColor: colors.warningTint,
  },
  card: {
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
    paddingHorizontal: space.space14,
  },
  version: {
    textAlign: 'center',
    marginTop: space.space16,
  },
});
