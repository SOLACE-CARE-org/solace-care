import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { ChevronRight } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useConsents, useSessionLanguages } from '@/api/account';
import { useBooking, useGuestToken } from '@/api/bookings';
import i18n from '@/i18n';
import { changeable, joinOpensAt, longWhen, minutesOf, toneOf } from '@/state/bookingView';
import { money, timeOf } from '@/state/format';
import { useSetSharing, useShares } from '@/api/tracking';
import { useFlags } from '@/config/flags';
import { Avatar } from '@/ui/Avatar';
import { SwitchRow } from '@/ui/SwitchRow';
import { Button } from '@/ui/Button';
import { Pill } from '@/ui/Pill';
import { BackButton } from '@/ui/ProgressSteps';
import { useNow } from '@/state/useNow';
import { Text } from '@/ui/Text';

/**
 * Session details (SessionDetail.dc.html), for a member's booking or one this device booked as a
 * guest. Joining opens 10 minutes before the start (epic 7). With tracking on, a registered seeker
 * shares what they log with this specialist from now on, or stops at once (D52).
 */
export default function SessionDetail() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const token = useGuestToken(id);
  const access = { bookingId: id, guestToken: token.data ?? undefined };
  const booking = useBooking(access, token.isSuccess);
  const languages = useSessionLanguages(lang);
  const b = booking.data;
  const now = useNow();

  if (!b) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        {booking.isError ? (
          <>
            <Text variant="body" color={colors.inkMuted}>
              {t('common.checkConnection')}
            </Text>
            <Button label={t('common.tryAgain')} onPress={() => booking.refetch()} />
          </>
        ) : (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        )}
      </SafeAreaView>
    );
  }

  const language =
    languages.data?.find((l) => l.code === b.sessionLanguage)?.name ?? b.sessionLanguage;
  const live = b.status === 'CONFIRMED' || b.status === 'IN_PROGRESS';
  const opensAt = joinOpensAt(b);
  const joinable = live && now >= opensAt.getTime() && now < Date.parse(b.end);

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <BackButton
          label={t('session.back')}
          onPress={() =>
            router.canGoBack() ? router.back() : router.replace('/(seeker)/sessions')
          }
        />
        <View style={styles.heading}>
          <Pill label={t(`status.${b.status}`)} tone={toneOf(b.status)} />
          <Text variant="titleMd" accessibilityRole="header">
            {longWhen(b.start, lang)}
          </Text>
          <Text variant="body" color={colors.inkMuted}>
            {t('session.meta', {
              minutes: minutesOf(b),
              modality: t(`book.modality_${b.modality}`),
              language: lang === 'fr' ? language.toLowerCase() : language,
            })}
          </Text>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={b.specialist.fullName}
          onPress={() =>
            router.push({ pathname: '/specialist/[id]', params: { id: b.specialist.id } })
          }
          style={styles.card}
        >
          <Avatar name={b.specialist.fullName} />
          <View style={styles.cardText}>
            <Text variant="label" style={styles.bold}>
              {b.specialist.fullName}
            </Text>
            {b.sessionNumber && live ? (
              <Text variant="meta" color={colors.inkMuted}>
                {t('session.together', { count: b.sessionNumber, ordinal: true })}
              </Text>
            ) : b.specialist.professionLabel ? (
              <Text variant="meta" color={colors.inkMuted}>
                {b.specialist.professionLabel}
              </Text>
            ) : null}
          </View>
          <ChevronRight size={20} strokeWidth={2} color={colors.inkMuted} aria-hidden />
        </Pressable>

        {b.status === 'PENDING_PAYMENT' && b.payBy ? (
          <View style={[styles.box, styles.warning]} accessibilityLiveRegion="polite">
            <Text variant="body" color={colors.warningInk}>
              {t('session.payBy', { time: timeOf(new Date(b.payBy), lang) })}
            </Text>
            <Button
              label={t('session.pay')}
              onPress={() => router.push({ pathname: '/book/pay', params: { id: b.id } })}
            />
          </View>
        ) : null}

        {b.status === 'CANCELLED' && b.refundAmount ? (
          <View style={styles.box}>
            <Text variant="body">
              {t('session.refunded', { amount: money(b.refundAmount, lang) })}
            </Text>
          </View>
        ) : null}

        {live && !access.guestToken ? (
          <ShareTracking specialistId={b.specialist.id} name={b.specialist.fullName} />
        ) : null}

        {live ? (
          <View style={styles.box}>
            <Text variant="label" style={styles.bold} accessibilityRole="header">
              {t('session.beforeTitle')}
            </Text>
            {(['tip1', 'tip2', 'tip3'] as const).map((tip) => (
              <View key={tip} style={styles.tip}>
                <Text variant="body" aria-hidden>
                  {'•'}
                </Text>
                <Text variant="body" style={styles.tipText}>
                  {t(`session.${tip}`)}
                </Text>
              </View>
            ))}
          </View>
        ) : null}

        {b.status === 'COMPLETED' && b.rated === false ? (
          <Button
            label={t('sessionsRate.rate')}
            onPress={() => router.push({ pathname: '/rate/[id]', params: { id: b.id } })}
          />
        ) : null}

        {changeable(b) ? (
          <Button
            variant="secondary"
            label={t('session.change')}
            onPress={() => router.push({ pathname: '/session/change', params: { id: b.id } })}
          />
        ) : null}
      </ScrollView>

      {live ? (
        <View style={styles.footer}>
          <Button
            label={t('session.join')}
            disabled={!joinable}
            accessibilityState={{ disabled: !joinable }}
            onPress={() => router.push({ pathname: '/call/[id]', params: { id: b.id } })}
          />
          {!joinable ? (
            <Text variant="meta" color={colors.inkMuted} style={styles.caption}>
              {t('session.joinOpens', { time: timeOf(opensAt, lang) })}
            </Text>
          ) : null}
        </View>
      ) : null}
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
    gap: space.space16,
  },
  heading: {
    gap: space.space6,
  },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  cardText: {
    flex: 1,
    gap: space.space2,
  },
  box: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  warning: {
    borderColor: colors.warningTint,
    backgroundColor: colors.warningTint,
  },
  tip: {
    flexDirection: 'row',
    gap: space.space8,
  },
  tipText: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
  footer: {
    gap: space.space6,
    paddingHorizontal: space.space20,
    paddingTop: space.space16,
    paddingBottom: space.space14,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  caption: {
    textAlign: 'center',
  },
});

/** "Share my tracking summary": a grant to this specialist, forward-only and revocable (D52). */
function ShareTracking({ specialistId, name }: { specialistId: string; name: string }) {
  const { t } = useTranslation();
  const trackTab = useFlags((f) => f.trackTab);
  const consents = useConsents();
  const consented =
    consents.data?.consents.some((c) => c.purpose === 'TRACKING' && c.granted) === true;
  const shares = useShares(trackTab && consented);
  const setSharing = useSetSharing();
  if (!trackTab || !consented || !shares.data) return null;
  const active = shares.data.find((g) => g.specialistId === specialistId && !g.revokedAt);
  return (
    <SwitchRow
      title={t('session.shareTracking')}
      meta={t('session.shareTrackingMeta', { name })}
      on={Boolean(active)}
      onToggle={() => setSharing.mutate({ specialistId, share: !active, grantId: active?.id })}
    />
  );
}
