import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { type Modality, useSpecialistProfile } from '@/api/catalogue';
import { useGuestToken, useRescheduleBooking } from '@/api/bookings';
import { ApiProblem } from '@/api/core';
import { releaseHold, useHoldSlot, useSlots } from '@/api/scheduling';
import i18n from '@/i18n';
import { capitalised, money, timeOf, whenLabel } from '@/state/format';
import { Button } from '@/ui/Button';
import { OptionCard } from '@/ui/OptionCard';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/** "2026-10-15" for the viewer's own calendar day. */
function dayKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/**
 * Choose a session (Book.dc.html): the session language, audio or video, then a day and a time in
 * the viewer's own time zone. Taken times stay visible, greyed. Continue holds the start for 10
 * minutes and opens checkout (epics 5 and 6). With {@code reschedule} (a booking id) it picks a new
 * time for that booking instead: same specialist, format and language, no checkout (D33).
 */
export default function ChooseSession() {
  const { t } = useTranslation();
  const {
    id,
    reschedule,
    modality: fixedModality,
  } = useLocalSearchParams<{
    id: string;
    reschedule?: string;
    modality?: Modality;
  }>();
  const moving = Boolean(reschedule);
  const guestToken = useGuestToken(reschedule ?? '');
  const move = useRescheduleBooking({
    bookingId: reschedule ?? '',
    guestToken: guestToken.data ?? undefined,
  });
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const profile = useSpecialistProfile(id, lang);
  const catalogue = useSessionLanguages(lang);
  const p = profile.data;

  const bookable = p?.languages.map((l) => l.code) ?? [];
  const [language, setLanguage] = useState<string | null>(null);
  const sessionLanguage = language ?? (bookable.includes(lang) ? lang : (bookable[0] ?? lang));
  const offerings = (p?.offerings ?? []).filter((o) => !moving || o.modality === fixedModality);
  const [modality, setModality] = useState<Modality | null>(null);
  const format: Modality = modality ?? offerings[0]?.modality ?? 'AUDIO';

  const slots = useSlots(id, format);
  const hold = useHoldSlot();
  const [day, setDay] = useState<string | null>(null);
  const [start, setStart] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const all = slots.data?.slots ?? [];
  const days = [...new Set(all.map((s) => dayKey(new Date(s.start))))];
  const chosenDay = day && days.includes(day) ? day : days[0];
  const times = all.filter((s) => dayKey(new Date(s.start)) === chosenDay);
  const viewerZone = new Intl.DateTimeFormat(lang, { timeZoneName: 'short' })
    .formatToParts(new Date())
    .find((part) => part.type === 'timeZoneName')?.value;
  const nameOf = (code: string) => catalogue.data?.find((l) => l.code === code)?.name ?? code;
  const chosenStart = start && times.some((s) => s.start === start && s.available) ? start : null;

  const proceed = () => {
    if (!chosenStart) return;
    setProblem(null);
    const failed = (e: unknown) => {
      setStart(null);
      setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));
    };
    hold.mutate(
      { specialistId: id, start: chosenStart, modality: format },
      {
        onSuccess: (held) =>
          moving
            ? move.mutate(held.holdId, {
                onSuccess: () =>
                  router.dismissTo({ pathname: '/session/[id]', params: { id: reschedule! } }),
                onError: (e) => {
                  void releaseHold(held.holdId);
                  failed(e);
                },
              })
            : router.push({
                pathname: '/book/checkout',
                params: {
                  holdId: held.holdId,
                  specialistId: id,
                  start: held.start,
                  end: held.end,
                  expiresAt: held.expiresAt,
                  modality: format,
                  language: sessionLanguage,
                },
              }),
        onError: failed,
      },
    );
  };
  const busy = hold.isPending || move.isPending;

  if (!p) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        {profile.isError ? (
          <>
            <Text variant="body" color={colors.inkMuted}>
              {t('common.checkConnection')}
            </Text>
            <Button label={t('common.tryAgain')} onPress={() => profile.refetch()} />
          </>
        ) : (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        )}
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.titleRow}>
          <BackButton />
          <View style={styles.titleText}>
            <Text variant="meta" color={colors.inkMuted}>
              {t(moving ? 'book.rescheduleStep' : 'book.step', { name: p.fullName })}
            </Text>
            <Text variant="titleMd" accessibilityRole="header">
              {t('book.title')}
            </Text>
          </View>
        </View>

        <View style={[styles.section, moving && styles.hidden]}>
          <Text variant="label" style={styles.question}>
            {t('book.languageQ')}
          </Text>
          <View accessibilityRole="radiogroup" style={styles.languages}>
            {bookable.map((code) => (
              <View key={code} style={styles.half}>
                <OptionCard
                  kind="radio"
                  title={nameOf(code)}
                  selected={code === sessionLanguage}
                  onPress={() => setLanguage(code)}
                />
              </View>
            ))}
          </View>
        </View>

        <View style={styles.section}>
          <Text variant="label" style={styles.question}>
            {t('book.formatQ')}
          </Text>
          <View accessibilityRole="radiogroup" style={styles.formats}>
            {offerings.map((o) => (
              <OptionCard
                key={o.modality}
                kind="radio"
                title={`${t(`book.${o.modality}`)} · ${t('book.price', { price: money(o.price, lang) })}`}
                meta={t(`book.${o.modality}_meta`)}
                selected={o.modality === format}
                onPress={() => {
                  setModality(o.modality);
                  setStart(null);
                }}
              />
            ))}
          </View>
        </View>

        <View style={styles.section}>
          <View style={styles.pickRow}>
            <Text variant="label" style={styles.question}>
              {t('book.pickTime')}
            </Text>
            {viewerZone ? (
              <Text variant="meta" color={colors.inkMuted}>
                {t('book.yourTime', { zone: viewerZone })}
              </Text>
            ) : null}
          </View>

          {slots.isPending ? (
            <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
          ) : slots.isError ? (
            <View style={styles.inline}>
              <Text variant="body" color={colors.inkMuted}>
                {t('common.checkConnection')}
              </Text>
              <Button
                variant="quiet"
                label={t('common.tryAgain')}
                onPress={() => slots.refetch()}
              />
            </View>
          ) : days.length === 0 ? (
            <Text variant="body" color={colors.inkMuted}>
              {t('book.noTimes')}
            </Text>
          ) : (
            <>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.days}
              >
                {days.map((key) => {
                  const date = new Date(`${key}T12:00:00`);
                  const today = key === dayKey(new Date());
                  const on = key === chosenDay;
                  const weekday = today
                    ? t('book.today')
                    : capitalised(new Intl.DateTimeFormat(lang, { weekday: 'short' }).format(date));
                  return (
                    <Pressable
                      key={key}
                      accessibilityRole="radio"
                      accessibilityState={{ checked: on }}
                      accessibilityLabel={new Intl.DateTimeFormat(lang, {
                        dateStyle: 'full',
                      }).format(date)}
                      onPress={() => {
                        setDay(key);
                        setStart(null);
                      }}
                      style={[styles.day, on && styles.dayOn]}
                    >
                      <Text
                        variant="meta"
                        color={on ? colors.onBrand : colors.inkMuted}
                        style={styles.strong}
                      >
                        {weekday}
                      </Text>
                      <Text
                        variant="label"
                        color={on ? colors.onBrand : colors.ink}
                        style={styles.dayNumber}
                      >
                        {date.getDate()}
                      </Text>
                    </Pressable>
                  );
                })}
              </ScrollView>
              <View style={styles.times}>
                {times.map((slot) => {
                  const time = timeOf(new Date(slot.start), lang);
                  const on = slot.start === chosenStart;
                  return (
                    <Pressable
                      key={slot.start}
                      accessibilityRole="radio"
                      accessibilityState={{ checked: on, disabled: !slot.available }}
                      accessibilityLabel={slot.available ? time : t('book.taken', { time })}
                      disabled={!slot.available}
                      onPress={() => setStart(slot.start)}
                      style={[
                        styles.time,
                        !slot.available && styles.timeTaken,
                        on && styles.timeOn,
                      ]}
                    >
                      <Text
                        variant="label"
                        color={slot.available ? colors.ink : colors.inkFaint}
                        style={[styles.strong, !slot.available && styles.struck]}
                      >
                        {time}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            </>
          )}
          {problem ? (
            <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
              {problem}
            </Text>
          ) : null}
        </View>
      </ScrollView>

      <View style={styles.footer}>
        <View style={styles.summary}>
          <Text variant="meta" color={colors.inkSoft}>
            {chosenStart ? capitalised(whenLabel(chosenStart, t, lang)) : ''}
          </Text>
          <Text variant="label" style={styles.strong}>
            {t('book.summaryFormat', {
              modality: t(`book.modality_${format}`),
              language: nameOf(sessionLanguage),
            })}
          </Text>
        </View>
        <Button
          label={moving ? t('book.move') : t('common.continue')}
          disabled={!chosenStart || busy}
          accessibilityState={{ disabled: !chosenStart || busy, busy }}
          onPress={proceed}
        />
      </View>
    </SafeAreaView>
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
  content: {
    padding: space.space20,
    gap: space.space20,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  titleText: {
    flex: 1,
  },
  section: {
    gap: space.space10,
  },
  hidden: {
    display: 'none',
  },
  question: {
    fontSize: 16,
  },
  languages: {
    flexDirection: 'row',
    gap: space.space10,
  },
  half: {
    flex: 1,
  },
  formats: {
    gap: space.space10,
  },
  pickRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  inline: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
  },
  days: {
    gap: space.space8,
  },
  day: {
    width: 62,
    minHeight: 60,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  dayOn: {
    borderColor: colors.brand,
    backgroundColor: colors.brand,
  },
  dayNumber: {
    fontSize: 18,
    fontFamily: fontFamily.body[700],
  },
  times: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space8,
  },
  time: {
    minWidth: 80,
    minHeight: size.sizeTap,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.radius12,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  timeTaken: {
    borderStyle: 'dashed',
    backgroundColor: colors.surfacePage,
  },
  timeOn: {
    borderWidth: 2,
    borderColor: colors.brand,
    backgroundColor: colors.brandTint,
  },
  struck: {
    textDecorationLine: 'line-through',
  },
  strong: {
    fontFamily: fontFamily.body[700],
  },
  footer: {
    gap: space.space10,
    paddingHorizontal: space.space20,
    paddingVertical: space.space14,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  summary: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
});
