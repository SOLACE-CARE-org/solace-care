import { colors, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { useEnrol, useModules } from '@/api/tracking';
import i18n from '@/i18n';
import { signupSteps } from '@/state/signup';
import { moduleDescription, moduleName } from '@/tracking/view';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/**
 * Seeker sign-up, step 4 (SeekerModules.dc.html; Phase 2): what to track. Optional; a module with
 * an enrolment question is added from the Track tab, where the question can be asked.
 */
export default function SignupModules() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const modules = useModules(lang);
  const enrol = useEnrol();
  const [chosen, setChosen] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const offered = (modules.data ?? []).filter((m) => !m.enrolmentQuestion);

  const start = async () => {
    setBusy(true);
    setFailed(false);
    try {
      for (const code of chosen) {
        const m = offered.find((x) => x.code === code);
        await enrol.mutateAsync({
          code,
          ...(m?.reminderDefault ? { reminderTime: m.reminderDefault.slice(0, 5) } : {}),
        });
      }
      router.replace('/signup/done');
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <FlowScreen
      top={<ProgressSteps step={4} total={signupSteps()} />}
      footer={
        <View style={styles.footer}>
          <Button
            label={
              chosen.length === 1
                ? t('track.signup.startOne')
                : t('track.signup.start', { count: chosen.length })
            }
            disabled={busy || chosen.length === 0}
            accessibilityState={{ disabled: busy || chosen.length === 0, busy }}
            onPress={() => void start()}
          />
          <Button
            variant="quiet"
            label={t('track.signup.skip')}
            onPress={() => router.replace('/signup/done')}
          />
        </View>
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('track.signup.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('track.signup.subtitle')}
        </Text>
      </View>
      <Text variant="label" color={colors.inkMuted}>
        {t('track.signup.section')}
      </Text>
      {modules.isError ? (
        <Text variant="meta" color={colors.danger}>
          {t('common.checkConnection')}
        </Text>
      ) : null}
      <View style={styles.list}>
        {offered.map((m) => (
          <OptionCard
            key={m.code}
            kind="checkbox"
            title={moduleName(m, lang)}
            meta={moduleDescription(m, lang)}
            selected={chosen.includes(m.code)}
            onPress={() =>
              setChosen((c) =>
                c.includes(m.code) ? c.filter((x) => x !== m.code) : [...c, m.code],
              )
            }
          />
        ))}
      </View>
      {failed ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {t('common.checkConnection')}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  intro: { gap: space.space6 },
  list: { gap: space.space8 },
  footer: { gap: space.space8 },
});
