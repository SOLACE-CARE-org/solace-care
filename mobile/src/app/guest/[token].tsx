import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Image, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { rememberGuestBooking, useGuestBooking } from '@/api/bookings';
import { ApiProblem } from '@/api/core';
import i18n from '@/i18n';
import { changeable, joinOpensAt } from '@/state/bookingView';
import { capitalised, timeOf, whenLabel } from '@/state/format';
import { Avatar } from '@/ui/Avatar';
import { Button } from '@/ui/Button';
import { Pill } from '@/ui/Pill';
import { useNow } from '@/state/useNow';
import { Text } from '@/ui/Text';

const WORDMARK = { width: 96, height: 32 };

/**
 * Guest: open booking from SMS (GuestJoin.dc.html). The link carries the guest token (D34); the
 * app keeps it in secure storage so Session details and Change or cancel work for this booking.
 */
export default function GuestJoin() {
  const { t } = useTranslation();
  const { token } = useLocalSearchParams<{ token: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const booking = useGuestBooking(token);
  const languages = useSessionLanguages(lang);
  const b = booking.data;
  const now = useNow();

  useEffect(() => {
    if (b) void rememberGuestBooking(b.id, token);
  }, [b, token]);

  if (!b) {
    const problem = booking.error instanceof ApiProblem ? booking.error.title : undefined;
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        {booking.isError ? (
          <>
            <Text variant="body" color={colors.inkMuted} style={styles.centerText}>
              {problem ?? t('common.checkConnection')}
            </Text>
            <Button label={t('sessions.emptyAction')} onPress={() => router.replace('/search')} />
          </>
        ) : (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        )}
      </SafeAreaView>
    );
  }

  const minutes = Math.round((Date.parse(b.start) - now) / 60_000);
  const title =
    minutes > 0 && minutes <= 60
      ? t('guestJoin.startsIn', { count: minutes })
      : t('guestJoin.startsAt', { when: whenLabel(b.start, t, lang) });
  const language =
    languages.data?.find((l) => l.code === b.sessionLanguage)?.name ?? b.sessionLanguage;
  const opensAt = joinOpensAt(b);
  const live = b.status === 'CONFIRMED' || b.status === 'IN_PROGRESS';
  const joinable = live && now >= opensAt.getTime() && now < Date.parse(b.end);
  const day = capitalised(
    whenLabel(b.start, t, lang)
      .replace(timeOf(new Date(b.start), lang), '')
      .replace(/[,\s]+$/, ''),
  );

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.top}>
          <Image
            source={require('../../../../design/design-system/assets/Logos/png/solace-care-logo.png')}
            style={WORDMARK}
            resizeMode="contain"
            accessible
            accessibilityRole="image"
            accessibilityLabel={t('common.appName')}
          />
          <Pill label={t('guestJoin.badge')} tone="neutral" />
        </View>

        <View style={styles.heading}>
          <Text variant="titleMd" accessibilityRole="header">
            {title}
          </Text>
          {b.guestPhone ? (
            <Text variant="body" color={colors.inkMuted}>
              {t('guestJoin.openedFor', { phone: b.guestPhone })}
            </Text>
          ) : null}
          {!live ? <Pill label={t(`status.${b.status}`)} tone="neutral" /> : null}
        </View>

        <View style={styles.card}>
          <View style={styles.who}>
            <Avatar name={b.specialist.fullName} />
            <View style={styles.flex}>
              <Text variant="label" style={styles.bold}>
                {b.specialist.fullName}
              </Text>
              {b.specialist.professionLabel ? (
                <Text variant="meta" color={colors.inkMuted}>
                  {b.specialist.professionLabel}
                </Text>
              ) : null}
            </View>
          </View>
          <View style={styles.facts}>
            <Fact label={day} value={timeOf(new Date(b.start), lang)} />
            <Fact label={t('guestJoin.type')} value={t(`book.modality_${b.modality}`)} />
            <Fact label={t('guestJoin.language')} value={language} />
          </View>
        </View>

        {live ? (
          <View style={styles.actions}>
            <Button
              label={t('guestJoin.waitingRoom')}
              disabled={!joinable}
              accessibilityState={{ disabled: !joinable }}
            />
            {!joinable ? (
              <Text variant="meta" color={colors.inkMuted} style={styles.centerText}>
                {t('session.joinOpens', { time: timeOf(opensAt, lang) })}
              </Text>
            ) : null}
          </View>
        ) : null}
        {changeable(b) ? (
          <Button
            variant="secondary"
            label={t('session.change')}
            onPress={() => router.push({ pathname: '/session/change', params: { id: b.id } })}
          />
        ) : null}

        <View style={styles.after}>
          <Text variant="label" style={styles.bold}>
            {t('guestJoin.afterTitle')}
          </Text>
          <Text variant="body" color={colors.inkMuted}>
            {t('guestJoin.afterBody')}
          </Text>
          <Button
            variant="quiet"
            label={t('guestJoin.createAccount')}
            onPress={() => router.push('/signup/details')}
          />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.fact} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text variant="meta" color={colors.inkMuted}>
        {label}
      </Text>
      <Text variant="label" style={styles.bold}>
        {value}
      </Text>
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
    padding: space.space24,
  },
  centerText: {
    textAlign: 'center',
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  top: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  heading: {
    gap: space.space6,
  },
  card: {
    gap: space.space14,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  who: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
  },
  facts: {
    flexDirection: 'row',
    gap: space.space8,
  },
  fact: {
    flex: 1,
    gap: space.space2,
    padding: space.space10,
    borderRadius: radius.radius12,
    backgroundColor: colors.surfaceSunken,
  },
  actions: {
    gap: space.space6,
  },
  after: {
    gap: space.space6,
    padding: space.space16,
    borderRadius: radius.radius16,
    backgroundColor: colors.brandTint,
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
});
