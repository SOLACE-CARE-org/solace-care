import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { useAuth } from '@/auth/auth';
import i18n from '@/i18n';
import { useHealthCheck } from '@/state/healthCheck';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/** "A clinical psychologist", "An endocrinologist": the article English needs. */
export function fitKey(person: string, lang: string): 'fitA' | 'fitAn' {
  return lang === 'en' && /^[aeiou]/i.test(person) ? 'fitAn' : 'fitA';
}

/**
 * Health check: your suggestion (HealthCheckResult.dc.html; D55). The specialty, the reasons the
 * rules gave, a close second, and that this is not a diagnosis. Totals and bands are never shown.
 */
export default function HealthCheckResultScreen() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const result = useHealthCheck((s) => s.result);
  const guest = useAuth((s) => s.status !== 'signedIn');

  if (!result) {
    return (
      <FlowScreen top={<BackButton />}>
        <Text variant="body">{t('common.checkConnection')}</Text>
      </FlowScreen>
    );
  }

  const person =
    lang === 'en' ? result.specialty.person.toLocaleLowerCase('en') : result.specialty.person;

  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <View style={styles.footer}>
          <Button
            label={t('healthCheck.result.see')}
            onPress={() => router.push('/health-check/matches')}
          />
          {guest ? (
            <Button
              variant="secondary"
              label={t('healthCheck.result.keep')}
              onPress={() => router.push('/signup/details')}
            />
          ) : null}
        </View>
      }
    >
      <Text variant="meta" color={colors.inkMuted}>
        {t('healthCheck.step.4')}
      </Text>
      <Text variant="meta" color={colors.inkMuted} style={styles.bold}>
        {t('healthCheck.result.title').toUpperCase()}
      </Text>
      <Text variant="titleLg" accessibilityRole="header">
        {t(`healthCheck.result.${fitKey(person, lang)}`, { person })}
      </Text>

      <View style={styles.card}>
        <Text variant="titleSm">{t('healthCheck.result.why')}</Text>
        {result.reasons.map((r) => (
          <Text key={r} variant="body">
            {`• ${r}`}
          </Text>
        ))}
      </View>

      {result.alsoFit ? (
        <View style={styles.card}>
          <Text variant="meta" color={colors.inkMuted} style={styles.bold}>
            {t('healthCheck.result.alsoFit')}
          </Text>
          <Text variant="titleSm">{result.alsoFit.person}</Text>
          {result.alsoFitReason ? (
            <Text variant="body" color={colors.inkSoft}>
              {result.alsoFitReason}
            </Text>
          ) : null}
        </View>
      ) : null}

      <Text variant="body" color={colors.inkSoft}>
        {t('healthCheck.result.notDiagnosis')}
      </Text>
      <Text variant="meta" color={colors.inkMuted}>
        {t('healthCheck.result.retake')}
      </Text>
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  footer: { gap: space.space8 },
  bold: { fontWeight: '600' },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
});
