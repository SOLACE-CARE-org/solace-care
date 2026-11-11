import { colors, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  type Element,
  type EpisodeSummary,
  type Observation,
  useElements,
  useEpisodes,
  useLeave,
  useModules,
  useObservations,
  useSetReminder,
} from '@/api/tracking';
import i18n from '@/i18n';
import {
  choicesOf,
  daysBack,
  localDay,
  moduleName,
  nameOf,
  RANGES,
  type Range,
} from '@/tracking/view';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';

import { episodeModel } from '@/tracking/presentation';

import { accent } from '../../(seeker)/track';

const CHARTED = new Set(['INT', 'MINUTES', 'ENUM', 'BOOL']);

/**
 * Trends (Trends.dc.html): what the person logged over 2 weeks, a month or 3 months, as facts. No
 * score, no forecast (D50). What is charted, in order, is the module's presentation (D61): a daily
 * element as bars, an episode metric as the last episodes; without one, every element it can. Notes are counted, never shown here; the reminder and leaving the
 * module live here too.
 */
export default function Trends() {
  const { t } = useTranslation();
  const { code } = useLocalSearchParams<{ code: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const modules = useModules(lang);
  const library = useElements();
  const observations = useObservations();
  const leave = useLeave();
  const reminder = useSetReminder();
  const [range, setRange] = useState<Range>('TWO_WEEKS');
  const module = modules.data?.find((m) => m.code === code);
  const model = episodeModel(module);
  const episodes = useEpisodes(model);

  const days = RANGES[range];
  const from = daysBack(days);
  const today = localDay();
  const inRange = (observations.data ?? []).filter(
    (o) => o.observedOn >= from && o.observedOn <= today,
  );
  const elements = (module?.elements ?? [])
    .map((b) => library.data?.find((e) => e.code === b.code))
    .filter((e): e is Element => Boolean(e));
  const notes = elements.find((e) => e.valueType === 'TEXT');
  const trends: string[] =
    module?.presentation?.trends ??
    elements.filter((e) => CHARTED.has(e.valueType)).map((e) => `daily_value:${e.code}`);
  const historyTitle = module?.presentation?.episode?.historyTitle?.[lang];
  const name = module ? moduleName(module, lang) : '';
  const reminderTime = module?.reminderTime?.slice(0, 5);
  const color = accent(module?.accentColour ?? 'brand');

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('track.trend.title', { module: name })}
        </Text>
        <Segmented<Range>
          label={t('track.trend.range')}
          value={range}
          onChange={setRange}
          options={(Object.keys(RANGES) as Range[]).map((r) => ({
            value: r,
            label: t(`track.trend.ranges.${r}`),
          }))}
        />

        {observations.isError ? (
          <View style={styles.card}>
            <Text variant="body">{t('common.checkConnection')}</Text>
            <Button
              variant="secondary"
              label={t('common.tryAgain')}
              onPress={() => void observations.refetch()}
            />
          </View>
        ) : null}

        {trends.map((key) => {
          if (key.startsWith('daily_value:')) {
            const e = elements.find((x) => `daily_value:${x.code}` === key);
            if (!e || !CHARTED.has(e.valueType)) return null;
            return (
              <Chart
                key={key}
                element={e}
                lang={lang}
                days={days}
                from={from}
                observations={inRange}
                color={color}
              />
            );
          }
          const [m, metric] = key.split('.');
          if (m !== model || !metric || !episodes.data) return null;
          return (
            <History
              key={key}
              title={historyTitle}
              summary={episodes.data}
              metric={metric}
              lang={lang}
            />
          );
        })}

        {notes ? (
          <View style={styles.card}>
            <Text variant="titleSm">{t('track.trend.notes')}</Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t(`track.trend.notesCount.${range}`, {
                n: inRange.filter((o) => o.element.code === notes.code).length,
              })}
            </Text>
          </View>
        ) : null}

        {module ? (
          <SwitchRow
            title={t('track.trend.reminder')}
            meta={
              reminderTime
                ? t('track.trend.reminderLine', { time: reminderTime })
                : t('track.trend.reminderOff')
            }
            on={Boolean(reminderTime)}
            onToggle={() =>
              reminder.mutate({
                code: module.code,
                reminderTime: reminderTime
                  ? null
                  : (module.reminderDefault?.slice(0, 5) ?? '20:00'),
              })
            }
          />
        ) : null}

        {module?.enrolled ? (
          <View style={styles.leave}>
            <Button
              variant="quiet"
              label={t('track.trend.leave', { module: name })}
              disabled={leave.isPending}
              onPress={() => leave.mutate(module.code, { onSuccess: () => router.back() })}
            />
            <Text variant="meta" color={colors.inkMuted}>
              {t('track.trend.leaveNote')}
            </Text>
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

/** The last complete episodes, one line each, with the metric's value (D50: facts only). */
function History({
  title,
  summary,
  metric,
  lang,
}: {
  title: string | undefined;
  summary: EpisodeSummary;
  metric: string;
  lang: 'en' | 'fr';
}) {
  const { t } = useTranslation();
  const done = summary.episodes
    .filter((ep) => ep.complete && ep.metrics[metric] !== undefined)
    .slice(-6);
  if (done.length === 0) return null;
  return (
    <View style={styles.card}>
      {title ? (
        <Text variant="titleSm" accessibilityRole="header">
          {title}
        </Text>
      ) : null}
      {done.map((ep) => (
        <View key={ep.start} style={styles.row}>
          <Text variant="body">
            {new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short' }).format(
              new Date(`${ep.start}T12:00:00`),
            )}
          </Text>
          <Text variant="body" style={styles.bold}>
            {metric.endsWith('_days')
              ? t('track.trend.cycleLine', { days: ep.metrics[metric] })
              : String(ep.metrics[metric])}
          </Text>
        </View>
      ))}
    </View>
  );
}

/** One bar per day; the height is the value's place on its scale. */
function Chart({
  element: e,
  lang,
  days,
  from,
  observations,
  color,
}: {
  element: Element;
  lang: 'en' | 'fr';
  days: number;
  from: string;
  observations: Observation[];
  color: string;
}) {
  const { t } = useTranslation();
  const byDay = new Map<string, Observation>();
  for (const o of observations) {
    if (o.element.code !== e.code) continue;
    const seen = byDay.get(o.observedOn);
    if (!seen || seen.recordedAt < o.recordedAt) byDay.set(o.observedOn, o);
  }
  const choices = choicesOf(e, lang);
  const level = (o: Observation | undefined): number | null => {
    if (!o) return null;
    if (e.valueType === 'BOOL') return o.value === true ? 1 : 0.1;
    if (e.valueType === 'MINUTES') return Math.min(1, Number(o.value) / 720);
    const i = choices.findIndex((c) => c.value === String(o.value));
    return i < 0 ? null : (i + 1) / choices.length;
  };
  const start = new Date(`${from}T12:00:00`);
  const bars = Array.from({ length: days }, (_, i) => {
    const d = new Date(start);
    d.setDate(start.getDate() + i);
    return level(byDay.get(localDay(d)));
  });
  const logged = bars.filter((b) => b !== null).length;
  const scale =
    e.valueType === 'MINUTES'
      ? t('track.trend.hours')
      : choices.length > 1
        ? t('track.trend.scale', {
            low: choices[0]!.label,
            high: choices[choices.length - 1]!.label,
          })
        : '';

  return (
    <View
      style={styles.card}
      accessible
      accessibilityLabel={`${nameOf(e, lang)}, ${t('track.trend.days', { n: logged, total: days })}`}
    >
      <View style={styles.row}>
        <Text variant="titleSm">{nameOf(e, lang)}</Text>
        <Text variant="meta" color={colors.inkMuted}>
          {scale}
        </Text>
      </View>
      {logged === 0 ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('track.trend.noData')}
        </Text>
      ) : (
        <View style={styles.chart} aria-hidden>
          {bars.map((b, i) => (
            <View key={i} style={styles.slot}>
              {b !== null ? (
                <View
                  style={[
                    styles.bar,
                    { height: `${Math.round(b * 100)}%`, backgroundColor: color },
                  ]}
                />
              ) : null}
            </View>
          ))}
        </View>
      )}
      <View style={styles.row} aria-hidden>
        <Text variant="meta" color={colors.inkMuted}>
          {new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short' }).format(start)}
        </Text>
        <Text variant="meta" color={colors.inkMuted}>
          {t('track.trend.today')}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  content: { padding: space.space20, gap: space.space16 },
  bold: { fontWeight: '600' },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  chart: { flexDirection: 'row', alignItems: 'flex-end', height: 96, gap: 2 },
  slot: {
    flex: 1,
    height: '100%',
    justifyContent: 'flex-end',
    backgroundColor: colors.surfaceSunken,
    borderRadius: 2,
  },
  bar: { width: '100%', borderRadius: 2 },
  leave: { gap: space.space4, alignItems: 'center', marginTop: space.space8 },
});
