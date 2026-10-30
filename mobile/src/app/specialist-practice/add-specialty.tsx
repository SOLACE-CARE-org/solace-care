import { colors, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { ApiProblem } from '@/api/core';
import { type PickedFile, useAddSpecialty, useApplication, useProfessions } from '@/api/specialist';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { FileRow } from '@/ui/FileRow';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/**
 * Add a specialty under one of the specialist's professions (D62). They say what training or
 * experience they have in it, and can attach a certificate. One person at Solace checks it before it
 * shows on the profile.
 */
export default function AddSpecialty() {
  const { t } = useTranslation();
  const { profession } = useLocalSearchParams<{ profession: string }>();
  const language = i18n.language === 'fr' ? 'fr' : 'en';
  const application = useApplication();
  const professions = useProfessions(language);
  const add = useAddSpecialty();
  const [specialty, setSpecialty] = useState('');
  const [note, setNote] = useState('');
  const [certificate, setCertificate] = useState<PickedFile | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const mine = application.data?.professions?.find((p) => p.profession === profession);
  const claimed = new Set(
    (mine?.specialties ?? [])
      .filter((s) => s.status === 'APPROVED' || s.status === 'PENDING')
      .map((s) => s.specialty),
  );
  const choices = (professions.data?.find((p) => p.code === profession)?.specialties ?? []).filter(
    (s) => !claimed.has(s.code),
  );
  const ready = specialty !== '' && note.trim() !== '';

  const submit = () => {
    if (!ready || !profession) return;
    setProblem(null);
    add.mutate(
      { profession, specialty, note: note.trim(), certificate: certificate ?? undefined },
      {
        onSuccess: () => router.back(),
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <Button
          label={t('specialistPractice.sendForReview')}
          disabled={!ready || add.isPending}
          accessibilityState={{ disabled: !ready || add.isPending, busy: add.isPending }}
          onPress={submit}
        />
      }
    >
      <Text variant="titleMd" accessibilityRole="header">
        {t('specialistPractice.addSpecialtyTitle', { profession: mine?.label ?? '' })}
      </Text>

      <View accessibilityRole="radiogroup" style={styles.options}>
        {choices.map((s) => (
          <OptionCard
            key={s.code}
            kind="radio"
            title={s.label}
            selected={s.code === specialty}
            onPress={() => setSpecialty(s.code)}
          />
        ))}
      </View>
      <Pressable
        accessibilityRole="link"
        onPress={() =>
          router.replace({ pathname: '/specialist-practice/request', params: { profession } })
        }
        style={styles.link}
      >
        <Text variant="label" color={colors.brand}>
          {t('specialistSignup.practice.notListed')}
        </Text>
      </Pressable>

      <TextField
        label={t('specialistPractice.noteLabel')}
        help={t('specialistPractice.noteHelp')}
        value={note}
        onChangeText={setNote}
        multiline
        maxLength={1000}
      />
      <FileRow
        title={t('specialistPractice.certificate')}
        file={certificate}
        required={false}
        onPick={setCertificate}
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
  options: {
    gap: space.space10,
  },
  link: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
});
