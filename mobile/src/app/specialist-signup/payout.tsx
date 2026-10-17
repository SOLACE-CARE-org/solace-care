import { colors, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { ApiProblem } from '@/api/core';
import {
  type PayoutDetails,
  SPECIALIST_STEPS,
  stepRoute,
  useApplication,
  useSavePayout,
  useSubmitApplication,
} from '@/api/specialist';
import { CAMEROON_PREFIX, toE164 } from '@/auth/contact';
import { flags } from '@/config/flags';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { spacedLocalNumber, TextField } from '@/ui/TextField';

type Method = PayoutDetails['method'];

/** Bank transfer waits behind its flag until the bank details to collect are specified (D26). */
const METHODS: Method[] = flags.payoutBankTransfer
  ? ['MTN_MOMO', 'ORANGE_MONEY', 'BANK_TRANSFER']
  : ['MTN_MOMO', 'ORANGE_MONEY'];

/**
 * Specialist application, step 7 of 7: payouts (SpecPayout.dc.html). "Submit application" saves the
 * details and submits; Core refuses if an earlier step is unfinished, and the app goes back to it.
 */
export default function SpecialistPayout() {
  const { t } = useTranslation();
  const application = useApplication();
  const savePayout = useSavePayout();
  const submitApplication = useSubmitApplication();
  const [problem, setProblem] = useState<string | null>(null);

  // The saved number comes back masked, so it is typed again; the method and name are kept.
  const saved = application.data?.payout;
  const [edits, setEdits] = useState<{ method?: Method; phone?: string; holderName?: string }>({});
  const method = edits.method ?? saved?.method ?? 'MTN_MOMO';
  const phone = edits.phone ?? '';
  const holderName = edits.holderName ?? saved?.holderName ?? application.data?.fullName ?? '';
  const mobileMoney = method === 'MTN_MOMO' || method === 'ORANGE_MONEY';
  const e164 = toE164(phone);
  const ready = holderName.trim() !== '' && (!mobileMoney || e164 !== null);
  const busy = savePayout.isPending || submitApplication.isPending;

  const fail = (e: unknown) =>
    setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));

  const submit = () => {
    if (!ready || busy) return;
    setProblem(null);
    savePayout.mutate(
      { method, phone: mobileMoney ? (e164 ?? undefined) : undefined, holderName: holderName.trim() },
      {
        onSuccess: (next) => {
          if (next.status === 'DRAFT' && stepRoute(next) !== '/specialist-signup/payout') {
            // An earlier step is unfinished: finish it first.
            router.push(stepRoute(next));
            return;
          }
          submitApplication.mutate(undefined, {
            onSuccess: () => router.replace('/specialist-signup/review'),
            onError: fail,
          });
        },
        onError: fail,
      },
    );
  };

  return (
    <FlowScreen
      top={<ProgressSteps step={7} total={SPECIALIST_STEPS} />}
      footer={
        <Button
          label={t('specialistSignup.payout.submit')}
          disabled={!ready || busy}
          accessibilityState={{ disabled: !ready || busy, busy }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('specialistSignup.payout.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('specialistSignup.payout.subtitle')}
        </Text>
      </View>

      <View style={styles.field}>
        <Text variant="label" color={colors.inkMuted} style={styles.label}>
          {t('specialistSignup.payout.methodLabel')}
        </Text>
        <View
          accessibilityRole="radiogroup"
          accessibilityLabel={t('specialistSignup.payout.methodLabel')}
          style={styles.options}
        >
          {METHODS.map((m) => (
            <OptionCard
              key={m}
              kind="radio"
              title={t(`specialistSignup.payout.${m}`)}
              selected={m === method}
              onPress={() => setEdits((e) => ({ ...e, method: m }))}
            />
          ))}
        </View>
      </View>

      {mobileMoney ? (
        <TextField
          label={t('specialistSignup.payout.numberLabel')}
          prefix={CAMEROON_PREFIX}
          value={spacedLocalNumber(phone)}
          onChangeText={(text) =>
            setEdits((e) => ({ ...e, phone: text.replace(/\D/g, '').slice(0, 9) }))
          }
          keyboardType="phone-pad"
          autoComplete="tel"
          textContentType="telephoneNumber"
          placeholder="6 99 00 00 00"
        />
      ) : null}

      <TextField
        label={t('specialistSignup.payout.holderLabel')}
        help={t('specialistSignup.payout.holderHelp')}
        value={holderName}
        onChangeText={(v) => setEdits((e) => ({ ...e, holderName: v }))}
        maxLength={120}
        autoComplete="name"
      />

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
  field: {
    gap: space.space6,
  },
  label: {
    fontSize: 13,
  },
  options: {
    gap: space.space10,
  },
});
