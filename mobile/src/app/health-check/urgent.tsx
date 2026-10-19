import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Phone } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Linking, Pressable, StyleSheet, View } from 'react-native';

import { requestCallback } from '@/api/healthCheck';
import { useAuth } from '@/auth/auth';
import { flags } from '@/config/flags';
import { useEmergencyNumbers } from '@/content/crisis';
import { useHealthCheck } from '@/state/healthCheck';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/**
 * Health check: urgent help after the safety answer (HealthCheckUrgent.dc.html; D55). A
 * signed-in seeker's on-call alert has already gone; a guest can leave a number for a call back.
 * The local emergency number works offline.
 */
export default function HealthCheckUrgent() {
  const { t } = useTranslation();
  const numbers = useEmergencyNumbers((s) => s.numbers);
  const signedIn = useAuth((s) => s.status === 'signedIn');
  const { responseId, token } = useHealthCheck();
  const [phone, setPhone] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const emergency = numbers[0]?.number ?? '119';

  const callBack = async () => {
    if (!responseId) return;
    setBusy(true);
    setFailed(false);
    try {
      await requestCallback(responseId, token, phone.replace(/\s/g, ''));
      setSent(true);
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
          variant="secondary"
          label={t('healthCheck.urgent.continue')}
          onPress={() => router.back()}
        />
      }
    >
      <Text variant="titleLg" accessibilityRole="header">
        {t('healthCheck.urgent.title')}
      </Text>
      <Text variant="body" color={colors.inkSoft}>
        {t('healthCheck.urgent.body')}
      </Text>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${t('healthCheck.urgent.call', { number: emergency })}, ${t('healthCheck.urgent.callMeta')}`}
        onPress={() => void Linking.openURL(`tel:${emergency}`)}
        style={[styles.card, styles.emergency]}
      >
        <Phone size={22} strokeWidth={1.8} color={colors.onBrand} aria-hidden />
        <View style={styles.flex}>
          <Text variant="label" color={colors.onBrand} style={styles.bold}>
            {t('healthCheck.urgent.call', { number: emergency })}
          </Text>
          <Text variant="meta" color={colors.onBrand}>
            {t('healthCheck.urgent.callMeta')}
          </Text>
        </View>
      </Pressable>

      {flags.onCallSpecialistCall ? (
        <View style={styles.card}>
          <Text variant="label" style={styles.bold}>
            {t('healthCheck.urgent.onCall')}
          </Text>
          <Text variant="meta" color={colors.inkMuted}>
            {t('healthCheck.urgent.onCallMeta')}
          </Text>
        </View>
      ) : null}

      {!signedIn ? (
        <View style={styles.card} accessibilityLiveRegion="polite">
          {sent ? (
            <Text variant="body">{t('healthCheck.urgent.called')}</Text>
          ) : (
            <>
              <Text variant="label" style={styles.bold}>
                {t('healthCheck.urgent.leaveNumber')}
              </Text>
              <TextField
                label={t('healthCheck.urgent.phone')}
                value={phone}
                onChangeText={setPhone}
                keyboardType="phone-pad"
                autoComplete="tel"
              />
              <Button
                label={t('healthCheck.urgent.callMe')}
                disabled={busy || phone.replace(/\D/g, '').length < 8}
                onPress={() => void callBack()}
              />
            </>
          )}
          {failed ? (
            <Text variant="meta" color={colors.danger} accessibilityRole="alert">
              {t('common.checkConnection')}
            </Text>
          ) : null}
        </View>
      ) : null}

      <Text variant="meta" color={colors.inkMuted}>
        {t('healthCheck.urgent.note')}
      </Text>
      <Text variant="meta" color={colors.inkMuted}>
        {t('healthCheck.urgent.offline')}
      </Text>
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  emergency: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    backgroundColor: colors.danger,
    borderColor: colors.danger,
    minHeight: 64,
  },
});
