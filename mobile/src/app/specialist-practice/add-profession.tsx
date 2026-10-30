import { colors, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { ApiProblem } from '@/api/core';
import {
  type PickedFile,
  useAddProfession,
  useApplication,
  useProfessions,
} from '@/api/specialist';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { FileRow } from '@/ui/FileRow';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/**
 * Add a profession after approval (D62): its licence number, issuing body, the specialties it
 * allows that the specialist offers, and the licence itself. Two people at Solace approve it; the
 * specialist keeps working under what is already approved meanwhile.
 */
export default function AddProfession() {
  const { t } = useTranslation();
  const language = i18n.language === 'fr' ? 'fr' : 'en';
  const application = useApplication();
  const professions = useProfessions(language);
  const add = useAddProfession();
  const [profession, setProfession] = useState('');
  const [licenceNumber, setLicenceNumber] = useState('');
  const [licensingBody, setLicensingBody] = useState('');
  const [specialties, setSpecialties] = useState<string[]>([]);
  const [licence, setLicence] = useState<PickedFile | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  // A profession already approved or waiting cannot be added again.
  const claimed = new Set(
    (application.data?.professions ?? [])
      .filter((p) => p.status === 'APPROVED' || p.status === 'PENDING')
      .map((p) => p.profession),
  );
  const choices = (professions.data ?? []).filter((p) => !claimed.has(p.code));
  const chosen = choices.find((p) => p.code === profession);
  const ready =
    !!chosen &&
    licenceNumber.trim() !== '' &&
    licensingBody.trim() !== '' &&
    specialties.length > 0 &&
    licence !== null;

  const submit = () => {
    if (!ready || !licence) return;
    setProblem(null);
    add.mutate(
      {
        profession,
        licenceNumber: licenceNumber.trim(),
        licensingBody: licensingBody.trim(),
        specialties,
        licence,
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
          label={t('specialistPractice.sendForReview')}
          disabled={!ready || add.isPending}
          accessibilityState={{ disabled: !ready || add.isPending, busy: add.isPending }}
          onPress={submit}
        />
      }
    >
      <Text variant="titleMd" accessibilityRole="header">
        {t('specialistPractice.addProfession')}
      </Text>
      <Text variant="body" color={colors.inkMuted}>
        {t('specialistPractice.addProfessionIntro')}
      </Text>

      <View accessibilityRole="radiogroup" style={styles.options}>
        {choices.map((p) => (
          <OptionCard
            key={p.code}
            kind="radio"
            title={p.label}
            selected={p.code === profession}
            onPress={() => {
              if (p.code !== profession) setSpecialties([]);
              setProfession(p.code);
            }}
          />
        ))}
      </View>
      {choices.length === 0 && professions.data ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('specialistPractice.noMoreProfessions')}
        </Text>
      ) : null}

      {chosen ? (
        <>
          <TextField
            label={t('specialistSignup.practice.licenceLabel')}
            value={licenceNumber}
            onChangeText={setLicenceNumber}
            maxLength={60}
            autoCapitalize="characters"
          />
          <TextField
            label={t('specialistSignup.practice.bodyLabel')}
            value={licensingBody}
            onChangeText={setLicensingBody}
            maxLength={160}
          />
          <View style={styles.field}>
            <Text variant="label" color={colors.inkMuted} nativeID="new-specialties">
              {t('specialistSignup.practice.specialtiesLabel')}
            </Text>
            <View style={styles.chips} accessibilityLabelledBy="new-specialties">
              {(chosen.specialties ?? []).map((s) => (
                <Chip
                  key={s.code}
                  label={s.label}
                  on={specialties.includes(s.code)}
                  onToggle={() =>
                    setSpecialties(
                      specialties.includes(s.code)
                        ? specialties.filter((c) => c !== s.code)
                        : [...specialties, s.code],
                    )
                  }
                />
              ))}
            </View>
          </View>
          <FileRow
            title={t('specialistSignup.documents.LICENCE')}
            file={licence}
            required
            onPick={setLicence}
          />
        </>
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
  options: {
    gap: space.space10,
  },
  field: {
    gap: space.space6,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space8,
  },
});
