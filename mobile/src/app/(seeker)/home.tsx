import AsyncStorage from '@react-native-async-storage/async-storage';
import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { CloudOff, Search } from 'lucide-react-native';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

import { useMe, useSessionLanguages } from '@/api/account';
import { type Booking, useMyBookings } from '@/api/bookings';
import { useMyHomework } from '@/api/care';
import { useFlags } from '@/config/flags';
import i18n from '@/i18n';
import { HomeTracking } from '@/tracking/HomeTracking';
import { joinOpensAt, longWhen } from '@/state/bookingView';
import { timeOf } from '@/state/format';
import { useNow } from '@/state/useNow';
import { Button } from '@/ui/Button';
import { CrisisButton } from '@/ui/CrisisButton';
import { Text } from '@/ui/Text';

type Snapshot = { next: Booking | null; at: string | null };

/** The last next session Home showed, so Home still says something offline (StateOfflineHome). */
export const useHomeSnapshot = create<Snapshot & { keep: (next: Booking | null) => void }>()(
  persist(
    (set) => ({
      next: null,
      at: null,
      keep: (next) => set({ next, at: new Date().toISOString() }),
    }),
    {
      name: 'solace.home',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: ({ next, at }) => ({ next, at }),
    },
  ),
);

/** "Good morning" until noon, "Good afternoon" until 18:00, then "Good evening". */
function greetingKey(now: Date): string {
  const h = now.getHours();
  return h < 12 ? 'home.morning' : h < 18 ? 'home.afternoon' : 'home.evening';
}

/**
 * Seeker home (Home.dc.html, StateOfflineHome.dc.html). Phase 1 shows sessions only: the next one,
 * with joining and rescheduling, and the way to book. With tracking on (Phase 2) the newest
 * pattern, a one-tap mood check-in and the tracked modules follow; Phase 4 adds the homework the
 * specialist set and the safety plan, behind the companion flag (D57).
 */
export default function Home() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const now = new Date(useNow());
  const me = useMe();
  const upcoming = useMyBookings('UPCOMING');
  const languages = useSessionLanguages(lang);
  const snapshot = useHomeSnapshot();
  const companion = useFlags((f) => f.companion);
  const trackTab = useFlags((f) => f.trackTab);

  const live = upcoming.data?.find((b) => b.status === 'CONFIRMED' || b.status === 'IN_PROGRESS');
  useEffect(() => {
    if (upcoming.data) useHomeSnapshot.getState().keep(live ?? null);
  }, [upcoming.data, live]);

  const offline = upcoming.isError;
  const next = offline ? snapshot.next : live;
  const name = me.data?.displayName;
  const nameOf = (code: string) => languages.data?.find((l) => l.code === code)?.name ?? code;
  const day = new Intl.DateTimeFormat(lang, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(now);

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={upcoming.isRefetching}
            onRefresh={() => void upcoming.refetch()}
          />
        }
      >
        {offline ? (
          <View style={styles.offline} accessibilityRole="alert">
            <CloudOff size={20} strokeWidth={1.8} color={colors.warningInk} aria-hidden />
            <View style={styles.flex}>
              <Text variant="label" color={colors.warningInk} style={styles.bold}>
                {t('home.offlineTitle')}
              </Text>
              <Text variant="meta" color={colors.warningInk}>
                {t('home.offlineBody')}
              </Text>
            </View>
          </View>
        ) : null}

        <Text variant="meta" color={colors.inkMuted}>
          {day.charAt(0).toUpperCase() + day.slice(1)}
        </Text>
        <Text variant="titleLg" accessibilityRole="header">
          {name ? t(greetingKey(now), { name }) : t(`${greetingKey(now)}Plain`)}
        </Text>

        <CrisisButton label={t('crisis.button')} onPress={() => router.push('/crisis')} />

        {next ? (
          <NextSession
            booking={next}
            lang={lang}
            language={nameOf(next.sessionLanguage)}
            now={now}
            offline={offline}
            updatedAt={offline ? snapshot.at : null}
          />
        ) : upcoming.isPending && !offline ? null : (
          <View style={styles.card}>
            <Search size={22} strokeWidth={1.8} color={colors.brand} aria-hidden />
            <Text variant="titleSm" accessibilityRole="header">
              {t('sessions.emptyTitle')}
            </Text>
            <Text variant="body" color={colors.inkMuted}>
              {t('sessions.emptyBody')}
            </Text>
            <Button
              label={t('sessions.emptyAction')}
              onPress={() => router.push('/search')}
              disabled={offline}
            />
          </View>
        )}

        {trackTab ? <HomeTracking lang={lang} /> : null}

        {companion ? <BetweenSessions lang={lang} /> : null}

        {offline ? (
          <Text variant="meta" color={colors.inkMuted} style={styles.center}>
            {t('home.emergencyOffline')}
          </Text>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function NextSession({
  booking: b,
  lang,
  language,
  now,
  offline,
  updatedAt,
}: {
  booking: Booking;
  lang: string;
  language: string;
  now: Date;
  offline: boolean;
  updatedAt: string | null;
}) {
  const { t } = useTranslation();
  const opens = joinOpensAt(b);
  const canJoin = !offline && (b.status === 'IN_PROGRESS' || now >= opens);
  const withLine = t('home.with', { name: b.specialist.fullName });
  return (
    <View
      style={styles.card}
      accessibilityLabel={`${t('home.next')}, ${longWhen(b.start, lang)}, ${withLine}`}
    >
      <Text variant="meta" color={colors.inkMuted} style={styles.bold}>
        {t('home.next').toUpperCase()}
      </Text>
      {!offline ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('home.kind', { modality: t(`book.modality_${b.modality}`), language })}
        </Text>
      ) : null}
      <Text variant="titleSm">{longWhen(b.start, lang)}</Text>
      <Text variant="body" color={colors.inkSoft}>
        {updatedAt
          ? t('home.withUpdated', {
              name: b.specialist.fullName,
              time: timeOf(new Date(updatedAt), lang),
            })
          : withLine}
      </Text>
      {canJoin ? (
        <Button
          label={t('home.join')}
          onPress={() => router.push({ pathname: '/call/[id]', params: { id: b.id } })}
        />
      ) : !offline ? (
        <Text variant="meta" color={colors.brand} style={styles.bold}>
          {t('home.joinOpens', { time: timeOf(opens, lang) })}
        </Text>
      ) : null}
      {offline ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('home.rescheduleOffline')}
        </Text>
      ) : (
        <Button
          variant="secondary"
          label={t('home.reschedule')}
          onPress={() => router.push({ pathname: '/session/change', params: { id: b.id } })}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  between: { gap: space.space8 },
  homework: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space12,
  },
  offline: {
    flexDirection: 'row',
    gap: space.space10,
    padding: space.space12,
    borderRadius: radius.radius12,
    borderWidth: 1,
    borderColor: colors.warning,
    backgroundColor: colors.surfaceCard,
  },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontWeight: '700',
  },
  center: {
    textAlign: 'center',
  },
});

/** Homework from the specialist and the safety plan, between sessions (Phase 4, D57). */
function BetweenSessions({ lang }: { lang: string }) {
  const { t } = useTranslation();
  const homework = useMyHomework(true);
  const open = (homework.data ?? []).filter((h) => h.done < h.target);
  return (
    <View style={styles.between}>
      {open.length > 0 ? (
        <Text variant="label" color={colors.inkMuted} accessibilityRole="header">
          {t('homework.homeTitle')}
        </Text>
      ) : null}
      {open.map((h) => (
        <Pressable
          key={h.id}
          accessibilityRole="button"
          onPress={() => router.push({ pathname: '/homework/[id]', params: { id: h.id } })}
          style={styles.homework}
        >
          <View style={styles.flex}>
            <Text variant="label" style={styles.bold}>
              {t('homework.progress', {
                exercise:
                  h.exercise === 'CUSTOM' ? (h.customTask ?? '') : t(`homework.${h.exercise}`),
                done: h.done,
                target: h.target,
              })}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {new Intl.DateTimeFormat(lang, {
                weekday: 'short',
                day: 'numeric',
                month: 'short',
              }).format(new Date(h.dueBy))}
            </Text>
          </View>
          <Text variant="label" color={colors.brand}>
            {t('homework.open')}
          </Text>
        </Pressable>
      ))}
      <Button
        variant="secondary"
        label={t('safetyPlan.title')}
        onPress={() => router.push('/safety-plan')}
      />
    </View>
  );
}
