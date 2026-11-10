import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { ChartLine, CloudOff, Sparkles } from 'lucide-react-native';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useConsents } from '@/api/account';
import {
  type CatalogueModule,
  useCycles,
  useModules,
  useObservations,
  useRecommendations,
} from '@/api/tracking';
import i18n from '@/i18n';
import { timeOf } from '@/state/format';
import { startSync, useOutbox } from '@/tracking/outbox';
import { localDay, moduleName, valuesOn } from '@/tracking/view';
import { Button } from '@/ui/Button';
import { CrisisButton } from '@/ui/CrisisButton';
import { Pill } from '@/ui/Pill';
import { Text } from '@/ui/Text';

/**
 * The Track tab (TrackHub.dc.html, StateTrackEmpty.dc.html, StateLogSavedOffline.dc.html; Phase 2):
 * open patterns and invitations, the modules the person tracks with today's state, and what is
 * still waiting on this phone to sync. Only the person sees what they log.
 */
export default function Track() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const consents = useConsents();
  const consented = consents.data?.consents.some((c) => c.purpose === 'TRACKING' && c.granted);
  const modules = useModules(lang, consented === true);
  const recommendations = useRecommendations(lang, consented === true);
  const observations = useObservations(consented === true);
  const cycles = useCycles(consented === true);
  const pending = useOutbox((s) => s.pending);

  useEffect(() => startSync(), []);

  const today = localDay();
  const enrolled = (modules.data ?? []).filter((m) => m.enrolled);
  const logged = valuesOn(observations.data ?? [], today);
  const waiting = pending.filter((p) => p.observedOn === today);
  const offline = modules.isError && !modules.data;
  const day = new Intl.DateTimeFormat(lang, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(new Date());

  const refresh = () => {
    void modules.refetch();
    void recommendations.refetch();
    void observations.refetch();
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={modules.isRefetching} onRefresh={refresh} />}
      >
        <Text variant="meta" color={colors.inkMuted}>
          {day.charAt(0).toUpperCase() + day.slice(1)}
        </Text>
        <Text variant="titleLg" accessibilityRole="header">
          {t('track.title')}
        </Text>

        {offline ? (
          <View style={styles.banner} accessibilityRole="alert">
            <CloudOff size={20} strokeWidth={1.8} color={colors.warningInk} aria-hidden />
            <Text variant="label" color={colors.warningInk} style={styles.flex}>
              {t('home.offlineTitle')}
            </Text>
          </View>
        ) : null}

        {waiting.length > 0 ? (
          <View style={styles.card} accessibilityLiveRegion="polite">
            <Pill label={t('track.waitingToSync')} tone="warning" />
            <Text variant="body">
              {t('track.loggedOnPhone', {
                time: timeOf(new Date(waiting[waiting.length - 1]!.savedAt), lang),
              })}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('track.savedOffline')}
            </Text>
          </View>
        ) : null}

        {consents.data && !consented ? (
          <View style={styles.card}>
            <Text variant="titleSm" accessibilityRole="header">
              {t('track.consentTitle')}
            </Text>
            <Text variant="body" color={colors.inkMuted}>
              {t('track.consentBody')}
            </Text>
            <Button label={t('track.consentAction')} onPress={() => router.push('/privacy')} />
          </View>
        ) : null}

        {(recommendations.data ?? []).map((r) => {
          const m = modules.data?.find((x) => x.code === r.moduleCode);
          return (
            <Pressable
              key={r.id}
              accessibilityRole="button"
              onPress={() =>
                router.push({ pathname: '/track/recommendation/[id]', params: { id: r.id } })
              }
              style={[styles.card, styles.recommendation]}
            >
              <Sparkles size={20} strokeWidth={1.8} color={colors.brand} aria-hidden />
              <View style={styles.flex}>
                <Text variant="label" style={styles.bold}>
                  {r.message}
                </Text>
                <Text variant="meta" color={colors.inkMuted}>
                  {t('track.seePattern', { module: m ? moduleName(m, lang) : r.moduleCode })}
                </Text>
              </View>
            </Pressable>
          );
        })}

        {consented && modules.data && enrolled.length === 0 ? (
          <View style={styles.card}>
            <ChartLine size={22} strokeWidth={1.8} color={colors.brand} aria-hidden />
            <Text variant="titleSm" accessibilityRole="header">
              {t('track.emptyTitle')}
            </Text>
            <Text variant="body" color={colors.inkMuted}>
              {t('track.emptyBody')}
            </Text>
            <Button label={t('track.addModule')} onPress={() => router.push('/track/catalogue')} />
          </View>
        ) : null}

        {enrolled.length > 0 ? (
          <>
            <Text variant="titleSm" accessibilityRole="header" style={styles.section}>
              {t('track.yourModules')}
            </Text>
            {enrolled.map((m) => (
              <ModuleCard
                key={m.code}
                module={m}
                lang={lang}
                logged={
                  m.elements.some((b) => logged.has(b.code)) ||
                  waiting.some((p) => m.elements.some((b) => b.code === p.element))
                }
                cycleDay={
                  m.elements.some((b) => b.code === 'flow_intensity')
                    ? cycles.data?.cycleDay
                    : undefined
                }
              />
            ))}
            <Button
              variant="secondary"
              label={t('track.addModule')}
              onPress={() => router.push('/track/catalogue')}
            />
            <Button
              variant="secondary"
              label={t('track.about.title')}
              onPress={() => router.push('/track/about')}
            />
          </>
        ) : null}

        <CrisisButton label={t('crisis.button')} onPress={() => router.push('/crisis')} />
      </ScrollView>
    </SafeAreaView>
  );
}

export function ModuleCard({
  module: m,
  lang,
  logged,
  cycleDay,
}: {
  module: CatalogueModule;
  lang: 'en' | 'fr';
  logged: boolean;
  cycleDay: number | undefined;
}) {
  const { t } = useTranslation();
  const name = moduleName(m, lang);
  const state =
    cycleDay !== undefined
      ? t(logged ? 'track.cycleDayLogged' : 'track.cycleDayNotLogged', { n: cycleDay })
      : t(logged ? 'track.loggedToday' : 'track.notLoggedToday');
  return (
    <View style={[styles.card, { borderLeftColor: accent(m.accentColour) }, styles.module]}>
      <Text variant="titleSm" accessibilityRole="header">
        {name}
      </Text>
      <Text variant="meta" color={colors.inkMuted}>
        {state}
      </Text>
      <View style={styles.actions}>
        <Button
          label={t('track.logToday')}
          accessibilityLabel={`${t('track.logToday')}, ${name}`}
          onPress={() => router.push({ pathname: '/track/log/[code]', params: { code: m.code } })}
          style={styles.flex}
        />
        <Button
          variant="secondary"
          label={t('track.trends')}
          accessibilityLabel={`${t('track.trends')}, ${name}`}
          onPress={() =>
            router.push({ pathname: '/track/trends/[code]', params: { code: m.code } })
          }
          style={styles.flex}
        />
      </View>
    </View>
  );
}

/** Accent colours are design tokens (D51). */
export function accent(colour: string): string {
  switch (colour) {
    case 'track-pink':
      return colors.trackPink;
    case 'track-gold':
      return colors.trackGold;
    case 'track-blue':
      return colors.trackBlue;
    default:
      return colors.brand;
  }
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  content: { padding: space.space20, gap: space.space16 },
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
  section: { marginTop: space.space8 },
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
    padding: space.space12,
    borderRadius: radius.radius12,
    backgroundColor: colors.warningTint,
  },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  recommendation: { flexDirection: 'row', alignItems: 'center', gap: space.space12 },
  module: { borderLeftWidth: 4 },
  actions: { flexDirection: 'row', gap: space.space8, marginTop: space.space4 },
});
