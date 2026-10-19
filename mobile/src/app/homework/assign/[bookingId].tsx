import { colors, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { Minus, Plus } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { useMyBookings } from '@/api/bookings';
import { type HomeworkAssignment, useAssignHomework } from '@/api/care';
import { ApiProblem } from '@/api/core';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { BackButton } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

type Exercise = HomeworkAssignment['exercise'];
type Frequency = HomeworkAssignment['frequency'];

const EXERCISES: Exercise[] = ['THOUGHT_RECORD', 'SLEEP_DIARY', 'WORRY_TIME'];

/**
 * Assign homework (AssignHomework.dc.html), Phase 4: after a session, for a registered seeker. It
 * is due before their next session with this specialist, or after [7] days (D57).
 */
export default function AssignHomework() {
  const { t } = useTranslation();
  const { bookingId } = useLocalSearchParams<{ bookingId: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const past = useMyBookings('PAST');
  const upcoming = useMyBookings('UPCOMING');
  const assign = useAssignHomework(bookingId);
  const [exercise, setExercise] = useState<Exercise>('THOUGHT_RECORD');
  const [custom, setCustom] = useState('');
  const [frequency, setFrequency] = useState<Frequency>('TIMES');
  const [times, setTimes] = useState(3);
  const [note, setNote] = useState('');
  const [guided, setGuided] = useState(true);
  const [problem, setProblem] = useState<string | null>(null);
  const [openedAt] = useState(() => Date.now());

  const session = past.data?.find((b) => b.id === bookingId);
  const name = session?.seeker?.label ?? t('notes.seeker');
  // The next session with the same seeker, when one is booked.
  const next = upcoming.data
    ?.filter((b) => b.seeker?.label === session?.seeker?.label && b.status === 'CONFIRMED')
    .sort((a, b) => a.start.localeCompare(b.start))[0];
  const dueBy = next?.start;
  const due = new Date(dueBy ?? openedAt + 7 * 86_400_000);
  const date = new Intl.DateTimeFormat(lang, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  }).format(due);
  const valid = exercise !== 'CUSTOM' || custom.trim().length > 0;

  const submit = () => {
    setProblem(null);
    assign.mutate(
      {
        exercise,
        ...(exercise === 'CUSTOM' ? { customTask: custom.trim() } : {}),
        frequency,
        ...(frequency === 'TIMES' ? { times } : {}),
        ...(note.trim() ? { note: note.trim() } : {}),
        companionGuided: guided,
        ...(dueBy ? { dueBy } : {}),
      },
      {
        onSuccess: () => router.back(),
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <View style={styles.footer}>
          {problem ? (
            <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
              {problem}
            </Text>
          ) : null}
          <Button
            label={t('homework.assign')}
            disabled={!valid || assign.isPending}
            accessibilityState={{ disabled: !valid || assign.isPending, busy: assign.isPending }}
            onPress={submit}
          />
        </View>
      }
    >
      <View>
        <Text variant="titleLg" accessibilityRole="header">
          {t('homework.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('homework.sub', { name, date })}
        </Text>
      </View>

      <Text variant="label" color={colors.inkMuted} accessibilityRole="header">
        {t('homework.exercise')}
      </Text>
      {EXERCISES.map((e) => (
        <OptionCard
          key={e}
          kind="radio"
          title={t(`homework.${e}`)}
          meta={t(`homework.${e}Meta`)}
          selected={exercise === e}
          onPress={() => setExercise(e)}
        />
      ))}
      <OptionCard
        kind="radio"
        title={t('homework.CUSTOM')}
        selected={exercise === 'CUSTOM'}
        onPress={() => setExercise('CUSTOM')}
      />
      {exercise === 'CUSTOM' ? (
        <TextField
          label={t('homework.customLabel')}
          value={custom}
          onChangeText={setCustom}
          maxLength={300}
        />
      ) : null}

      <Segmented
        label={t('homework.howMany')}
        value={frequency}
        onChange={setFrequency}
        options={[
          { value: 'TIMES', label: t('homework.TIMES') },
          { value: 'DAILY', label: t('homework.DAILY') },
          { value: 'ONCE', label: t('homework.ONCE') },
        ]}
      />
      {frequency === 'TIMES' ? (
        <View style={styles.stepper}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('homework.fewer')}
            disabled={times <= 1}
            onPress={() => setTimes((n) => Math.max(1, n - 1))}
            style={styles.step}
          >
            <Minus size={20} strokeWidth={1.8} color={colors.ink} aria-hidden />
          </Pressable>
          <Text variant="label" style={styles.flex} accessibilityLiveRegion="polite">
            {t('homework.times', { count: times })}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('homework.more')}
            disabled={times >= 14}
            onPress={() => setTimes((n) => Math.min(14, n + 1))}
            style={styles.step}
          >
            <Plus size={20} strokeWidth={1.8} color={colors.ink} aria-hidden />
          </Pressable>
        </View>
      ) : null}

      <TextField
        label={t('homework.noteTo', { name })}
        value={note}
        onChangeText={setNote}
        multiline
        maxLength={500}
      />
      <SwitchRow
        title={t('homework.companion', { name })}
        on={guided}
        onToggle={() => setGuided((g) => !g)}
      />
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  footer: { gap: space.space8 },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: space.space12 },
  step: {
    width: 44,
    height: 44,
    borderRadius: 22,
    borderWidth: 1,
    borderColor: colors.line,
    alignItems: 'center',
    justifyContent: 'center',
  },
  flex: { flex: 1, textAlign: 'center' },
});
