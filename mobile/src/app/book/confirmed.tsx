import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { CircleCheck } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { useBooking, useGuestToken } from '@/api/bookings';
import i18n from '@/i18n';
import { minutesOf } from '@/state/bookingView';
import { capitalised, money, whenLabel } from '@/state/format';
import { Avatar } from '@/ui/Avatar';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';

/**
 * Booking confirmed (BookingConfirmed.dc.html). Guests are offered an account with the same number
 * (their booking moves into it, D34). "Add to my calendar" waits for a calendar integration.
 */
export default function BookingConfirmed() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const token = useGuestToken(id);
  const booking = useBooking(
    { bookingId: id, guestToken: token.data ?? undefined },
    token.isSuccess,
  );
  const languages = useSessionLanguages(lang);
  const b = booking.data;

  if (!b) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
      </SafeAreaView>
    );
  }
  const guest = Boolean(token.data);
  const language =
    languages.data?.find((l) => l.code === b.sessionLanguage)?.name ?? b.sessionLanguage;
  const rows: [string, string][] = [
    [t('confirmed.when'), capitalised(whenLabel(b.start, t, lang))],
    [
      t('confirmed.type'),
      t('confirmed.typeValue', {
        modality: t(`book.modality_${b.modality}`),
        minutes: minutesOf(b),
      }),
    ],
    [t('confirmed.language'), language],
    [t('confirmed.paid'), t('confirmed.paidValue', { amount: money(b.price, lang) })],
  ];

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.icon} aria-hidden>
          <CircleCheck size={36} strokeWidth={1.8} color={colors.brand} />
        </View>
        <View style={styles.heading}>
          <Text variant="titleLg" accessibilityRole="header">
            {t('confirmed.title')}
          </Text>
          <Text variant="body" color={colors.inkMuted}>
            {t('confirmed.lead')}
          </Text>
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
          {rows.map(([label, value]) => (
            <View
              key={label}
              style={styles.row}
              accessible
              accessibilityLabel={`${label}: ${value}`}
            >
              <Text variant="body" color={colors.inkMuted}>
                {label}
              </Text>
              <Text variant="label" style={styles.bold}>
                {value}
              </Text>
            </View>
          ))}
        </View>

        {guest ? (
          <View style={styles.keep}>
            <Text variant="label" style={styles.bold}>
              {t('confirmed.keepTitle')}
            </Text>
            <Text variant="body" color={colors.inkMuted}>
              {t('confirmed.keepBody')}
            </Text>
            <Button
              variant="quiet"
              label={t('confirmed.createAccount')}
              onPress={() => router.push('/signup/details')}
            />
          </View>
        ) : null}
      </ScrollView>
      <View style={styles.footer}>
        <Button
          label={t('confirmed.view')}
          onPress={() => router.replace({ pathname: '/session/[id]', params: { id } })}
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
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  icon: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandTint,
  },
  heading: {
    gap: space.space6,
  },
  card: {
    gap: space.space12,
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
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: space.space8,
  },
  keep: {
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
  footer: {
    paddingHorizontal: space.space20,
    paddingTop: space.space16,
    paddingBottom: space.space14,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
});
