import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { Smartphone } from 'lucide-react-native';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useBooking, useGuestToken } from '@/api/bookings';
import { ApiProblem } from '@/api/core';
import { type PayMethod, useCheckPayment, useLatestPayment, useStartPayment } from '@/api/payments';
import { displayContact } from '@/auth/contact';
import i18n from '@/i18n';
import { money, timeOf } from '@/state/format';
import { useNow } from '@/state/useNow';
import { Button } from '@/ui/Button';
import { OptionCard } from '@/ui/OptionCard';
import { Text } from '@/ui/Text';

const METHODS: PayMethod[] = ['MTN_MOMO', 'ORANGE_MONEY'];

/**
 * Approve payment (PaymentPending.dc.html): the wallet holder approves on their phone; the screen
 * watches the payment and counts down the hold. Core confirms the booking only on the provider's
 * word (webhook or a check), never on this screen's.
 */
export default function ApprovePayment() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const token = useGuestToken(id);
  const access = { bookingId: id, guestToken: token.data ?? undefined };
  const booking = useBooking(access, token.isSuccess);
  const payment = useLatestPayment(access, token.isSuccess);
  const start = useStartPayment(access);
  const check = useCheckPayment(access);
  const now = useNow(1_000);
  const [choosing, setChoosing] = useState(false);
  const [method, setMethod] = useState<PayMethod | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const b = booking.data;
  const p = payment.data;
  const chosen: PayMethod = method ?? (p?.method as PayMethod | undefined) ?? 'MTN_MOMO';

  useEffect(() => {
    if (p?.status === 'COMPLETE') {
      router.replace({ pathname: '/book/confirmed', params: { id } });
    }
  }, [p?.status, id]);

  if (!b) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
      </SafeAreaView>
    );
  }

  const payBy = b.payBy ? Date.parse(b.payBy) : 0;
  const left = Math.max(0, Math.floor((payBy - now) / 1000));
  const expired = b.status === 'EXPIRED' || (b.status === 'PENDING_PAYMENT' && left === 0);
  const failed = p?.status === 'FAILED' || (payment.isError && !p);
  const say = (e: unknown) =>
    setNote(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));

  const resend = () => {
    setNote(null);
    start.mutate({ method: chosen }, { onSuccess: () => setChoosing(false), onError: say });
  };
  const approved = () => {
    setNote(null);
    check.mutate(undefined, {
      onSuccess: (latest) => latest.status === 'PENDING' && setNote(t('pay.notYet')),
      onError: say,
    });
  };

  if (expired) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <Text variant="body" color={colors.inkMuted} style={styles.centerText}>
          {t('pay.expired')}
        </Text>
        <Button
          label={t('pay.pickAgain')}
          onPress={() =>
            router.replace({ pathname: '/book/[id]', params: { id: b.specialist.id } })
          }
        />
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.icon} aria-hidden>
          <Smartphone size={32} strokeWidth={1.8} color={colors.brand} />
        </View>
        <Text variant="titleMd" accessibilityRole="header">
          {t('pay.title')}
        </Text>
        {p ? (
          <Text variant="body" color={colors.inkMuted}>
            {t('pay.lead', {
              price: money(p.amount, lang),
              method: t(`checkout.${p.method}`),
              phone: displayContact(p.phone),
            })}
          </Text>
        ) : null}

        <View style={styles.timer} accessible accessibilityLiveRegion="none">
          <Text variant="meta" color={colors.inkMuted}>
            {t('pay.heldFor')}
          </Text>
          <Text style={styles.clock}>
            {`${String(Math.floor(left / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')}`}
          </Text>
        </View>

        {failed ? (
          <Text variant="body" color={colors.danger} accessibilityLiveRegion="polite">
            {t('pay.failed', { time: timeOf(new Date(payBy), lang) })}
          </Text>
        ) : p?.status === 'PENDING' ? (
          <Text variant="meta" color={colors.inkMuted}>
            {t('pay.waiting')}
          </Text>
        ) : null}
        {note ? (
          <Text variant="meta" color={colors.inkMuted} accessibilityLiveRegion="polite">
            {note}
          </Text>
        ) : null}

        {choosing ? (
          <View accessibilityRole="radiogroup" style={styles.methods}>
            {METHODS.map((m) => (
              <OptionCard
                key={m}
                kind="radio"
                title={t(`checkout.${m}`)}
                selected={m === chosen}
                onPress={() => setMethod(m)}
              />
            ))}
          </View>
        ) : null}
      </ScrollView>

      <View style={styles.footer}>
        {failed || choosing ? null : (
          <Button
            label={t('pay.approved')}
            disabled={check.isPending}
            accessibilityState={{ disabled: check.isPending, busy: check.isPending }}
            onPress={approved}
          />
        )}
        <Button
          variant={failed || choosing ? 'primary' : 'secondary'}
          label={t('pay.resend')}
          disabled={start.isPending}
          accessibilityState={{ disabled: start.isPending, busy: start.isPending }}
          onPress={resend}
        />
        {choosing ? null : (
          <Button variant="quiet" label={t('pay.another')} onPress={() => setChoosing(true)} />
        )}
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
    padding: space.space24,
  },
  centerText: {
    textAlign: 'center',
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  icon: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandTint,
  },
  timer: {
    alignItems: 'center',
    gap: space.space4,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  clock: {
    fontFamily: fontFamily.display[700],
    fontSize: 32,
    color: colors.ink,
  },
  methods: {
    gap: space.space10,
  },
  footer: {
    gap: space.space10,
    paddingHorizontal: space.space20,
    paddingTop: space.space16,
    paddingBottom: space.space14,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
});
