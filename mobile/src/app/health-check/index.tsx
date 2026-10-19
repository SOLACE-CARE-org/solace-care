import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Check, Clock, Lock, Stethoscope, UserCheck } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { useSaveConsents } from '@/api/account';
import { guestResponse, startHealthCheck, useHealthCheckForm } from '@/api/healthCheck';
import { useAuth } from '@/auth/auth';
import i18n from '@/i18n';
import { useHealthCheck } from '@/state/healthCheck';
import { useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/** The wording version of the consent on this screen; a new wording gets a new version. */
export const HEALTH_CHECK_CONSENT_VERSION = 'v1';

/**
 * Health check: start (HealthCheckIntro.dc.html; D55). The consent comes before the first
 * question; it is never ticked in advance. Works for guests: no account needed.
 */
export default function HealthCheckIntro() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const form = useHealthCheckForm(lang);
  const saveConsents = useSaveConsents();
  const seeker = useAuth((s) => s.status === 'signedIn') && useSession.getState().role === 'seeker';
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const start = async () => {
    if (!form.data) return;
    setBusy(true);
    setFailed(false);
    try {
      if (seeker) {
        await saveConsents.mutateAsync([{ purpose: 'HEALTH_CHECK', granted: true }]);
      }
      const started = await startHealthCheck(HEALTH_CHECK_CONSENT_VERSION);
      if (started.token) await guestResponse.set(started.id, started.token);
      useHealthCheck.getState().begin({
        responseId: started.id,
        token: started.token ?? null,
        questions: form.data.questions,
        priceBands: form.data.priceBands,
      });
      router.replace('/health-check/question');
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const unavailable = form.isError;
  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <View style={styles.footer}>
          <Button
            label={t('healthCheck.intro.start')}
            disabled={!agreed || busy || !form.data}
            accessibilityState={{ disabled: !agreed || busy || !form.data, busy }}
            onPress={() => void start()}
          />
          {!agreed ? (
            <Text variant="meta" color={colors.inkMuted} style={styles.center}>
              {t('healthCheck.intro.tickFirst')}
            </Text>
          ) : null}
        </View>
      }
    >
      <Text variant="meta" color={colors.inkMuted}>
        {t('healthCheck.title')}
      </Text>
      <Text variant="titleLg" accessibilityRole="header">
        {t('healthCheck.intro.heading')}
      </Text>
      <Text variant="body" color={colors.inkMuted}>
        {t('healthCheck.intro.body')}
      </Text>
      <View style={styles.points}>
        <Point
          icon={<Clock size={20} strokeWidth={1.8} color={colors.brand} />}
          text={t('healthCheck.intro.time')}
        />
        <Point
          icon={<Stethoscope size={20} strokeWidth={1.8} color={colors.brand} />}
          text={t('healthCheck.intro.clinical')}
        />
        <Point
          icon={<UserCheck size={20} strokeWidth={1.8} color={colors.brand} />}
          text={t('healthCheck.intro.notDiagnosis')}
        />
        <Point
          icon={<Lock size={20} strokeWidth={1.8} color={colors.brand} />}
          text={t('healthCheck.intro.private')}
        />
      </View>
      {unavailable ? (
        <View style={styles.card}>
          <Text variant="body">{t('healthCheck.intro.unavailable')}</Text>
          <Button
            variant="secondary"
            label={t('healthCheck.matches.browse')}
            onPress={() => router.replace('/search')}
          />
        </View>
      ) : (
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{ checked: agreed }}
          accessibilityLabel={t('healthCheck.intro.consent')}
          onPress={() => setAgreed((a) => !a)}
          style={styles.consent}
        >
          <View style={[styles.box, agreed && styles.boxOn]}>
            {agreed ? <Check size={16} strokeWidth={2.4} color={colors.onBrand} /> : null}
          </View>
          <Text variant="body" style={styles.flex}>
            {t('healthCheck.intro.consent')}
          </Text>
        </Pressable>
      )}
      {failed ? (
        <Text variant="meta" color={colors.danger} accessibilityRole="alert">
          {t('common.checkConnection')}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

function Point({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <View style={styles.point}>
      <View aria-hidden>{icon}</View>
      <Text variant="body" style={styles.flex}>
        {text}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  footer: { gap: space.space8 },
  center: { textAlign: 'center' },
  flex: { flex: 1 },
  points: { gap: space.space12, marginVertical: space.space8 },
  point: { flexDirection: 'row', gap: space.space12, alignItems: 'center' },
  consent: {
    flexDirection: 'row',
    gap: space.space12,
    alignItems: 'flex-start',
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
    minHeight: 44,
  },
  box: {
    width: 24,
    height: 24,
    borderRadius: radius.radius6,
    borderWidth: 2,
    borderColor: colors.lineStrong,
    alignItems: 'center',
    justifyContent: 'center',
  },
  boxOn: { backgroundColor: colors.brand, borderColor: colors.brand },
  card: {
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
});
