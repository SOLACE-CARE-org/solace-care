import { colors, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  useInviteMatches,
  useModules,
  useRecommendationAction,
  useRecommendations,
} from '@/api/tracking';
import i18n from '@/i18n';
import { longWhen } from '@/state/bookingView';
import { moduleName } from '@/tracking/view';
import { Avatar } from '@/ui/Avatar';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

type CycleEvidence = { start: string; value: number | string };

/**
 * A pattern or an invitation (Insights.dc.html). It states what was logged against the rule's
 * threshold, never a diagnosis. An invitation lists matched specialists; booking one answers it.
 */
export default function RecommendationScreen() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const recommendations = useRecommendations(lang);
  const modules = useModules(lang);
  const action = useRecommendationAction();
  const r = recommendations.data?.find((x) => x.id === id);
  const invite = r?.outcome === 'INVITE';
  const matches = useInviteMatches(id, lang, invite);
  const m = modules.data?.find((x) => x.code === r?.moduleCode);

  useEffect(() => {
    if (r && r.status === 'NEW') action.mutate({ id: r.id, action: 'seen' });
    // Marked seen once, when it is first shown.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [r?.id]);

  if (!r) {
    return (
      <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
        <View style={styles.top}>
          <BackButton />
        </View>
        <View style={styles.content}>
          <Text variant="body" color={colors.inkMuted}>
            {recommendations.isError ? t('common.checkConnection') : t('common.loading')}
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  const evidence = r.evidence as { cycles?: CycleEvidence[]; threshold?: number };
  const cycles = (evidence.cycles ?? []).filter((c) => typeof c.value === 'number') as {
    start: string;
    value: number;
  }[];
  const guide = typeof evidence.threshold === 'number' ? evidence.threshold : undefined;
  const top = Math.max(guide ?? 0, ...cycles.map((c) => c.value), 1);
  const specialty = matches.data?.specialties[0]?.label;
  const close = (kind: 'snooze' | 'dismissal') =>
    action.mutate({ id: r.id, action: kind }, { onSuccess: () => router.back() });

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="meta" color={colors.inkMuted} style={styles.bold}>
          {(m ? moduleName(m, lang) : r.moduleCode).toUpperCase()}
        </Text>
        <Text variant="titleLg" accessibilityRole="header">
          {invite && specialty
            ? t('track.recommendation.invite', { specialty: specialty.toLocaleLowerCase(lang) })
            : t('track.recommendation.pattern')}
        </Text>

        {r.dataChanged ? (
          <View style={styles.notice} accessibilityRole="alert">
            <Text variant="body" color={colors.warningInk}>
              {t('track.recommendation.dataChanged')}
            </Text>
          </View>
        ) : null}

        {cycles.length > 0 ? (
          <View
            style={styles.card}
            accessible
            accessibilityLabel={`${t('track.recommendation.lastCycles', { n: cycles.length })}: ${cycles
              .map((c) => c.value)
              .join(', ')} ${t('track.recommendation.days')}`}
          >
            <Text variant="titleSm">
              {t('track.recommendation.lastCycles', { n: cycles.length })}
            </Text>
            <View style={styles.chart} aria-hidden>
              {guide !== undefined ? (
                <View style={[styles.guide, { bottom: `${(guide / top) * 100}%` }]}>
                  <Text variant="meta" color={colors.inkMuted} style={styles.guideLabel}>
                    {t('track.recommendation.guide', { n: guide })}
                  </Text>
                </View>
              ) : null}
              {cycles.map((c) => (
                <View key={c.start} style={styles.column}>
                  <Text variant="meta" style={styles.bold}>
                    {c.value}
                  </Text>
                  <View style={[styles.bar, { height: `${(c.value / top) * 80}%` }]} />
                  <Text variant="meta" color={colors.inkMuted}>
                    {new Intl.DateTimeFormat(lang, { month: 'short' }).format(
                      new Date(`${c.start}T12:00:00`),
                    )}
                  </Text>
                </View>
              ))}
            </View>
          </View>
        ) : null}

        <View style={styles.card}>
          <Text variant="titleSm">{t('track.recommendation.why')}</Text>
          <Text variant="body">{r.message}</Text>
          <Text variant="meta" color={colors.inkMuted}>
            {r.aiWorded ? t('track.recommendation.aiWorded') : t('track.recommendation.reviewed')}
          </Text>
        </View>

        {invite ? (
          matches.data && matches.data.specialists.length === 0 ? (
            <View style={styles.card}>
              <Text variant="body">{t('track.recommendation.noMatches')}</Text>
              <Button
                variant="secondary"
                label={t('track.recommendation.searchAll')}
                onPress={() => router.push('/search')}
              />
            </View>
          ) : (
            (matches.data?.specialists ?? []).slice(0, 3).map((s) => (
              <Pressable
                key={s.id}
                accessibilityRole="button"
                onPress={() =>
                  router.push({
                    pathname: '/book/[id]',
                    params: { id: s.id, recommendationId: r.id },
                  })
                }
                style={[styles.card, styles.match]}
              >
                <Avatar name={s.fullName} />
                <View style={styles.flex}>
                  <Text variant="label" style={styles.bold}>
                    {s.fullName}
                  </Text>
                  <Text variant="meta" color={colors.inkMuted}>
                    {s.nextAvailable
                      ? t('track.recommendation.next', {
                          specialty: s.professionLabel,
                          slot: longWhen(s.nextAvailable, lang),
                        })
                      : s.professionLabel}
                  </Text>
                </View>
              </Pressable>
            ))
          )
        ) : null}

        <View style={styles.actions}>
          {invite && matches.data?.specialists[0] ? (
            <Button
              label={t('track.recommendation.book')}
              onPress={() =>
                router.push({
                  pathname: '/book/[id]',
                  params: { id: matches.data!.specialists[0]!.id, recommendationId: r.id },
                })
              }
            />
          ) : null}
          <Button
            variant="secondary"
            label={t('track.recommendation.later')}
            disabled={action.isPending}
            onPress={() => close('snooze')}
          />
          <Button
            variant="quiet"
            label={t('track.recommendation.dismiss')}
            disabled={action.isPending}
            onPress={() => close('dismissal')}
          />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  content: { padding: space.space20, gap: space.space16 },
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  notice: {
    padding: space.space12,
    borderRadius: radius.radius12,
    backgroundColor: colors.warningTint,
  },
  chart: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    height: 140,
    gap: space.space16,
    paddingTop: space.space16,
  },
  column: {
    flex: 1,
    height: '100%',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: space.space4,
  },
  bar: { width: '70%', borderRadius: radius.radius6, backgroundColor: colors.trackPink },
  guide: {
    position: 'absolute',
    left: 0,
    right: 0,
    borderTopWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.inkMuted,
  },
  guideLabel: { position: 'absolute', right: 0, top: -18 },
  match: { flexDirection: 'row', alignItems: 'center', gap: space.space12 },
  actions: { gap: space.space8 },
});
