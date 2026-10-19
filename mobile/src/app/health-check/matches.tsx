import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useHealthCheckMatches } from '@/api/healthCheck';
import { useAuth } from '@/auth/auth';
import i18n from '@/i18n';
import { longWhen } from '@/state/bookingView';
import { money } from '@/state/format';
import { useHealthCheck } from '@/state/healthCheck';
import { Avatar } from '@/ui/Avatar';
import { Button } from '@/ui/Button';
import { Pill } from '@/ui/Pill';
import { BackButton } from '@/ui/ProgressSteps';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';

/**
 * Matches from the health check (Matches.dc.html; D55): the suggested specialty first, then the
 * close second, in the chosen language, format and price band. A seeker can share their answers
 * with the specialist they book; bookings carry source HEALTH_CHECK.
 */
export default function HealthCheckMatches() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const { responseId, token, result, preferences } = useHealthCheck();
  const signedIn = useAuth((s) => s.status === 'signedIn');
  const matches = useHealthCheckMatches(responseId, token);
  const [share, setShare] = useState(false);

  const summary = t('healthCheck.matches.summary', {
    language:
      preferences.sessionLanguage === 'fr'
        ? t('common.french')
        : preferences.sessionLanguage === 'en'
          ? t('common.english')
          : t('healthCheck.matches.anyLanguage'),
    format:
      preferences.modality === 'VIDEO'
        ? t('healthCheck.prefs.video')
        : preferences.modality === 'AUDIO'
          ? t('healthCheck.prefs.audio')
          : t('healthCheck.matches.anyFormat'),
    price: preferences.maxPrice
      ? t('healthCheck.prefs.upTo', { amount: money(preferences.maxPrice, lang) })
      : t('healthCheck.prefs.anyPrice'),
  });

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('healthCheck.matches.title')}
        </Text>
        <View style={styles.row}>
          <Text variant="meta" color={colors.inkMuted} style={styles.flex}>
            {t('healthCheck.matches.from')}
          </Text>
          <Button
            variant="quiet"
            label={t('healthCheck.matches.edit')}
            onPress={() => router.replace('/health-check/preferences')}
          />
        </View>
        {result ? (
          <View style={styles.card}>
            <Text variant="titleSm">
              {result.specialty.person}
              {result.alsoFit
                ? ` · ${t('healthCheck.matches.also', { person: result.alsoFit.person.toLocaleLowerCase(lang) })}`
                : ''}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {summary}
            </Text>
          </View>
        ) : null}
        {signedIn ? (
          <SwitchRow
            title={t('healthCheck.matches.share')}
            on={share}
            onToggle={() => setShare((s) => !s)}
          />
        ) : null}

        {matches.isError ? (
          <View style={styles.card}>
            <Text variant="body">{t('common.checkConnection')}</Text>
            <Button
              variant="secondary"
              label={t('common.tryAgain')}
              onPress={() => void matches.refetch()}
            />
          </View>
        ) : matches.isPending ? (
          <Text variant="meta" color={colors.inkMuted}>
            {t('common.loading')}
          </Text>
        ) : (matches.data ?? []).length === 0 ? (
          <Text variant="body" color={colors.inkMuted}>
            {t('healthCheck.matches.none')}
          </Text>
        ) : (
          (matches.data ?? []).map((m) => (
            <Pressable
              key={m.id}
              accessibilityRole="button"
              onPress={() =>
                router.push({
                  pathname: '/book/[id]',
                  params: {
                    id: m.id,
                    healthCheckResponseId: responseId ?? '',
                    ...(share ? { shareHealthCheck: '1' } : {}),
                  },
                })
              }
              style={[styles.card, styles.match]}
            >
              <Avatar name={m.fullName} />
              <View style={styles.flex}>
                <Text variant="label" style={styles.bold}>
                  {m.fullName}
                </Text>
                <Text variant="meta" color={colors.inkMuted}>
                  {m.professionLabel} · {m.languages.join(', ')}
                </Text>
                {m.alsoFit ? (
                  <Pill label={t('healthCheck.matches.alsoFit')} tone="neutral" />
                ) : null}
                <Text variant="meta" color={colors.inkSoft}>
                  {[
                    m.focusAreas.length
                      ? t('healthCheck.matches.worksWith', {
                          topics: m.focusAreas.join(', ').toLocaleLowerCase(lang),
                        })
                      : null,
                    m.nextAvailable
                      ? t('healthCheck.matches.slot', { when: longWhen(m.nextAvailable, lang) })
                      : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </Text>
              </View>
            </Pressable>
          ))
        )}

        <Text variant="meta" color={colors.inkMuted}>
          {t('healthCheck.matches.footer')}
        </Text>
        <Button
          variant="secondary"
          label={t('healthCheck.matches.browse')}
          onPress={() => router.push('/search')}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  content: { padding: space.space20, gap: space.space16 },
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
  row: { flexDirection: 'row', alignItems: 'center' },
  card: {
    gap: space.space4,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  match: { flexDirection: 'row', alignItems: 'center', gap: space.space12 },
});
