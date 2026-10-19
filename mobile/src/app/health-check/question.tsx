import { colors, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { saveAnswers } from '@/api/healthCheck';
import { currentQuestion, useHealthCheck } from '@/state/healthCheck';
import { Button } from '@/ui/Button';
import { CrisisButton } from '@/ui/CrisisButton';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/**
 * Health check: one question at a time (HealthCheckTopics.dc.html, HealthCheckScale.dc.html).
 * Every answer so far goes to Core, which says what to ask next or that urgent help opens now.
 */
export default function HealthCheckQuestion() {
  const { t } = useTranslation();
  const state = useHealthCheck();
  const q = currentQuestion(state);
  const [chosen, setChosen] = useState<string[]>(q ? (state.answers[q.code] ?? []) : []);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  // Nothing left to ask: the preferences.
  useEffect(() => {
    if (state.responseId && !q) router.replace('/health-check/preferences');
  }, [state.responseId, q]);

  if (!state.responseId) {
    return (
      <FlowScreen top={<BackButton />}>
        <Text variant="body">{t('common.checkConnection')}</Text>
      </FlowScreen>
    );
  }

  if (!q) {
    return null;
  }

  const toggle = (code: string) =>
    setChosen((c) =>
      q.multi ? (c.includes(code) ? c.filter((x) => x !== code) : [...c, code]) : [code],
    );

  const next = async () => {
    setBusy(true);
    setFailed(false);
    const answers = { ...state.answers, [q.code]: chosen };
    try {
      const progress = await saveAnswers(state.responseId!, state.token, answers);
      useHealthCheck.getState().answer(q.code, chosen);
      useHealthCheck.getState().setNext(progress.next);
      const upcoming = useHealthCheck.getState();
      const following = currentQuestion(upcoming);
      setChosen(following ? (upcoming.answers[following.code] ?? []) : []);
      // When nothing is left, the effect above moves on to the preferences.
      if (progress.status === 'SAFETY_STOP') router.push('/health-check/urgent');
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <Button
          label={t('healthCheck.next')}
          disabled={chosen.length === 0 || busy}
          accessibilityState={{ disabled: chosen.length === 0 || busy, busy }}
          onPress={() => void next()}
        />
      }
    >
      <Text variant="meta" color={colors.inkMuted}>
        {t(`healthCheck.step.${q.step}`)}
      </Text>
      {q.intro ? (
        <Text variant="body" color={colors.inkSoft}>
          {q.intro}
        </Text>
      ) : null}
      <Text variant="titleMd" accessibilityRole="header">
        {q.text}
      </Text>
      {q.multi ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('healthCheck.chooseAll')}
        </Text>
      ) : null}
      <View style={styles.options} accessibilityRole={q.multi ? undefined : 'radiogroup'}>
        {q.options.map((o) => (
          <OptionCard
            key={o.code}
            kind={q.multi ? 'checkbox' : 'radio'}
            title={o.label}
            selected={chosen.includes(o.code)}
            onPress={() => toggle(o.code)}
          />
        ))}
      </View>
      {q.source ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t(`healthCheck.source.${q.source}`)}
        </Text>
      ) : null}
      {failed ? (
        <Text variant="meta" color={colors.danger} accessibilityRole="alert">
          {t('common.checkConnection')}
        </Text>
      ) : null}
      <CrisisButton label={t('crisis.button')} onPress={() => router.push('/crisis')} />
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  options: { gap: space.space8 },
});
