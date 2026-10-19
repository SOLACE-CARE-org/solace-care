import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { CalendarDays } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { type Booking, useMyBookings } from '@/api/bookings';
import { useApplication } from '@/api/specialist';
import { useFlags } from '@/config/flags';
import i18n from '@/i18n';
import { joinOpensAt, longWhen, toneOf } from '@/state/bookingView';
import { capitalised, timeOf } from '@/state/format';
import { useNow } from '@/state/useNow';
import { Button } from '@/ui/Button';
import { Pill } from '@/ui/Pill';
import { Text } from '@/ui/Text';

function sameDay(iso: string, now: number): boolean {
  const a = new Date(iso);
  const b = new Date(now);
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

/**
 * Today (SpecialistToday.dc.html, StateSpecialistQuietDay.dc.html), Phase 1: the next session with
 * Join from 10 minutes before, then the rest of the day. The pre-session brief (Phase 3) opens from
 * the next session while AI assist is on; note drafts (Phase 4) join this screen later.
 */
export default function SpecialistToday() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const now = useNow(30_000);
  const application = useApplication();
  const upcoming = useMyBookings('UPCOMING');
  const past = useMyBookings('PAST');
  const languages = useSessionLanguages(lang);
  const nameOf = (code: string) => languages.data?.find((l) => l.code === code)?.name ?? code;

  if (upcoming.isPending || past.isPending) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
      </SafeAreaView>
    );
  }
  if (upcoming.isError || past.isError) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <Text variant="body" color={colors.inkMuted}>
          {t('common.checkConnection')}
        </Text>
        <Button
          label={t('common.tryAgain')}
          onPress={() => {
            void upcoming.refetch();
            void past.refetch();
          }}
        />
      </SafeAreaView>
    );
  }

  const live = (upcoming.data ?? []).filter((b) => b.status !== 'PENDING_PAYMENT');
  const todayNext = live.filter((b) => sameDay(b.start, now));
  const next = todayNext[0];
  const later = todayNext.slice(1);
  const earlier = (past.data ?? []).filter((b) => sameDay(b.start, now)).reverse();
  const date = capitalised(
    new Intl.DateTimeFormat(lang, { weekday: 'long', day: 'numeric', month: 'long' }).format(
      new Date(now),
    ),
  );

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={upcoming.isRefetching || past.isRefetching}
            onRefresh={() => {
              void upcoming.refetch();
              void past.refetch();
            }}
          />
        }
      >
        <View>
          <Text variant="meta" color={colors.inkMuted}>
            {date}
          </Text>
          <Text variant="titleLg" accessibilityRole="header">
            {t('today.greeting', { name: application.data?.fullName ?? '' })}
          </Text>
        </View>

        {next ? (
          <UpNext booking={next} language={nameOf(next.sessionLanguage)} lang={lang} now={now} />
        ) : (
          <View style={styles.quiet}>
            <View style={styles.quietIcon} aria-hidden>
              <CalendarDays size={28} strokeWidth={1.8} color={colors.brand} />
            </View>
            <Text variant="titleSm" accessibilityRole="header">
              {t('today.quietTitle')}
            </Text>
            <Text variant="body" color={colors.inkMuted} style={styles.centerText}>
              {live[0]
                ? t('today.quietNext', { when: longWhen(live[0].start, lang) })
                : t('today.quietNone')}
            </Text>
            <Button
              variant="secondary"
              label={t('today.openCalendar')}
              onPress={() => router.push('/calendar')}
            />
          </View>
        )}

        {later.length > 0 ? (
          <Section title={t('today.later')} items={later} nameOf={nameOf} lang={lang} />
        ) : null}
        {earlier.length > 0 ? (
          <Section title={t('today.earlier')} items={earlier} nameOf={nameOf} lang={lang} />
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function UpNext({
  booking: b,
  language,
  lang,
  now,
}: {
  booking: Booking;
  language: string;
  lang: string;
  now: number;
}) {
  const { t } = useTranslation();
  const opens = joinOpensAt(b);
  const open = now >= opens.getTime();
  const who = b.seeker?.label ?? '';
  const aiAssist = useFlags((f) => f.aiAssist);
  return (
    <View style={styles.next}>
      <Text variant="meta" color={colors.brandTintStrong} style={styles.bold}>
        {t('today.upNext', { time: timeOf(new Date(b.start), lang) })}
      </Text>
      <Text variant="titleMd" color={colors.onBrand}>
        {b.sessionNumber && b.sessionNumber > 1
          ? t('today.who', {
              name: who,
              nth: t('session.together', { count: b.sessionNumber, ordinal: true }),
            })
          : t('today.who', { name: who, nth: t('today.firstSession') })}
      </Text>
      <Text variant="body" color={colors.brandTintStrong}>
        {t('today.meta', { modality: t(`book.modality_${b.modality}`), language })}
      </Text>
      <Button
        variant="secondary"
        label={open ? t('today.join') : t('today.joinAt', { time: timeOf(opens, lang) })}
        disabled={!open}
        accessibilityState={{ disabled: !open }}
        onPress={() => router.push({ pathname: '/call/[id]', params: { id: b.id } })}
      />
      {aiAssist ? (
        <Button
          variant="secondary"
          label={t('today.brief')}
          onPress={() => router.push({ pathname: '/brief/[id]', params: { id: b.id } })}
        />
      ) : null}
    </View>
  );
}

function Section({
  title,
  items,
  nameOf,
  lang,
}: {
  title: string;
  items: Booking[];
  nameOf: (code: string) => string;
  lang: string;
}) {
  const { t } = useTranslation();
  return (
    <View style={styles.section}>
      <Text variant="meta" color={colors.inkMuted} style={styles.bold} accessibilityRole="header">
        {title.toUpperCase()}
      </Text>
      {items.map((b) => (
        <View key={b.id} style={styles.row}>
          <Text variant="label" style={[styles.bold, styles.time]}>
            {timeOf(new Date(b.start), lang)}
          </Text>
          <View style={styles.flex}>
            <Text variant="label" style={styles.bold}>
              {b.seeker?.label ?? ''}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('today.meta', {
                modality: t(`book.modality_${b.modality}`),
                language: nameOf(b.sessionLanguage),
              })}
            </Text>
          </View>
          {b.status !== 'CONFIRMED' ? (
            <Pill label={t(`status.${b.status}`)} tone={toneOf(b.status)} />
          ) : null}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  center: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space14,
  },
  centerText: {
    textAlign: 'center',
  },
  content: {
    paddingHorizontal: space.space20,
    paddingTop: space.space24,
    paddingBottom: space.space16,
    gap: space.space16,
  },
  next: {
    gap: space.space8,
    padding: space.space20,
    borderRadius: radius.radius18,
    backgroundColor: colors.brand,
  },
  quiet: {
    alignItems: 'center',
    gap: space.space12,
    paddingTop: space.space24,
  },
  quietIcon: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandTint,
  },
  section: {
    gap: space.space8,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  time: {
    width: 52,
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
});
