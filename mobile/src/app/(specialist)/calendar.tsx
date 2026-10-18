import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Plus } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiProblem } from '@/api/core';
import {
  type TimeOffReason,
  useBlockTimeOff,
  useCalendar,
  useRemoveTimeOff,
} from '@/api/scheduling';
import i18n from '@/i18n';
import { capitalised, timeOf } from '@/state/format';
import { maskTime } from '@/state/signup';
import { Button } from '@/ui/Button';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

const REASONS: TimeOffReason[] = ['PERSONAL', 'HOLIDAY', 'OTHER'];
const HHMM = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

/** Monday of the week containing {@code date}, as YYYY-MM-DD in the viewer's calendar. */
function mondayOf(date: Date): string {
  const d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * Calendar & availability (SpecCalendar.dc.html): the week, a day's open starts and time off, and
 * Block time off. Booked sessions join the day with Bookings (epic 5).
 */
export default function SpecialistCalendar() {
  const { t } = useTranslation();
  const lang = i18n.language;
  const [week] = useState(() => mondayOf(new Date()));
  const calendar = useCalendar(week);
  const remove = useRemoveTimeOff();
  const [selected, setSelected] = useState<string | null>(null);
  const [blocking, setBlocking] = useState(false);

  const days = calendar.data?.days ?? [];
  const todayKey = new Date().toISOString().slice(0, 10);
  const chosen = days.find((d) => d.date === (selected ?? todayKey)) ?? days[0];
  const month = capitalised(
    new Intl.DateTimeFormat(lang, { month: 'long' }).format(new Date(`${week}T12:00:00`)),
  );

  type Row =
    | { kind: 'open'; at: string }
    | { kind: 'off'; at: string; id: string; end: string; reason: TimeOffReason };
  const rows: Row[] = chosen
    ? [
        ...chosen.openStarts.map((at): Row => ({ kind: 'open', at })),
        ...chosen.timeOff.map(
          (o): Row => ({ kind: 'off', at: o.start, id: o.id, end: o.end, reason: o.reason }),
        ),
      ].sort((a, b) => a.at.localeCompare(b.at))
    : [];

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.head}>
          <Text variant="titleMd" accessibilityRole="header">
            {month}
          </Text>
          <Button
            variant="secondary"
            label={t('calendar.weeklyHours')}
            onPress={() => router.push('/specialist-signup/hours')}
          />
        </View>

        <View style={styles.week} accessibilityRole="tablist">
          {days.map((d) => {
            const date = new Date(`${d.date}T12:00:00`);
            const on = d.date === chosen?.date;
            return (
              <Pressable
                key={d.date}
                accessibilityRole="tab"
                accessibilityState={{ selected: on }}
                accessibilityLabel={new Intl.DateTimeFormat(lang, { dateStyle: 'full' }).format(date)}
                onPress={() => setSelected(d.date)}
                style={[styles.day, on && styles.dayOn]}
              >
                <Text variant="meta" color={on ? colors.onBrand : colors.inkMuted}>
                  {new Intl.DateTimeFormat(lang, { weekday: 'narrow' }).format(date)}
                </Text>
                <Text variant="label" color={on ? colors.onBrand : colors.ink} style={styles.dayNumber}>
                  {date.getDate()}
                </Text>
              </Pressable>
            );
          })}
        </View>

        {calendar.isPending ? (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        ) : calendar.isError ? (
          <View style={styles.state}>
            <Text variant="body" color={colors.inkMuted}>
              {t('common.checkConnection')}
            </Text>
            <Button label={t('common.tryAgain')} onPress={() => calendar.refetch()} />
          </View>
        ) : rows.length === 0 ? (
          <Text variant="body" color={colors.inkMuted}>
            {t('calendar.nothing')}
          </Text>
        ) : (
          rows.map((row) => (
            <View key={`${row.kind}-${row.at}`} style={styles.row}>
              <Text variant="label" color={colors.inkSoft} style={styles.time}>
                {timeOf(new Date(row.at), lang)}
              </Text>
              {row.kind === 'open' ? (
                <View style={[styles.entry, styles.open]}>
                  <Text variant="meta" color={colors.inkMuted}>
                    {t('calendar.openSlot')}
                  </Text>
                </View>
              ) : (
                <View style={[styles.entry, styles.blocked]}>
                  <Text variant="meta" color={colors.inkSoft}>
                    {t('calendar.blocked', { reason: t(`calendar.reason_${row.reason}`) })} ·{' '}
                    {timeOf(new Date(row.end), lang)}
                  </Text>
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={t('calendar.unblock')}
                    onPress={() => remove.mutate(row.id)}
                    style={styles.unblock}
                  >
                    <Text variant="meta" color={colors.brand} style={styles.strong}>
                      {t('calendar.unblock')}
                    </Text>
                  </Pressable>
                </View>
              )}
            </View>
          ))
        )}

        {chosen ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('calendar.blockTimeOff')}
            onPress={() => setBlocking(true)}
            style={styles.block}
          >
            <Plus size={18} strokeWidth={1.8} color={colors.brand} aria-hidden />
            <Text variant="label" color={colors.brand}>
              {t('calendar.blockTimeOff')}
            </Text>
          </Pressable>
        ) : null}
      </ScrollView>

      {blocking && chosen ? (
        <BlockTimeOff date={chosen.date} onClose={() => setBlocking(false)} />
      ) : null}
    </SafeAreaView>
  );
}

function BlockTimeOff({ date, onClose }: { date: string; onClose: () => void }) {
  const { t } = useTranslation();
  const block = useBlockTimeOff();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState<TimeOffReason>('PERSONAL');
  const [problem, setProblem] = useState<string | null>(null);
  const valid = HHMM.test(from) && HHMM.test(to) && from < to;

  const save = () => {
    if (!valid) return;
    setProblem(null);
    // The times are the specialist's own; the device's time zone turns them into instants.
    block.mutate(
      {
        start: new Date(`${date}T${from}:00`).toISOString(),
        end: new Date(`${date}T${to}:00`).toISOString(),
        reason,
      },
      {
        onSuccess: onClose,
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  return (
    <Modal visible animationType="slide" onRequestClose={onClose}>
      <SafeAreaView style={styles.sheet} edges={['top', 'bottom']}>
        <View style={styles.sheetContent}>
          <Text variant="titleMd" accessibilityRole="header">
            {t('calendar.blockTimeOff')}
          </Text>
          <View style={styles.times}>
            <View style={styles.half}>
              <TextField
                label={t('calendar.from')}
                value={from}
                onChangeText={(v) => setFrom(maskTime(v))}
                keyboardType="number-pad"
                placeholder="17:00"
              />
            </View>
            <View style={styles.half}>
              <TextField
                label={t('calendar.to')}
                value={to}
                onChangeText={(v) => setTo(maskTime(v))}
                keyboardType="number-pad"
                placeholder="18:00"
              />
            </View>
          </View>
          <View style={styles.field}>
            <Text variant="label" color={colors.inkMuted}>
              {t('calendar.reasonLabel')}
            </Text>
            <Segmented
              label={t('calendar.reasonLabel')}
              options={REASONS.map((r) => ({ value: r, label: capitalised(t(`calendar.reason_${r}`)) }))}
              value={reason}
              onChange={setReason}
            />
          </View>
          {problem ? (
            <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
              {problem}
            </Text>
          ) : null}
          <Button
            label={t('calendar.save')}
            disabled={!valid || block.isPending}
            accessibilityState={{ disabled: !valid || block.isPending, busy: block.isPending }}
            onPress={save}
          />
          <Button variant="quiet" label={t('common.back')} onPress={onClose} />
        </View>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space14,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  week: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  day: {
    width: 44,
    minHeight: 58,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.radius12,
  },
  dayOn: {
    backgroundColor: colors.brand,
  },
  dayNumber: {
    fontSize: 17,
    fontFamily: fontFamily.body[700],
  },
  state: {
    alignItems: 'center',
    gap: space.space12,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
  },
  time: {
    width: 48,
  },
  entry: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: size.sizeTap,
    paddingHorizontal: space.space12,
    borderRadius: radius.radius12,
  },
  open: {
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.lineControl,
  },
  blocked: {
    backgroundColor: colors.surfaceSunken,
  },
  unblock: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
  strong: {
    fontFamily: fontFamily.body[700],
  },
  block: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space8,
    minHeight: size.sizeTap + 6,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.lineControl,
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
  field: {
    gap: space.space6,
  },
});
