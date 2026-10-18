import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Lock } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { type ConsentPurpose, useConsents, useSaveConsents } from '@/api/account';
import { signupSteps } from '@/state/signup';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';

/** The purposes this screen can show; Core lists the ones offered in this release. */
type Shown = Extract<ConsentPurpose, 'AI_ASSIST' | 'AI_SCRIBE'>;
const SHOWN: readonly Shown[] = ['AI_ASSIST', 'AI_SCRIBE'];

/**
 * Seeker sign-up, step 3: privacy choices (SeekerConsent.dc.html). The health check and tracking
 * rows appear only when those phases ship (docs/screens.md); Core says which purposes are offered.
 * Every choice starts off: consent is never pre-selected (components/OptionCard).
 */
export default function SignupPrivacy() {
  const { t } = useTranslation();
  const current = useConsents();
  const save = useSaveConsents();
  // Only what the person changed here; everything else is what Core has saved.
  const [changed, setChanged] = useState<Partial<Record<Shown, boolean>>>({});

  const offered = (current.data?.consents ?? [])
    .map((c) => c.purpose)
    .filter((p): p is Shown => (SHOWN as readonly string[]).includes(p));
  const granted = (purpose: Shown) =>
    changed[purpose] ??
    current.data?.consents.find((c) => c.purpose === purpose)?.granted === true;

  const submit = () =>
    save.mutate(
      offered.map((purpose) => ({ purpose, granted: granted(purpose) })),
      { onSuccess: () => router.replace('/signup/done') },
    );

  return (
    <FlowScreen
      top={<ProgressSteps step={3} total={signupSteps()} />}
      footer={
        <Button
          label={t('signupPrivacy.save')}
          disabled={offered.length === 0 || save.isPending}
          accessibilityState={{ disabled: offered.length === 0 || save.isPending, busy: save.isPending }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('signupPrivacy.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('signupPrivacy.subtitle')}
        </Text>
      </View>

      {offered.length > 0 ? (
        <View style={styles.card}>
          {offered.map((purpose, i) => (
            <View key={purpose} style={i > 0 && styles.divider}>
              <SwitchRow
                title={t(`signupPrivacy.${purpose}_title`)}
                meta={t(`signupPrivacy.${purpose}_meta`)}
                on={granted(purpose)}
                onToggle={() => setChanged((c) => ({ ...c, [purpose]: !granted(purpose) }))}
              />
            </View>
          ))}
        </View>
      ) : null}

      <View style={styles.promise}>
        <Lock size={18} strokeWidth={1.8} color={colors.inkMuted} aria-hidden />
        <Text variant="meta" color={colors.inkMuted} style={styles.promiseText}>
          {t('signupPrivacy.promise')}{' '}
          <Text
            variant="meta"
            color={colors.brand}
            accessibilityRole="link"
            onPress={() => router.push('/legal/privacy')}
            style={styles.link}
          >
            {t('signupPrivacy.readNotice')}
          </Text>
        </Text>
      </View>

      {current.isError || save.isError ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {t('common.checkConnection')}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space6,
  },
  card: {
    paddingHorizontal: space.space16,
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  divider: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  promise: {
    flexDirection: 'row',
    gap: space.space10,
    alignItems: 'flex-start',
  },
  promiseText: {
    flex: 1,
  },
  link: {
    textDecorationLine: 'underline',
  },
});
