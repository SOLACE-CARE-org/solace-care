import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { CalendarDays, ChevronRight, Plus } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { type Booking, type Scope, useMyBookings } from '@/api/bookings';
import i18n from '@/i18n';
import { toneOf } from '@/state/bookingView';
import { timeOf } from '@/state/format';
import { Button } from '@/ui/Button';
import { Pill } from '@/ui/Pill';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';

/**
 * My sessions (Sessions.dc.html, StateSessionsEmpty.dc.html): upcoming soonest first with the most
 * recent past ones under them; Past lists the rest. Times in the viewer's time zone.
 */
export default function Sessions() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const [scope, setScope] = useState<Scope>('UPCOMING');
  const upcoming = useMyBookings('UPCOMING');
  const past = useMyBookings('PAST');
  const languages = useSessionLanguages(lang);
  const nameOf = (code: string) => languages.data?.find((l) => l.code === code)?.name ?? code;

  const shown = scope === 'UPCOMING' ? upcoming : past;
  const empty = upcoming.data?.length === 0 && past.data?.length === 0;
  const open = (b: Booking) => router.push({ pathname: '/session/[id]', params: { id: b.id } });

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
        <View style={styles.header}>
          <Text variant="titleLg" accessibilityRole="header">
            {t('sessions.title')}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('sessions.book')}
            onPress={() => router.push('/search')}
            style={styles.bookButton}
          >
            <Plus size={16} strokeWidth={2.2} color={colors.onBrand} aria-hidden />
            <Text variant="label" color={colors.onBrand} style={styles.strong}>
              {t('sessions.book')}
            </Text>
          </Pressable>
        </View>

        {empty ? (
          <View style={styles.empty}>
            <View style={styles.emptyIcon} aria-hidden>
              <CalendarDays size={28} strokeWidth={1.8} color={colors.brand} />
            </View>
            <Text variant="titleSm" accessibilityRole="header">
              {t('sessions.emptyTitle')}
            </Text>
            <Text variant="body" color={colors.inkMuted} style={styles.center}>
              {t('sessions.emptyBody')}
            </Text>
            <Button label={t('sessions.emptyAction')} onPress={() => router.push('/search')} />
          </View>
        ) : (
          <>
            <Segmented
              label={t('sessions.title')}
              value={scope}
              onChange={setScope}
              options={[
                { value: 'UPCOMING', label: t('sessions.upcoming') },
                { value: 'PAST', label: t('sessions.past') },
              ]}
            />
            {shown.isPending ? (
              <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
            ) : shown.isError ? (
              <View style={styles.inline}>
                <Text variant="body" color={colors.inkMuted}>
                  {t('common.checkConnection')}
                </Text>
                <Button
                  variant="quiet"
                  label={t('common.tryAgain')}
                  onPress={() => shown.refetch()}
                />
              </View>
            ) : scope === 'UPCOMING' ? (
              <>
                {(upcoming.data ?? []).map((b) => (
                  <UpcomingCard
                    key={b.id}
                    booking={b}
                    lang={lang}
                    language={nameOf(b.sessionLanguage)}
                    onPress={() => open(b)}
                  />
                ))}
                {(past.data ?? []).length > 0 ? (
                  <>
                    <Text
                      variant="meta"
                      color={colors.inkMuted}
                      style={styles.section}
                      accessibilityRole="header"
                    >
                      {t('sessions.recently').toUpperCase()}
                    </Text>
                    {(past.data ?? []).slice(0, 2).map((b) => (
                      <PastRow key={b.id} booking={b} lang={lang} onPress={() => open(b)} />
                    ))}
                  </>
                ) : null}
              </>
            ) : (past.data ?? []).length === 0 ? (
              <Text variant="body" color={colors.inkMuted}>
                {t('sessions.nonePast')}
              </Text>
            ) : (
              (past.data ?? []).map((b) => (
                <PastRow key={b.id} booking={b} lang={lang} onPress={() => open(b)} />
              ))
            )}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function UpcomingCard({
  booking: b,
  lang,
  language,
  onPress,
}: {
  booking: Booking;
  lang: string;
  language: string;
  onPress: () => void;
}) {
  const { t } = useTranslation();
  const start = new Date(b.start);
  const soonest = b.status === 'CONFIRMED' || b.status === 'IN_PROGRESS';
  const weekday = new Intl.DateTimeFormat(lang, { weekday: 'short' })
    .format(start)
    .replace('.', '')
    .toUpperCase();
  const month = new Intl.DateTimeFormat(lang, { month: 'short' }).format(start);
  const line = t('sessions.line', {
    time: timeOf(start, lang),
    modality: t(`book.modality_${b.modality}`),
    language,
  });
  const status = t(`status.${b.status}`);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${b.specialist.fullName}, ${new Intl.DateTimeFormat(lang, { dateStyle: 'full' }).format(start)}, ${line}, ${status}`}
      onPress={onPress}
      style={styles.card}
    >
      <View style={[styles.date, soonest && styles.dateOn]} aria-hidden>
        <Text
          variant="meta"
          color={soonest ? colors.brandTintStrong : colors.inkMuted}
          style={styles.strong}
        >
          {weekday}
        </Text>
        <Text style={[styles.dayNumber, { color: soonest ? colors.onBrand : colors.ink }]}>
          {start.getDate()}
        </Text>
        <Text variant="meta" color={soonest ? colors.brandTintStrong : colors.inkMuted}>
          {month}
        </Text>
      </View>
      <View style={styles.cardText}>
        <Text variant="label" style={styles.bold}>
          {b.specialist.fullName}
        </Text>
        <Text variant="meta" color={colors.inkMuted}>
          {line}
        </Text>
        <Pill label={status} tone={toneOf(b.status)} />
      </View>
      <ChevronRight size={20} strokeWidth={2} color={colors.inkMuted} aria-hidden />
    </Pressable>
  );
}

function PastRow({
  booking: b,
  lang,
  onPress,
}: {
  booking: Booking;
  lang: string;
  onPress: () => void;
}) {
  const { t } = useTranslation();
  const date = new Intl.DateTimeFormat(lang, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  }).format(new Date(b.start));
  const title = t('sessions.pastTitle', {
    date: date.charAt(0).toUpperCase() + date.slice(1),
    name: b.specialist.fullName,
  });
  const line = t('sessions.pastLine', {
    status: t(`status.${b.status}`),
    modality: t(`book.modality_${b.modality}`),
  });
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${title}, ${line}`}
      onPress={onPress}
      style={[styles.card, styles.pastCard]}
    >
      <View style={styles.cardText}>
        <Text variant="label" style={styles.bold}>
          {title}
        </Text>
        <Text variant="meta" color={colors.inkMuted}>
          {line}
        </Text>
      </View>
      <ChevronRight size={20} strokeWidth={2} color={colors.inkMuted} aria-hidden />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    paddingHorizontal: space.space20,
    paddingTop: space.space24,
    paddingBottom: space.space16,
    gap: space.space16,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  bookButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space6,
    minHeight: size.sizeTap,
    paddingHorizontal: space.space14,
    borderRadius: radius.radiusPill,
    backgroundColor: colors.brand,
  },
  empty: {
    alignItems: 'center',
    gap: space.space12,
    paddingTop: space.space32,
  },
  emptyIcon: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandTint,
  },
  center: {
    textAlign: 'center',
  },
  inline: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
  },
  section: {
    marginTop: space.space8,
    fontFamily: fontFamily.body[700],
    letterSpacing: 0.5,
  },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space14,
    padding: space.space16,
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  pastCard: {
    paddingVertical: space.space14,
  },
  date: {
    width: 56,
    alignItems: 'center',
    paddingVertical: space.space8,
    borderRadius: radius.radius14,
    backgroundColor: colors.surfaceSunken,
  },
  dateOn: {
    backgroundColor: colors.brand,
  },
  dayNumber: {
    fontFamily: fontFamily.display[700],
    fontSize: 22,
    lineHeight: 24,
  },
  cardText: {
    flex: 1,
    gap: 3,
  },
  strong: {
    fontFamily: fontFamily.body[600],
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
});
