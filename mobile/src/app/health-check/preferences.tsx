import { colors, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { completeHealthCheck, type HealthCheckPreferences } from '@/api/healthCheck';
import i18n from '@/i18n';
import { money } from '@/state/format';
import { useHealthCheck } from '@/state/healthCheck';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/**
 * Health check: preferences (HealthCheckPrefs.dc.html). They narrow the matches and never change
 * the suggestion.
 */
export default function HealthCheckPreferencesScreen() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const { responseId, token, priceBands, preferences } = useHealthCheck();
  const [p, setP] = useState<HealthCheckPreferences>(preferences);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const set = (change: Partial<HealthCheckPreferences>) => setP((x) => ({ ...x, ...change }));
  const drop = (key: keyof HealthCheckPreferences) =>
    setP((x) => {
      const copy = { ...x };
      delete copy[key];
      return copy;
    });

  const see = async () => {
    if (!responseId) return;
    setBusy(true);
    setFailed(false);
    try {
      const result = await completeHealthCheck(responseId, token, p);
      useHealthCheck.getState().setPreferences(p);
      useHealthCheck.getState().finish(result);
      router.replace('/health-check/result');
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <Button
          label={t('healthCheck.prefs.see')}
          disabled={busy}
          accessibilityState={{ disabled: busy, busy }}
          onPress={() => void see()}
        />
      }
    >
      <Text variant="meta" color={colors.inkMuted}>
        {t('healthCheck.step.3')}
      </Text>
      <Text variant="titleMd" accessibilityRole="header">
        {t('healthCheck.prefs.title')}
      </Text>
      <Text variant="body" color={colors.inkMuted}>
        {t('healthCheck.prefs.subtitle')}
      </Text>

      <Group label={t('healthCheck.prefs.language')}>
        <Chip
          role="radio"
          label={t('common.english')}
          on={p.sessionLanguage === 'en'}
          onToggle={() => set({ sessionLanguage: 'en' })}
        />
        <Chip
          role="radio"
          label={t('common.french')}
          on={p.sessionLanguage === 'fr'}
          onToggle={() => set({ sessionLanguage: 'fr' })}
        />
        <Chip
          role="radio"
          label={t('healthCheck.prefs.either')}
          on={!p.sessionLanguage}
          onToggle={() => drop('sessionLanguage')}
        />
      </Group>
      <Group label={t('healthCheck.prefs.format')}>
        <Chip
          role="radio"
          label={t('healthCheck.prefs.audio')}
          on={p.modality === 'AUDIO'}
          onToggle={() => set({ modality: 'AUDIO' })}
        />
        <Chip
          role="radio"
          label={t('healthCheck.prefs.video')}
          on={p.modality === 'VIDEO'}
          onToggle={() => set({ modality: 'VIDEO' })}
        />
        <Chip
          role="radio"
          label={t('healthCheck.prefs.either')}
          on={!p.modality}
          onToggle={() => drop('modality')}
        />
      </Group>
      <Group label={t('healthCheck.prefs.price')}>
        {priceBands.map((band) => (
          <Chip
            key={band}
            role="radio"
            label={t('healthCheck.prefs.upTo', { amount: money(band, lang) })}
            on={p.maxPrice === band}
            onToggle={() => set({ maxPrice: band })}
          />
        ))}
        <Chip
          role="radio"
          label={t('healthCheck.prefs.anyPrice')}
          on={!p.maxPrice}
          onToggle={() => drop('maxPrice')}
        />
      </Group>
      <Group label={t('healthCheck.prefs.gender')}>
        <Chip
          role="radio"
          label={t('healthCheck.prefs.noPreference')}
          on={!p.gender}
          onToggle={() => drop('gender')}
        />
        <Chip
          role="radio"
          label={t('healthCheck.prefs.woman')}
          on={p.gender === 'WOMAN'}
          onToggle={() => set({ gender: 'WOMAN' })}
        />
        <Chip
          role="radio"
          label={t('healthCheck.prefs.man')}
          on={p.gender === 'MAN'}
          onToggle={() => set({ gender: 'MAN' })}
        />
      </Group>
      {failed ? (
        <Text variant="meta" color={colors.danger} accessibilityRole="alert">
          {t('common.checkConnection')}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text variant="label" style={styles.bold}>
        {label}
      </Text>
      <View style={styles.chips} accessibilityRole="radiogroup" accessibilityLabel={label}>
        {children}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  group: { gap: space.space8, marginTop: space.space16 },
  bold: { fontWeight: '600' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.space8 },
});
