import { colors, radius, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { Minus, Plus } from 'lucide-react-native';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import {
  type Element,
  type PushEntry,
  timeZone,
  useElements,
  useEpisodes,
  useModules,
  useObservations,
} from '@/api/tracking';
import i18n from '@/i18n';
import { newEntryId, saveEntries } from '@/tracking/outbox';
import { dayLabel, episodeModel, sections } from '@/tracking/presentation';
import {
  choicesOf,
  helpOf,
  hoursAndMinutes,
  localDay,
  moduleName,
  nameOf,
  valuesOn,
} from '@/tracking/view';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';

type Value = string | number | boolean;

/**
 * Log today (Tracking.dc.html, Checkin.dc.html): one control per element of the module, from the
 * element library, grouped as the module says. The title, save button and the day in the header
 * come from the module's presentation (D61). Saving writes to the phone first and syncs when it
 * can (epic 9); nothing waits for the network.
 */
export default function LogToday() {
  const { t } = useTranslation();
  const { code } = useLocalSearchParams<{ code: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const modules = useModules(lang);
  const library = useElements();
  const observations = useObservations();
  const module = modules.data?.find((m) => m.code === code);
  const presentation = module?.presentation;
  const episodes = useEpisodes(episodeModel(module));
  const today = localDay();
  const before = useMemo(
    () => valuesOn(observations.data ?? [], today),
    [observations.data, today],
  );
  const [values, setValues] = useState<Record<string, Value>>({});
  const [busy, setBusy] = useState(false);

  const parts = sections(module, library.data, lang);
  const elements = parts.flatMap((p) => p.elements);
  const valueOf = (e: Element): Value | undefined =>
    e.code in values ? values[e.code] : (before.get(e.code)?.value as Value | undefined);
  const set = (code: string, value: Value) => setValues((v) => ({ ...v, [code]: value }));
  const changed = Object.keys(values).filter((k) => values[k] !== '' && values[k] !== undefined);

  const date = new Intl.DateTimeFormat(lang, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  }).format(new Date());
  const name = module ? moduleName(module, lang) : '';
  const day = dayLabel(module, lang, episodes.data?.currentDay);

  const save = async () => {
    setBusy(true);
    const now = new Date().toISOString();
    const entries: PushEntry[] = changed.map((c) => {
      const e = elements.find((x) => x.code === c)!;
      return {
        clientEntryId: newEntryId(),
        element: { code: e.code, version: e.version },
        observedOn: today,
        timeZone: timeZone(),
        value: values[c]!,
        recordedAt: now,
      };
    });
    await saveEntries(entries);
    setBusy(false);
    router.back();
  };

  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <Button
          label={presentation?.saveLabel?.[lang] || t('track.log.save')}
          disabled={busy || changed.length === 0}
          accessibilityState={{ disabled: busy || changed.length === 0, busy }}
          onPress={() => void save()}
        />
      }
    >
      <Text variant="meta" color={colors.inkMuted}>
        {day
          ? t('track.log.headerDay', { module: name, date, day })
          : t('track.log.header', { module: name, date })}
      </Text>
      <Text variant="titleLg" accessibilityRole="header">
        {presentation?.title?.[lang] || t('track.log.title')}
      </Text>

      {parts.map((part) => {
        const marker = (ev: Element) => (
          <Chip
            key={ev.code}
            label={helpOf(ev, lang) ?? nameOf(ev, lang)}
            on={valueOf(ev) === today}
            onToggle={() => set(ev.code, valueOf(ev) === today ? '' : today)}
          />
        );
        // Event markers of one group share one row, as period start and end do on the design.
        if (part.elements.every((e) => e.inputView === 'event_marker')) {
          return (
            <Field key={part.key} label={part.label ?? nameOf(part.elements[0]!, lang)}>
              <View style={styles.chips}>{part.elements.map(marker)}</View>
            </Field>
          );
        }
        const controls = part.elements.map((e) =>
          e.inputView === 'event_marker' ? (
            <View key={e.code} style={[styles.chips, styles.field]}>
              {marker(e)}
            </View>
          ) : (
            control(e)
          ),
        );
        if (!part.label) return controls;
        return (
          <View key={part.key}>
            <Text variant="label" style={[styles.bold, styles.field]}>
              {part.label}
            </Text>
            {controls}
          </View>
        );
      })}
    </FlowScreen>
  );

  function control(e: Element) {
    switch (e.inputView) {
      case 'scale_5':
      case 'choice_chips':
      case 'flow_calendar':
        return (
          <Field key={e.code} label={nameOf(e, lang)}>
            <View
              style={styles.chips}
              accessibilityRole="radiogroup"
              accessibilityLabel={nameOf(e, lang)}
            >
              {choicesOf(e, lang).map((c) => {
                const value = e.valueType === 'INT' ? Number(c.value) : c.value;
                return (
                  <Chip
                    key={c.value}
                    role="radio"
                    label={c.label}
                    on={valueOf(e) === value}
                    onToggle={() => set(e.code, value)}
                  />
                );
              })}
            </View>
          </Field>
        );
      case 'duration_stepper':
        return (
          <Stepper
            key={e.code}
            element={e}
            lang={lang}
            value={valueOf(e) as number | undefined}
            onChange={(v) => set(e.code, v)}
          />
        );
      case 'toggle':
        return (
          <SwitchRow
            key={e.code}
            title={nameOf(e, lang)}
            on={valueOf(e) === true}
            onToggle={() => set(e.code, valueOf(e) !== true)}
          />
        );
      case 'free_text':
        return (
          <Field key={e.code} label={nameOf(e, lang)} optional>
            <TextInput
              multiline
              maxLength={e.constraints.max ?? 4000}
              accessibilityLabel={nameOf(e, lang)}
              value={String(valueOf(e) ?? '')}
              onChangeText={(text) => set(e.code, text)}
              style={styles.note}
            />
            <Text variant="meta" color={colors.inkMuted}>
              {helpOf(e, lang) ?? t('track.log.notePrivate')}
            </Text>
          </Field>
        );
      default:
        return null;
    }
  }
}

function Field({
  label,
  optional,
  children,
}: {
  label: string;
  optional?: boolean;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <View style={styles.field}>
      <Text variant="label" style={styles.bold}>
        {label}
        {optional ? (
          <Text variant="label" color={colors.inkMuted}>
            {' '}
            {t('track.log.optional')}
          </Text>
        ) : null}
      </Text>
      {children}
    </View>
  );
}

function Stepper({
  element: e,
  lang,
  value,
  onChange,
}: {
  element: Element;
  lang: 'en' | 'fr';
  value: number | undefined;
  onChange: (v: number) => void;
}) {
  const { t } = useTranslation();
  const step = e.constraints.step ?? 15;
  const min = e.constraints.min ?? 0;
  const max = e.constraints.max ?? 1440;
  // Untouched, the stepper starts from 7 hours, a common night, without saving it.
  const shown = value ?? 420;
  const hm = hoursAndMinutes(shown);
  const label = t('track.log.hours', { h: hm.h, m: hm.m });
  return (
    <Field label={nameOf(e, lang)}>
      <View style={styles.stepper}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`${t('track.log.decrease')}, ${nameOf(e, lang)}`}
          onPress={() => onChange(Math.max(min, shown - step))}
          style={styles.stepButton}
        >
          <Minus size={20} strokeWidth={1.8} color={colors.ink} />
        </Pressable>
        <Text
          variant="titleSm"
          style={styles.stepValue}
          color={value === undefined ? colors.inkMuted : colors.ink}
          accessibilityLiveRegion="polite"
        >
          {label}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`${t('track.log.increase')}, ${nameOf(e, lang)}`}
          onPress={() => onChange(Math.min(max, shown + step))}
          style={styles.stepButton}
        >
          <Plus size={20} strokeWidth={1.8} color={colors.ink} />
        </Pressable>
      </View>
    </Field>
  );
}

const styles = StyleSheet.create({
  field: { gap: space.space8, marginTop: space.space16 },
  bold: { fontWeight: '600' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.space8 },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: space.space12 },
  stepButton: {
    width: size.sizeTap,
    height: size.sizeTap,
    borderRadius: radius.radiusPill,
    borderWidth: 1,
    borderColor: colors.lineControl,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepValue: { minWidth: 96, textAlign: 'center' },
  note: {
    minHeight: 96,
    padding: space.space12,
    borderRadius: radius.radius12,
    borderWidth: 1,
    borderColor: colors.lineControl,
    color: colors.ink,
    textAlignVertical: 'top',
  },
});
