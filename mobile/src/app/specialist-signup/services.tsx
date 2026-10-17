import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Mic, Video } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { ApiProblem } from '@/api/core';
import {
  type Offering,
  SPECIALIST_STEPS,
  resumeRoute,
  useApplication,
  useSaveServices,
} from '@/api/specialist';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

type Modality = Offering['modality'];
type Draft = { enabled: boolean; durationMinutes: number; price: string };

const MODALITIES: Modality[] = ['AUDIO', 'VIDEO'];

/** Specialist application, step 5 of 7: sessions you offer (SpecServices.dc.html). */
export default function SpecialistServices() {
  const { t } = useTranslation();
  const application = useApplication();
  const save = useSaveServices();
  const [problem, setProblem] = useState<string | null>(null);
  const [edits, setEdits] = useState<Partial<Record<Modality, Draft>>>({});

  const pricing = application.data?.pricing;
  const durations = pricing?.durations ?? [];
  const defaultDuration = durations.includes(50) ? 50 : (durations[0] ?? 50);
  const draftOf = (modality: Modality): Draft => {
    if (edits[modality]) return edits[modality];
    const saved = application.data?.offerings.find((o) => o.modality === modality);
    return saved
      ? { enabled: saved.enabled, durationMinutes: saved.durationMinutes, price: saved.enabled ? String(saved.price) : '' }
      : { enabled: true, durationMinutes: defaultDuration, price: '' };
  };
  const edit = (modality: Modality, patch: Partial<Draft>) =>
    setEdits((e) => ({ ...e, [modality]: { ...draftOf(modality), ...patch } }));

  const inRange = (price: string) =>
    pricing !== undefined &&
    /^\d+$/.test(price) &&
    Number(price) >= pricing.minPrice &&
    Number(price) <= pricing.maxPrice;
  const drafts = MODALITIES.map((m) => [m, draftOf(m)] as const);
  const ready =
    pricing !== undefined &&
    drafts.some(([, d]) => d.enabled) &&
    drafts.every(([, d]) => !d.enabled || inRange(d.price));

  const money = (amount: number) => new Intl.NumberFormat(i18n.language).format(amount);

  const submit = () => {
    if (!ready) return;
    setProblem(null);
    save.mutate(
      drafts.map(([modality, d]) => ({
        modality,
        enabled: d.enabled,
        durationMinutes: d.durationMinutes,
        price: d.enabled ? Number(d.price) : 0,
      })),
      {
        onSuccess: (next) => router.push(resumeRoute(next)),
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  return (
    <FlowScreen
      top={<ProgressSteps step={5} total={SPECIALIST_STEPS} />}
      footer={
        <Button
          label={t('common.continue')}
          disabled={!ready || save.isPending}
          accessibilityState={{ disabled: !ready || save.isPending, busy: save.isPending }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('specialistSignup.services.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('specialistSignup.services.subtitle')}
        </Text>
      </View>

      {drafts.map(([modality, draft]) => {
        const Icon = modality === 'AUDIO' ? Mic : Video;
        const invalid = draft.enabled && draft.price !== '' && !inRange(draft.price);
        return (
          <View key={modality} style={[styles.card, draft.enabled && styles.cardOn]}>
            <SwitchRow
              title={t(`specialistSignup.services.${modality}`)}
              icon={<Icon size={20} strokeWidth={1.8} color={colors.brand} />}
              on={draft.enabled}
              onToggle={() => edit(modality, { enabled: !draft.enabled })}
            />
            {draft.enabled ? (
              <View style={styles.fields}>
                <View style={styles.field}>
                  <Text variant="label" color={colors.inkMuted} style={styles.label}>
                    {t('specialistSignup.services.lengthLabel')}
                  </Text>
                  <Segmented
                    label={`${t(`specialistSignup.services.${modality}`)}, ${t('specialistSignup.services.lengthLabel')}`}
                    options={durations.map((minutes) => ({
                      value: String(minutes),
                      label: t('specialistSignup.services.minutes', { count: minutes }),
                    }))}
                    value={String(draft.durationMinutes)}
                    onChange={(v) => edit(modality, { durationMinutes: Number(v) })}
                  />
                </View>
                <TextField
                  label={t('specialistSignup.services.priceLabel')}
                  accessibilityLabel={`${t(`specialistSignup.services.${modality}`)}, ${t('specialistSignup.services.priceLabel')}`}
                  value={draft.price}
                  onChangeText={(v) => edit(modality, { price: v.replace(/\D/g, '').slice(0, 7) })}
                  keyboardType="number-pad"
                  error={
                    invalid && pricing
                      ? t('specialistSignup.services.range', {
                          min: money(pricing.minPrice),
                          max: money(pricing.maxPrice),
                        })
                      : undefined
                  }
                />
              </View>
            ) : null}
          </View>
        );
      })}

      {pricing ? (
        <View style={styles.rules}>
          <Text variant="meta" color={colors.inkSoft}>
            <Text variant="meta" color={colors.ink} style={styles.strong}>
              {t('specialistSignup.services.rangeLabel')}
            </Text>{' '}
            {t('specialistSignup.services.range', {
              min: money(pricing.minPrice),
              max: money(pricing.maxPrice),
            })}
          </Text>
          <Text variant="meta" color={colors.inkSoft}>
            <Text variant="meta" color={colors.ink} style={styles.strong}>
              {t('specialistSignup.services.receiveLabel')}
            </Text>{' '}
            {t('specialistSignup.services.receive', { fee: money(pricing.feePercent) })}
          </Text>
        </View>
      ) : null}

      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space6,
  },
  card: {
    paddingHorizontal: space.space16,
    paddingBottom: space.space4,
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  cardOn: {
    borderWidth: 2,
    borderColor: colors.brand,
    paddingHorizontal: space.space16 - 1,
  },
  fields: {
    gap: space.space12,
    paddingBottom: space.space14,
  },
  field: {
    gap: space.space6,
  },
  label: {
    fontSize: 13,
  },
  rules: {
    gap: space.space6,
    padding: space.space14,
    borderRadius: radius.radius14,
    backgroundColor: colors.brandTint,
  },
  strong: {
    fontFamily: fontFamily.body[700],
  },
});
