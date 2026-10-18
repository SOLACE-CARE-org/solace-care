import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { type Earnings, useEarnings } from '@/api/payments';
import { flags } from '@/config/flags';
import i18n from '@/i18n';
import { capitalised, money, whenLabel } from '@/state/format';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';

type Activity = Earnings['activity'][number];

/**
 * Earnings & payouts (Earnings.dc.html): the released balance, held money for sessions still to
 * come, and the latest sessions and payouts. Payouts go out in twice-monthly runs approved by two
 * people (D35); Pay out now stays behind its flag.
 */
export default function SpecialistEarnings() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const earnings = useEarnings();
  const e = earnings.data;

  if (!e) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        {earnings.isError ? (
          <>
            <Text variant="body" color={colors.inkMuted}>
              {t('common.checkConnection')}
            </Text>
            <Button label={t('common.tryAgain')} onPress={() => earnings.refetch()} />
          </>
        ) : (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        )}
      </SafeAreaView>
    );
  }

  const next = e.nextPayout
    ? new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short' }).format(
        new Date(`${e.nextPayout}T12:00:00`),
      )
    : '';

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={earnings.isRefetching}
            onRefresh={() => void earnings.refetch()}
          />
        }
      >
        <Text variant="titleLg" accessibilityRole="header">
          {t('earnings.title')}
        </Text>

        <View style={styles.balance}>
          <Text variant="meta" color={colors.brandTintStrong}>
            {t('earnings.available')}
          </Text>
          <Text style={styles.amount}>
            {t('earnings.amount', { amount: money(e.available, lang) })}
          </Text>
          <Text variant="meta" color={colors.brandTintStrong}>
            {e.payoutTo
              ? t('earnings.payoutTo', {
                  method: t(`earnings.${e.payoutTo.method}`),
                  end: e.payoutTo.phoneEnd,
                  date: next,
                })
              : t('earnings.noPayoutTo', { date: next })}
          </Text>
          {flags.payoutOnRequest ? (
            <Button variant="secondary" label={t('earnings.payOutNow')} />
          ) : null}
        </View>

        <View style={styles.stats}>
          <Stat
            label={t('earnings.thisMonth')}
            value={e.sessionsThisMonth}
            unit={t('earnings.sessions')}
          />
          <Stat
            label={t('earnings.onHold')}
            value={e.onHoldSessions}
            unit={t('earnings.upcoming')}
          />
          <Stat
            label={t('earnings.noShows')}
            value={e.noShowsThisMonth}
            unit={t('earnings.thisMonthLower')}
          />
        </View>

        <Text variant="label" style={styles.bold} accessibilityRole="header">
          {t('earnings.activity')}
        </Text>
        {e.activity.length === 0 ? (
          <Text variant="body" color={colors.inkMuted}>
            {t('earnings.none')}
          </Text>
        ) : (
          e.activity.map((a, i) => <Row key={`${a.at}-${i}`} item={a} lang={lang} />)
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function Stat({ label, value, unit }: { label: string; value: number; unit: string }) {
  return (
    <View style={styles.stat} accessible accessibilityLabel={`${label}: ${value} ${unit}`}>
      <Text variant="meta" color={colors.inkMuted}>
        {label}
      </Text>
      <Text style={styles.statValue}>{value}</Text>
      <Text variant="meta" color={colors.inkMuted}>
        {unit}
      </Text>
    </View>
  );
}

function Row({ item: a, lang }: { item: Activity; lang: string }) {
  const { t } = useTranslation();
  const when = capitalised(whenLabel(a.at, t, lang));
  const title =
    a.kind === 'SESSION'
      ? t('earnings.session', { name: a.seekerLabel ?? '' })
      : a.kind === 'PAYOUT'
        ? t('earnings.payout', { method: t(`earnings.${a.method ?? 'MTN_MOMO'}`) })
        : t('earnings.adjustment');
  const meta =
    a.kind === 'PAYOUT'
      ? t('earnings.payoutWhen', { when, status: t(`earnings.payoutStatus_${a.status ?? 'SENT'}`) })
      : t('earnings.afterFee', { when });
  const amount = `${a.amount >= 0 ? '+' : '−'}${money(Math.abs(a.amount), lang)}`;
  return (
    <View style={styles.row} accessible accessibilityLabel={`${title}, ${meta}, ${amount} FCFA`}>
      <View style={styles.flex}>
        <Text variant="label" style={styles.bold}>
          {title}
        </Text>
        <Text variant="meta" color={colors.inkMuted}>
          {meta}
        </Text>
      </View>
      <Text variant="label" color={a.amount >= 0 ? colors.success : colors.ink} style={styles.bold}>
        {amount}
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
  },
  content: {
    paddingHorizontal: space.space20,
    paddingTop: space.space24,
    paddingBottom: space.space16,
    gap: space.space16,
  },
  balance: {
    gap: space.space6,
    padding: space.space20,
    borderRadius: radius.radius18,
    backgroundColor: colors.brand,
  },
  amount: {
    fontFamily: fontFamily.display[700],
    fontSize: 32,
    color: colors.onBrand,
  },
  stats: {
    flexDirection: 'row',
    gap: space.space8,
  },
  stat: {
    flex: 1,
    gap: space.space2,
    padding: space.space12,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  statValue: {
    fontFamily: fontFamily.display[700],
    fontSize: 22,
    color: colors.ink,
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
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
});
