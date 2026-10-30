import { colors, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiProblem } from '@/api/core';
import {
  type MyProfession,
  type PracticeStatus,
  useApplication,
  useWithdrawProfession,
  useWithdrawRequest,
  useWithdrawSpecialty,
} from '@/api/specialist';
import { Button } from '@/ui/Button';
import { Pill, type Tone } from '@/ui/Pill';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

const TONES: Record<string, Tone> = {
  APPROVED: 'brand',
  PENDING: 'warning',
  OPEN: 'warning',
  CREATED: 'brand',
  MATCHED: 'brand',
  REJECTED: 'danger',
  DECLINED: 'danger',
  WITHDRAWN: 'neutral',
};

/**
 * Professions and specialties (D62), from the Profile tab. What is approved shows on the profile;
 * what the specialist adds waits for Solace's team, and withdrawing takes effect at once. Booked
 * sessions are kept either way.
 */
export default function SpecialistPractice() {
  const { t } = useTranslation();
  const application = useApplication();
  const withdrawProfession = useWithdrawProfession();
  const withdrawSpecialty = useWithdrawSpecialty();
  const withdrawRequest = useWithdrawRequest();
  const [problem, setProblem] = useState<string | null>(null);

  const a = application.data;
  const approved = a?.status === 'APPROVED';
  const professions = a?.professions ?? [];
  const status = (s: PracticeStatus | string) => t(`specialistPractice.status_${s}`);
  const fail = (e: unknown) =>
    setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));

  const confirm = (title: string, action: () => void) =>
    Alert.alert(title, t('specialistPractice.withdrawBody'), [
      { text: t('specialistPractice.cancel'), style: 'cancel' },
      { text: t('specialistPractice.withdraw'), style: 'destructive', onPress: action },
    ]);

  const live = (p: MyProfession) => p.status === 'APPROVED' || p.status === 'PENDING';

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView contentContainerStyle={styles.content}>
        <BackButton />
        <Text variant="titleMd" accessibilityRole="header">
          {t('specialistPractice.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('specialistPractice.intro')}
        </Text>

        {professions.map((p) => (
          <View key={p.profession} style={styles.card}>
            <View style={styles.head}>
              <Text variant="label" style={styles.flex} accessibilityRole="header">
                {p.label}
              </Text>
              <Pill label={status(p.status)} tone={TONES[p.status] ?? 'neutral'} />
            </View>
            <Text variant="meta" color={colors.inkMuted}>
              {t('specialistPractice.licence', { number: p.licenceNumber })}
            </Text>
            {p.reviewNote ? (
              <Text variant="meta" color={colors.inkSoft}>
                {t('specialistPractice.reason', { reason: p.reviewNote })}
              </Text>
            ) : null}

            {p.specialties.map((s) => (
              <View key={s.specialty} style={styles.specialty}>
                <View style={styles.head}>
                  <Text variant="body" style={styles.flex}>
                    {s.label}
                  </Text>
                  <Pill label={status(s.status)} tone={TONES[s.status] ?? 'neutral'} />
                </View>
                {s.reviewNote ? (
                  <Text variant="meta" color={colors.inkSoft}>
                    {t('specialistPractice.reason', { reason: s.reviewNote })}
                  </Text>
                ) : null}
                {approved && (s.status === 'APPROVED' || s.status === 'PENDING') ? (
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={t('specialistPractice.withdrawSpecialty', {
                      specialty: s.label,
                    })}
                    onPress={() =>
                      confirm(
                        t('specialistPractice.withdrawSpecialty', { specialty: s.label }),
                        () =>
                          withdrawSpecialty.mutate(
                            { profession: p.profession, specialty: s.specialty },
                            { onError: fail },
                          ),
                      )
                    }
                    style={styles.link}
                  >
                    <Text variant="label" color={colors.brand}>
                      {t('specialistPractice.withdraw')}
                    </Text>
                  </Pressable>
                ) : null}
              </View>
            ))}

            {approved && live(p) ? (
              <View style={styles.actions}>
                <Button
                  variant="secondary"
                  label={t('specialistPractice.addSpecialty')}
                  onPress={() =>
                    router.push({
                      pathname: '/specialist-practice/add-specialty',
                      params: { profession: p.profession },
                    })
                  }
                />
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={t('specialistPractice.withdrawProfession', {
                    profession: p.label,
                  })}
                  onPress={() =>
                    confirm(
                      t('specialistPractice.withdrawProfession', { profession: p.label }),
                      () => withdrawProfession.mutate(p.profession, { onError: fail }),
                    )
                  }
                  style={styles.link}
                >
                  <Text variant="label" color={colors.brand}>
                    {t('specialistPractice.withdrawProfession', { profession: p.label })}
                  </Text>
                </Pressable>
              </View>
            ) : null}
          </View>
        ))}

        {(a?.specialtyRequests ?? []).length > 0 ? (
          <View style={styles.card}>
            <Text variant="label" accessibilityRole="header">
              {t('specialistPractice.requests')}
            </Text>
            {(a?.specialtyRequests ?? []).map((r) => (
              <View key={r.id} style={styles.specialty}>
                <View style={styles.head}>
                  <Text variant="body" style={styles.flex}>
                    {r.proposedName}
                  </Text>
                  <Pill
                    label={t(`specialistPractice.request_${r.status}`)}
                    tone={TONES[r.status] ?? 'neutral'}
                  />
                </View>
                {r.specialtyLabel ? (
                  <Text variant="meta" color={colors.inkMuted}>
                    {t('specialistPractice.becomes', { specialty: r.specialtyLabel })}
                  </Text>
                ) : null}
                {r.reviewNote ? (
                  <Text variant="meta" color={colors.inkSoft}>
                    {t('specialistPractice.reason', { reason: r.reviewNote })}
                  </Text>
                ) : null}
                {r.status === 'OPEN' ? (
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={t('specialistPractice.withdrawRequest', {
                      name: r.proposedName,
                    })}
                    onPress={() =>
                      confirm(
                        t('specialistPractice.withdrawRequest', { name: r.proposedName }),
                        () => withdrawRequest.mutate(r.id, { onError: fail }),
                      )
                    }
                    style={styles.link}
                  >
                    <Text variant="label" color={colors.brand}>
                      {t('specialistPractice.withdraw')}
                    </Text>
                  </Pressable>
                ) : null}
              </View>
            ))}
          </View>
        ) : null}

        {problem ? (
          <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
            {problem}
          </Text>
        ) : null}

        {approved ? (
          <Button
            label={t('specialistPractice.addProfession')}
            onPress={() => router.push('/specialist-practice/add-profession')}
          />
        ) : (
          <Text variant="meta" color={colors.inkMuted}>
            {t('specialistPractice.afterApproval')}
          </Text>
        )}
      </ScrollView>
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
    gap: space.space16,
  },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
  },
  flex: {
    flex: 1,
  },
  specialty: {
    gap: space.space4,
    paddingTop: space.space8,
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  actions: {
    gap: space.space8,
    paddingTop: space.space8,
  },
  link: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
});
