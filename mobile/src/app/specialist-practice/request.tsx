import { colors } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiProblem } from '@/api/core';
import { type PickedFile, useApplication, useRequestSpecialty } from '@/api/specialist';
import { Button } from '@/ui/Button';
import { FileRow } from '@/ui/FileRow';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/**
 * Ask for a specialty that is not listed yet, under one of the specialist's professions (D62).
 * Solace's team adds it to the list, matches it to one that exists, or declines it with a reason.
 * A request never holds up the application.
 */
export default function RequestSpecialty() {
  const { t } = useTranslation();
  const { profession } = useLocalSearchParams<{ profession: string }>();
  const application = useApplication();
  const request = useRequestSpecialty();
  const [name, setName] = useState('');
  const [note, setNote] = useState('');
  const [certificate, setCertificate] = useState<PickedFile | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const label = application.data?.professions?.find((p) => p.profession === profession)?.label;
  const ready = name.trim() !== '' && note.trim() !== '';

  const submit = () => {
    if (!ready || !profession) return;
    setProblem(null);
    request.mutate(
      {
        profession,
        name: name.trim(),
        note: note.trim(),
        certificate: certificate ?? undefined,
      },
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
          label={t('specialistPractice.sendRequest')}
          disabled={!ready || request.isPending}
          accessibilityState={{ disabled: !ready || request.isPending, busy: request.isPending }}
          onPress={submit}
        />
      }
    >
      <Text variant="titleMd" accessibilityRole="header">
        {t('specialistPractice.requestTitle')}
      </Text>
      <Text variant="body" color={colors.inkMuted}>
        {t('specialistPractice.requestIntro', { profession: label ?? '' })}
      </Text>
      <TextField
        label={t('specialistPractice.requestName')}
        value={name}
        onChangeText={setName}
        maxLength={80}
      />
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
