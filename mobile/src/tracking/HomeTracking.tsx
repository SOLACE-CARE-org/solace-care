import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Sparkles } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { useConsents } from '@/api/account';
import {
  timeZone,
  useElements,
  useEpisodeDays,
  useModules,
  useObservations,
  useRecommendations,
} from '@/api/tracking';
import { ModuleCard } from '@/app/(seeker)/track';
import { newEntryId, saveEntries, useOutbox } from '@/tracking/outbox';
import { episodeModel } from '@/tracking/presentation';
import { choicesOf, localDay, moduleName, nameOf, valuesOn } from '@/tracking/view';
import { Chip } from '@/ui/Chip';
import { Text } from '@/ui/Text';

/**
 * Tracking on Home (Home.dc.html), Phase 2: the newest pattern or invitation, the one-tap element
 * of a tracked module that offers one (the check-in's mood; D61), and the tracked modules with
 * today's state. Shown
 * only with the trackTab flag and the TRACKING consent; logging works offline like the Track tab.
 */
export function HomeTracking({ lang }: { lang: 'en' | 'fr' }) {
  const { t } = useTranslation();
  const consents = useConsents();
  const consented =
    consents.data?.consents.some((c) => c.purpose === 'TRACKING' && c.granted) === true;
  const modules = useModules(lang, consented);
  const recommendations = useRecommendations(lang, consented);
  const observations = useObservations(consented);
  const library = useElements();
  const pending = useOutbox((s) => s.pending);
  const [mood, setMood] = useState<string | null>(null);
  const enrolled = (modules.data ?? []).filter((m) => m.enrolled);
  const days = useEpisodeDays(
    enrolled.map(episodeModel).filter((m): m is string => Boolean(m)),
    consented,
  );
  if (!consented) return null;

  const today = localDay();
  const logged = valuesOn(observations.data ?? [], today);
  const waiting = pending.filter((p) => p.observedOn === today);
  // The first tracked module offering a one-tap element shows it.
  const quick = enrolled.find((m) =>
    m.elements.some((b) => b.code === m.presentation?.quickLog),
  );
  const quickElement = library.data?.find((e) => e.code === quick?.presentation?.quickLog);
  // One tap needs a short list of answers, such as the mood scale.
  const moodElement =
    quickElement && choicesOf(quickElement, lang).length > 0 ? quickElement : undefined;
  const quickTitle = quick?.presentation?.quickLogTitle?.[lang];
  const loggedValue = moodElement ? logged.get(moodElement.code)?.value : undefined;
  const loggedMood = mood ?? (loggedValue !== undefined ? String(loggedValue) : null);
  const newest = (recommendations.data ?? [])[0];
  const newestModule = modules.data?.find((m) => m.code === newest?.moduleCode);

  const saveMood = async (value: string) => {
    if (!moodElement) return;
    setMood(value);
    await saveEntries([
      {
        clientEntryId: newEntryId(),
        element: { code: moodElement.code, version: moodElement.version },
        observedOn: today,
        timeZone: timeZone(),
        value: moodElement.valueType === 'INT' ? Number(value) : value,
        recordedAt: new Date().toISOString(),
      },
    ]);
  };

  return (
    <View style={styles.section}>
      {newest ? (
        <Pressable
          accessibilityRole="button"
          onPress={() =>
            router.push({ pathname: '/track/recommendation/[id]', params: { id: newest.id } })
          }
          style={[styles.card, styles.row]}
        >
          <Sparkles size={20} strokeWidth={1.8} color={colors.brand} aria-hidden />
          <View style={styles.flex}>
            <Text variant="label" style={styles.bold}>
              {newest.message}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('track.seePattern', {
                module: newestModule ? moduleName(newestModule, lang) : newest.moduleCode,
              })}
            </Text>
          </View>
        </Pressable>
      ) : null}

      {moodElement ? (
        <View style={styles.card}>
          <Text variant="titleSm" accessibilityRole="header">
            {quickTitle || nameOf(moodElement, lang)}
          </Text>
          <View style={styles.chips}>
            {choicesOf(moodElement, lang).map((c) => (
              <Chip
                key={c.value}
                role="radio"
                label={c.label}
                on={loggedMood === c.value}
                onToggle={() => void saveMood(c.value)}
              />
            ))}
          </View>
          {loggedMood ? (
            <Text variant="meta" color={colors.inkMuted} accessibilityLiveRegion="polite">
              {t('home.feelingSaved')}
            </Text>
          ) : null}
        </View>
      ) : null}

      {enrolled.map((m) => (
        <ModuleCard
          key={m.code}
          module={m}
          lang={lang}
          logged={
            m.elements.some((b) => logged.has(b.code)) ||
            waiting.some((p) => m.elements.some((b) => b.code === p.element))
          }
          day={days.get(episodeModel(m) ?? '')}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { gap: space.space12 },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.space12 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.space8 },
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
});
