import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { useCreateBooking, useCreateGuestBooking, useSendGuestCode } from '@/api/bookings';
import { type Modality, useSpecialistProfile } from '@/api/catalogue';
import { ApiProblem } from '@/api/core';
import { startPayment } from '@/api/payments';
import { useAuth } from '@/auth/auth';
import { CAMEROON_PREFIX, displayContact, toE164 } from '@/auth/contact';
import { flags } from '@/config/flags';
import i18n from '@/i18n';
import { capitalised, money, timeOf, whenLabel } from '@/state/format';
import { useSession } from '@/state/session';
import { Avatar } from '@/ui/Avatar';
import { Button } from '@/ui/Button';
import { CODE_LENGTH, CodeInput } from '@/ui/CodeInput';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { spacedLocalNumber, TextField } from '@/ui/TextField';

type PayMethod = 'MTN_MOMO' | 'ORANGE_MONEY' | 'CARD';

type Params = {
  holdId: string;
  specialistId: string;
  start: string;
  end: string;
  expiresAt: string;
  modality: Modality;
  language: string;
  recommendationId?: string;
  healthCheckResponseId?: string;
  shareHealthCheck?: string;
};

/**
 * Verify and pay (Checkout.dc.html). A signed-in seeker books straight away; a guest first proves
 * their phone with a 6-digit code (D34). Paying books the held time, then asks the chosen wallet for
 * the price and opens Approve payment; the booking keeps the time until the hold window ends.
 */
/** Where the booking came from: a tracking invite, the health check, or search (D52, D55). */
function bookingRequest(params: Params) {
  const base = { holdId: params.holdId, sessionLanguage: params.language };
  if (params.recommendationId) {
    return {
      ...base,
      source: 'TRACKING_RECOMMENDATION' as const,
      recommendationId: params.recommendationId,
    };
  }
  if (params.healthCheckResponseId) {
    return {
      ...base,
      source: 'HEALTH_CHECK' as const,
      healthCheckResponseId: params.healthCheckResponseId,
      shareHealthCheck: params.shareHealthCheck === '1',
    };
  }
  return { ...base, source: 'SEARCH' as const };
}

export default function Checkout() {
  const { t } = useTranslation();
  const params = useLocalSearchParams<Params>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const member = useAuth((s) => s.status === 'signedIn') && useSession.getState().role === 'seeker';
  const profile = useSpecialistProfile(params.specialistId, lang);
  const languages = useSessionLanguages(lang);

  const [phone, setPhone] = useState('');
  const [codeSentTo, setCodeSentTo] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [method, setMethod] = useState<PayMethod>('MTN_MOMO');
  const [problem, setProblem] = useState<string | null>(null);

  const sendCode = useSendGuestCode();
  const book = useCreateBooking();
  const guestBook = useCreateGuestBooking();
  const busy = book.isPending || guestBook.isPending;

  const p = profile.data;
  const offering = p?.offerings.find((o) => o.modality === params.modality);
  const minutes = Math.round((Date.parse(params.end) - Date.parse(params.start)) / 60_000);
  const languageName =
    languages.data?.find((l) => l.code === params.language)?.name ?? params.language;
  const e164 = toE164(phone);
  const ready =
    member || (codeSentTo !== null && codeSentTo === e164 && code.length === CODE_LENGTH);

  const say = (e: unknown) =>
    setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));

  const send = () => {
    if (!e164) return;
    setProblem(null);
    setCode('');
    sendCode.mutate(
      { phone: e164, appLanguage: lang },
      { onSuccess: () => setCodeSentTo(e164), onError: say },
    );
  };

  const pay = () => {
    if (!ready || busy) return;
    setProblem(null);
    // The booking exists from here: whatever happens to the payment request, Approve payment shows
    // it and can send it again.
    const opened = async (bookingId: string, guestToken?: string) => {
      if (method !== 'CARD') {
        await startPayment({ bookingId, guestToken }, { method }).catch(() => undefined);
      }
      router.replace({ pathname: '/book/pay', params: { id: bookingId } });
    };
    if (member) {
      book.mutate(bookingRequest(params), { onSuccess: (b) => opened(b.id), onError: say });
    } else {
      guestBook.mutate(
        {
          holdId: params.holdId,
          sessionLanguage: params.language,
          phone: e164!,
          code,
          appLanguage: lang,
        },
        {
          onSuccess: (made) => opened(made.booking.id, made.guestToken),
          onError: (e) => {
            setCode('');
            say(e);
          },
        },
      );
    }
  };

  const methods: PayMethod[] = flags.cardPayments
    ? ['MTN_MOMO', 'ORANGE_MONEY', 'CARD']
    : ['MTN_MOMO', 'ORANGE_MONEY'];

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.titleRow}>
          <BackButton />
          <View style={styles.titleText}>
            <Text variant="meta" color={colors.inkMuted}>
              {t('checkout.step')}
            </Text>
            <Text variant="titleMd" accessibilityRole="header">
              {t('checkout.title')}
            </Text>
          </View>
        </View>

        <View style={styles.card}>
          <Avatar name={p?.fullName ?? ''} />
          <View style={styles.cardText}>
            <Text variant="label" style={styles.strong}>
              {p?.fullName ?? ''}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('checkout.summary', {
                when: capitalised(whenLabel(params.start, t, lang)),
                modality: t(`book.modality_${params.modality}`),
                language: languageName,
                minutes,
              })}
            </Text>
          </View>
        </View>

        {member ? null : (
          <View style={styles.section}>
            <Text variant="label" style={styles.heading} accessibilityRole="header">
              {t('checkout.reachQ')}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('checkout.reachLead')}
            </Text>
            <View style={styles.phoneRow}>
              <View style={styles.phoneField}>
                <TextField
                  label={t('checkout.phoneLabel')}
                  prefix={CAMEROON_PREFIX}
                  value={spacedLocalNumber(phone)}
                  onChangeText={(text) => setPhone(text.replace(/\D/g, '').slice(0, 9))}
                  keyboardType="phone-pad"
                  autoComplete="tel"
                  textContentType="telephoneNumber"
                  placeholder="6 77 00 00 00"
                />
              </View>
              <Button
                variant="secondary"
                label={codeSentTo === e164 && e164 ? t('checkout.resend') : t('checkout.sendCode')}
                disabled={!e164 || sendCode.isPending}
                accessibilityState={{
                  disabled: !e164 || sendCode.isPending,
                  busy: sendCode.isPending,
                }}
                onPress={send}
                style={styles.sendButton}
              />
            </View>
            {codeSentTo ? (
              <>
                <Text variant="meta" color={colors.inkMuted} accessibilityLiveRegion="polite">
                  {t('checkout.codeSent', { contact: displayContact(codeSentTo) })}
                </Text>
                <CodeInput
                  compact
                  autoFocus
                  label={t('checkout.codeLabel')}
                  value={code}
                  onChange={setCode}
                  onSubmit={pay}
                />
              </>
            ) : null}
          </View>
        )}

        <View accessibilityRole="radiogroup" style={styles.section}>
          <Text variant="label" style={styles.heading} accessibilityRole="header">
            {t('checkout.payWith')}
          </Text>
          {methods.map((m) => {
            const on = m === method;
            return (
              <Pressable
                key={m}
                accessibilityRole="radio"
                accessibilityState={{ checked: on }}
                accessibilityLabel={t(`checkout.${m}`)}
                onPress={() => setMethod(m)}
                style={[styles.method, on && styles.methodOn]}
              >
                <View style={[styles.radio, on && styles.radioOn]} aria-hidden>
                  {on ? <View style={styles.radioDot} /> : null}
                </View>
                <Text variant="label" style={[styles.strong, styles.methodName]}>
                  {t(`checkout.${m}`)}
                </Text>
                {on && m !== 'CARD' && !member ? (
                  <Text variant="meta" color={colors.inkMuted}>
                    {t('checkout.sameNumber')}
                  </Text>
                ) : null}
              </Pressable>
            );
          })}
        </View>
        {problem ? (
          <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
            {problem}
          </Text>
        ) : null}
      </ScrollView>

      <View style={styles.footer}>
        <Text variant="meta" color={colors.inkMuted}>
          {t('checkout.approve')}{' '}
          {t('checkout.heldUntil', { time: timeOf(new Date(params.expiresAt), lang) })}
        </Text>
        <Button
          label={
            offering
              ? t('checkout.pay', { price: money(offering.price, lang) })
              : t('common.loading')
          }
          disabled={!ready || busy || !offering}
          accessibilityState={{ disabled: !ready || busy || !offering, busy }}
          onPress={pay}
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
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  cardText: {
    flex: 1,
    gap: space.space2,
  },
  section: {
    gap: space.space10,
  },
  heading: {
    fontSize: 15,
    fontFamily: fontFamily.body[700],
  },
  phoneRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: space.space8,
  },
  phoneField: {
    flex: 1,
  },
  sendButton: {
    minHeight: size.sizeFieldMobile,
    paddingHorizontal: space.space16,
  },
  method: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    minHeight: 52,
    paddingHorizontal: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  methodOn: {
    borderWidth: 2,
    borderColor: colors.brand,
    backgroundColor: colors.brandTint,
  },
  methodName: {
    flex: 1,
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
  strong: {
    fontFamily: fontFamily.body[600],
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
