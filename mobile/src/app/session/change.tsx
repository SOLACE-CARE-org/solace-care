import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { CalendarDays } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  type CancelReason,
  useBooking,
  useCancelBooking,
  useCancellationQuote,
  useGuestToken,
} from '@/api/bookings';
import { ApiProblem } from '@/api/core';
import i18n from '@/i18n';
import { longWhen } from '@/state/bookingView';
import { money } from '@/state/format';
import { Text } from '@/ui/Text';

const REASONS: CancelReason[] = ['TIME_NO_LONGER_WORKS', 'FEELING_BETTER', 'DIFFERENT_SPECIALIST'];

/**
 * Cancel or reschedule (CancelSession.dc.html), a sheet over Session details. Moving keeps the
 * specialist and the format at no charge; cancelling shows the refund the policy gives now (D33).
 */
export default function ChangeSession() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const token = useGuestToken(id);
  const access = { bookingId: id, guestToken: token.data ?? undefined };
  const booking = useBooking(access, token.isSuccess);
  const quote = useCancellationQuote(access, token.isSuccess);
  const cancel = useCancelBooking(access);
  const [reason, setReason] = useState<CancelReason | undefined>(undefined);
  const [problem, setProblem] = useState<string | null>(null);
  const b = booking.data;
  const close = () => router.back();
  const canMove = b?.status === 'CONFIRMED' && (b?.reschedulesLeft ?? 0) > 0;

  const confirmCancel = () => {
    setProblem(null);
    cancel.mutate(reason, {
      onSuccess: () => router.dismissTo({ pathname: '/session/[id]', params: { id } }),
      onError: (e) =>
        setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
    });
  };

  return (
    <View style={styles.backdrop}>
      <Pressable
        style={styles.scrim}
        accessibilityRole="button"
        accessibilityLabel={t('change.close')}
        onPress={close}
      />
      <SafeAreaView edges={['bottom']} style={styles.sheet} accessibilityViewIsModal>
        <ScrollView contentContainerStyle={styles.content}>
          <View style={styles.handle} aria-hidden />
          <View style={styles.heading}>
            <Text variant="titleMd" accessibilityRole="header">
              {t('change.title')}
            </Text>
            {b ? (
              <Text variant="body" color={colors.inkMuted}>
                {t('change.with', { when: longWhen(b.start, lang), name: b.specialist.fullName })}
              </Text>
            ) : null}
          </View>

          {b?.status === 'CONFIRMED' ? (
            canMove ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`${t('change.newTime')}, ${t('change.newTimeMeta')}`}
                onPress={() =>
                  router.push({
                    pathname: '/book/[id]',
                    params: { id: b.specialist.id, reschedule: b.id, modality: b.modality },
                  })
                }
                style={styles.move}
              >
                <CalendarDays size={22} strokeWidth={1.8} color={colors.brand} aria-hidden />
                <View style={styles.flex}>
                  <Text variant="label" style={styles.bold}>
                    {t('change.newTime')}
                  </Text>
                  <Text variant="meta" color={colors.inkMuted}>
                    {t('change.newTimeMeta')}
                  </Text>
                </View>
              </Pressable>
            ) : (
              <Text variant="meta" color={colors.inkMuted}>
                {t('change.noMoves')}
              </Text>
            )
          ) : null}

          <View style={styles.cancelBox}>
            <Text variant="label" style={styles.bold}>
              {t('change.cancelInstead')}
            </Text>
            <View style={styles.refund}>
              <Text variant="meta" color={colors.inkMuted} style={styles.flex}>
                {t('change.refund')}
              </Text>
              <Text variant="label" style={styles.bold}>
                {quote.data
                  ? t('change.amount', { amount: money(quote.data.refundAmount, lang) })
                  : '…'}
              </Text>
            </View>
            <View
              accessibilityRole="radiogroup"
              accessibilityLabel={t('change.reason')}
              style={styles.reasons}
            >
              <Text variant="meta" color={colors.inkMuted} style={styles.bold}>
                {t('change.reason')}
              </Text>
              {REASONS.map((r) => {
                const on = r === reason;
                return (
                  <Pressable
                    key={r}
                    accessibilityRole="radio"
                    accessibilityState={{ checked: on }}
                    accessibilityLabel={t(`change.${r}`)}
                    onPress={() => setReason(on ? undefined : r)}
                    style={styles.reason}
                  >
                    <View style={[styles.radio, on && styles.radioOn]} aria-hidden>
                      {on ? <View style={styles.radioDot} /> : null}
                    </View>
                    <Text variant="body">{t(`change.${r}`)}</Text>
                  </Pressable>
                );
              })}
            </View>
            {problem ? (
              <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
                {problem}
              </Text>
            ) : null}
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('change.cancel')}
              accessibilityState={{ disabled: cancel.isPending || !b, busy: cancel.isPending }}
              disabled={cancel.isPending || !b}
              onPress={confirmCancel}
              style={styles.cancelButton}
            >
              <Text variant="label" color={colors.dangerStrong} style={styles.bold}>
                {t('change.cancel')}
              </Text>
            </Pressable>
          </View>

          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('change.keep')}
            onPress={close}
            style={styles.keep}
          >
            <Text variant="label" color={colors.brand} style={[styles.bold, styles.underline]}>
              {t('change.keep')}
            </Text>
          </Pressable>
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  scrim: {
    ...StyleSheet.absoluteFill,
    backgroundColor: colors.ink,
    opacity: 0.75,
  },
  sheet: {
    maxHeight: '85%',
    borderTopLeftRadius: radius.radius28,
    borderTopRightRadius: radius.radius28,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  handle: {
    alignSelf: 'center',
    width: 40,
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.lineStrong,
  },
  heading: {
    gap: space.space4,
  },
  move: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space14,
    padding: space.space18,
    borderRadius: radius.radius16,
    borderWidth: 2,
    borderColor: colors.brand,
    backgroundColor: colors.surfaceCard,
  },
  flex: {
    flex: 1,
  },
  cancelBox: {
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  refund: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
    padding: space.space12,
    borderRadius: radius.radius10,
    backgroundColor: colors.surfaceSunken,
  },
  reasons: {
    gap: space.space2,
  },
  reason: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space10,
    minHeight: size.sizeTap,
  },
  radio: {
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 2,
    borderColor: colors.lineControl,
    alignItems: 'center',
    justifyContent: 'center',
  },
  radioOn: {
    borderColor: colors.brand,
  },
  radioDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: colors.brand,
  },
  cancelButton: {
    minHeight: 52,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.dangerStrong,
  },
  keep: {
    minHeight: size.sizeTap,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
  underline: {
    textDecorationLine: 'underline',
  },
});
