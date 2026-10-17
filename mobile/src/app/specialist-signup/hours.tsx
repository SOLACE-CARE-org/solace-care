import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Check, Plus } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiProblem } from '@/api/core';
import {
  SPECIALIST_STEPS,
  type WeeklyHours,
  stepRoute,
  useApplication,
  useSaveWeeklyHours,
  useWeeklyHours,
} from '@/api/specialist';
import i18n from '@/i18n';
import { maskTime } from '@/state/signup';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

type Weekday = WeeklyHours['days'][number]['weekday'];
type Range = { start: string; end: string };

const WEEK: Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const BREAKS = [10, 15, 0] as const;
const NOTICES = [2, 12, 24] as const;
const HHMM = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;
/** A day switched on starts with the first range SpecAvailability shows. */
const FIRST_RANGE: Range = { start: '09:00', end: '12:00' };

/** Specialist application, step 6 of 7: weekly hours (SpecAvailability.dc.html). */
export default function SpecialistHours() {
  const { t } = useTranslation();
  const hours = useWeeklyHours();
  const application = useApplication();
  const save = useSaveWeeklyHours();
  const [problem, setProblem] = useState<string | null>(null);
  const [edited, setEdited] = useState<WeeklyHours | null>(null);
  const [editing, setEditing] = useState<{ day: Weekday; index: number | null } | null>(null);

  const current = edited ?? hours.data;
  const rangesOf = (day: Weekday): Range[] =>
    current?.days.find((d) => d.weekday === day)?.ranges ?? [];
  const update = (patch: Partial<WeeklyHours>) => current && setEdited({ ...current, ...patch });
  const setRanges = (day: Weekday, ranges: Range[]) =>
    update({
      days: WEEK.map((weekday) => ({
        weekday,
        ranges: weekday === day ? sortRanges(ranges) : rangesOf(weekday),
      })),
    });

  const ready = current !== undefined && WEEK.some((d) => rangesOf(d).length > 0);

  const submit = () => {
    if (!current || !ready) return;
    setProblem(null);
    save.mutate(current, {
      onSuccess: () => {
        // The application's hours step follows from the saved hours.
        application.refetch().then((r) => r.data && router.push(stepRoute(r.data)));
      },
      onError: (e) =>
        setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
    });
  };

  const zone = current ? zoneLabel(current.timeZone) : '';

  return (
    <FlowScreen
      top={<ProgressSteps step={6} total={SPECIALIST_STEPS} />}
      footer={
        <Button
          label={t('common.continue')}
          disabled={!ready || save.isPending}
          accessibilityState={{ disabled: !ready || save.isPending, busy: save.isPending }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('specialistSignup.hours.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('specialistSignup.hours.subtitle')}
        </Text>
      </View>

      <View style={styles.card}>
        {WEEK.map((day, i) => {
          const ranges = rangesOf(day);
          const on = ranges.length > 0;
          const dayName = t(`specialistSignup.hours.${day}`);
          return (
            <View key={day} style={[styles.day, i > 0 && styles.divider]}>
              <Pressable
                accessibilityRole="checkbox"
                accessibilityState={{ checked: on }}
                accessibilityLabel={dayName}
                onPress={() => setRanges(day, on ? [] : [FIRST_RANGE])}
                style={styles.checkTarget}
              >
                <View style={[styles.checkbox, on && styles.checkboxOn]}>
                  {on ? <Check size={16} strokeWidth={3} color={colors.onBrand} /> : null}
                </View>
              </Pressable>
              <Text variant="label" color={on ? colors.ink : colors.inkMuted} style={styles.dayName}>
                {dayName}
              </Text>
              <View style={styles.ranges}>
                {on ? (
                  <>
                    {ranges.map((range, index) => (
                      <Pressable
                        key={`${range.start}-${range.end}`}
                        accessibilityRole="button"
                        accessibilityLabel={`${dayName}, ${range.start}–${range.end}`}
                        onPress={() => setEditing({ day, index })}
                        style={styles.range}
                      >
                        <Text variant="label" color={colors.brandDeep} style={styles.rangeText}>
                          {`${range.start}–${range.end}`}
                        </Text>
                      </Pressable>
                    ))}
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`${dayName}, ${t('specialistSignup.hours.addHours')}`}
                      onPress={() => setEditing({ day, index: null })}
                      style={styles.addRange}
                    >
                      <Plus size={16} strokeWidth={1.8} color={colors.brand} />
                    </Pressable>
                  </>
                ) : (
                  <Text variant="body" color={colors.inkMuted}>
                    {t('specialistSignup.hours.unavailable')}
                  </Text>
                )}
              </View>
            </View>
          );
        })}
      </View>

      {current ? (
        <View style={styles.settings}>
          <View style={styles.setting}>
            <Text variant="label" color={colors.inkMuted} style={styles.label}>
              {t('specialistSignup.hours.breakLabel')}
            </Text>
            <Segmented
              label={t('specialistSignup.hours.breakLabel')}
              options={BREAKS.map((b) => ({ value: String(b), label: t(`specialistSignup.hours.break_${b}`) }))}
              value={String(current.breakMinutes)}
              onChange={(v) => update({ breakMinutes: Number(v) as WeeklyHours['breakMinutes'] })}
            />
          </View>
          <View style={styles.setting}>
            <Text variant="label" color={colors.inkMuted} style={styles.label}>
              {t('specialistSignup.hours.noticeLabel')}
            </Text>
            <Segmented
              label={t('specialistSignup.hours.noticeLabel')}
              options={NOTICES.map((n) => ({ value: String(n), label: t(`specialistSignup.hours.notice_${n}`) }))}
              value={String(current.minimumNoticeHours)}
              onChange={(v) =>
                update({ minimumNoticeHours: Number(v) as WeeklyHours['minimumNoticeHours'] })
              }
            />
          </View>
          <Text variant="meta" color={colors.inkMuted}>
            {t('specialistSignup.hours.timeZone', { zone })}
          </Text>
        </View>
      ) : null}

      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}

      {editing ? (
        <RangeEditor
          day={t(`specialistSignup.hours.${editing.day}`)}
          initial={editing.index === null ? null : rangesOf(editing.day)[editing.index]}
          onClose={() => setEditing(null)}
          onSave={(range) => {
            const ranges = rangesOf(editing.day);
            setRanges(
              editing.day,
              editing.index === null
                ? [...ranges, range]
                : ranges.map((r, i) => (i === editing.index ? range : r)),
            );
            setEditing(null);
          }}
          onRemove={
            editing.index === null
              ? undefined
              : () => {
                  setRanges(
                    editing.day,
                    rangesOf(editing.day).filter((_, i) => i !== editing.index),
                  );
                  setEditing(null);
                }
          }
        />
      ) : null}
    </FlowScreen>
  );
}

/** Start and end of one range, typed as HH:MM. Core checks overlaps on save. */
function RangeEditor({
  day,
  initial,
  onClose,
  onSave,
  onRemove,
}: {
  day: string;
  initial: Range | null;
  onClose: () => void;
  onSave: (range: Range) => void;
  onRemove?: () => void;
}) {
  const { t } = useTranslation();
  const [start, setStart] = useState(initial?.start ?? '');
  const [end, setEnd] = useState(initial?.end ?? '');
  const valid = HHMM.test(start) && HHMM.test(end) && start < end;
  return (
    <Modal visible animationType="slide" onRequestClose={onClose}>
      <SafeAreaView style={styles.sheet} edges={['top', 'bottom']}>
        <View style={styles.sheetContent}>
          <Text variant="titleMd" accessibilityRole="header">
            {day}
          </Text>
          <View style={styles.times}>
            <View style={styles.half}>
              <TextField
                label={t('specialistSignup.hours.start')}
                value={start}
                onChangeText={(v) => setStart(maskTime(v))}
                keyboardType="number-pad"
                placeholder="09:00"
              />
            </View>
            <View style={styles.half}>
              <TextField
                label={t('specialistSignup.hours.end')}
                value={end}
                onChangeText={(v) => setEnd(maskTime(v))}
                keyboardType="number-pad"
                placeholder="12:00"
              />
            </View>
          </View>
          <Button
            label={t('specialistSignup.hours.save')}
            disabled={!valid}
            accessibilityState={{ disabled: !valid }}
            onPress={() => onSave({ start, end })}
          />
          {onRemove ? (
            <Button variant="quiet" label={t('specialistSignup.hours.remove')} onPress={onRemove} />
          ) : null}
          <Button variant="quiet" label={t('common.back')} onPress={onClose} />
        </View>
      </SafeAreaView>
    </Modal>
  );
}

function sortRanges(ranges: Range[]): Range[] {
  return [...ranges].sort((a, b) => a.start.localeCompare(b.start));
}

/** "Africa/Douala (WAT)": the zone and its short name where the platform knows it. */
function zoneLabel(timeZone: string): string {
  try {
    const short = new Intl.DateTimeFormat(i18n.language, { timeZone, timeZoneName: 'short' })
      .formatToParts(new Date())
      .find((p) => p.type === 'timeZoneName')?.value;
    return short ? `${timeZone} (${short})` : timeZone;
  } catch {
    return timeZone;
  }
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space6,
  },
  card: {
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  day: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
    paddingVertical: space.space6,
    paddingHorizontal: space.space14,
    minHeight: size.sizeTap + 8,
  },
  divider: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  checkTarget: {
    width: size.sizeTap,
    height: size.sizeTap,
    marginLeft: -space.space10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkbox: {
    width: 22,
    height: 22,
    borderRadius: radius.radius6,
    borderWidth: 2,
    borderColor: colors.lineControl,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceCard,
  },
  checkboxOn: {
    borderColor: colors.brand,
    backgroundColor: colors.brand,
  },
  dayName: {
    width: 40,
    fontSize: 15,
  },
  ranges: {
    flex: 1,
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: space.space6,
  },
  range: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
  rangeText: {
    paddingVertical: space.space6,
    paddingHorizontal: space.space10,
    borderRadius: radius.radius10,
    backgroundColor: colors.brandTint,
    fontFamily: fontFamily.body[700],
    fontSize: 13,
    overflow: 'hidden',
  },
  addRange: {
    width: size.sizeTap,
    height: size.sizeTap,
    alignItems: 'center',
    justifyContent: 'center',
  },
  settings: {
    gap: space.space12,
  },
  setting: {
    gap: space.space6,
  },
  label: {
    fontSize: 13,
  },
  sheet: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  sheetContent: {
    padding: space.space20,
    gap: space.space16,
  },
  times: {
    flexDirection: 'row',
    gap: space.space10,
  },
  half: {
    flex: 1,
  },
});
