import { colors, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { ChevronDown } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useHelpTopics } from '@/api/account';
import { ApiProblem } from '@/api/core';
import {
  type Practice,
  SPECIALIST_STEPS,
  stepRoute,
  useApplication,
  useProfessions,
  useSavePractice,
} from '@/api/specialist';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/** SpecProfessional: "Areas you work with (up to 5)". */
const MAX_AREAS = 5;

/** Specialist application, step 2 of 7: your practice (SpecProfessional.dc.html). */
export default function SpecialistPractice() {
  const { t } = useTranslation();
  const language = i18n.language === 'fr' ? 'fr' : 'en';
  const application = useApplication();
  const professions = useProfessions(language);
  const topics = useHelpTopics(language);
  const save = useSavePractice();
  const [picking, setPicking] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  // What the specialist changed here; until then, what Core has.
  const [edits, setEdits] = useState<Partial<Practice & { years: string }>>({});
  const saved = application.data?.practice;
  const profession = edits.profession ?? saved?.profession ?? '';
  const licenceNumber = edits.licenceNumber ?? saved?.licenceNumber ?? '';
  const years = edits.years ?? (saved ? String(saved.yearsInPractice) : '');
  const licensingBody = edits.licensingBody ?? saved?.licensingBody ?? '';
  const focusAreas = edits.focusAreas ?? saved?.focusAreas ?? [];
  const bio = edits.bio ?? saved?.bio ?? '';
  const edit = (patch: Partial<Practice & { years: string }>) =>
    setEdits((e) => ({ ...e, ...patch }));

  const yearsNumber = /^\d{1,2}$/.test(years) ? Number(years) : null;
  const ready =
    profession !== '' &&
    licenceNumber.trim() !== '' &&
    yearsNumber !== null &&
    licensingBody.trim() !== '' &&
    focusAreas.length > 0 &&
    bio.trim() !== '';

  const toggleArea = (code: string) =>
    edit({
      focusAreas: focusAreas.includes(code)
        ? focusAreas.filter((c) => c !== code)
        : focusAreas.length < MAX_AREAS
          ? [...focusAreas, code]
          : focusAreas,
    });

  const submit = () => {
    if (!ready || yearsNumber === null) return;
    setProblem(null);
    save.mutate(
      {
        profession,
        licenceNumber: licenceNumber.trim(),
        yearsInPractice: yearsNumber,
        licensingBody: licensingBody.trim(),
        focusAreas,
        bio: bio.trim(),
      },
      {
        onSuccess: (next) => router.push(stepRoute(next)),
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  const professionLabel = professions.data?.find((p) => p.code === profession)?.label ?? '';

  return (
    <FlowScreen
      top={<ProgressSteps step={2} total={SPECIALIST_STEPS} />}
      footer={
        <Button
          label={t('common.continue')}
          disabled={!ready || save.isPending}
          accessibilityState={{ disabled: !ready || save.isPending, busy: save.isPending }}
          onPress={submit}
        />
      }
    >
      <Text variant="titleMd" accessibilityRole="header">
        {t('specialistSignup.practice.title')}
      </Text>

      <View style={styles.field}>
        <Text variant="label" color={colors.inkMuted} style={styles.label}>
          {t('specialistSignup.practice.professionLabel')}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`${t('specialistSignup.practice.professionLabel')}, ${professionLabel}`}
          onPress={() => setPicking(true)}
          style={styles.select}
        >
          <Text variant="body" style={styles.selectText}>
            {professionLabel}
          </Text>
          <ChevronDown size={20} strokeWidth={1.8} color={colors.inkMuted} aria-hidden />
        </Pressable>
      </View>

      <View style={styles.row}>
        <View style={styles.half}>
          <TextField
            label={t('specialistSignup.practice.licenceLabel')}
            value={licenceNumber}
            onChangeText={(v) => edit({ licenceNumber: v })}
            maxLength={60}
            autoCapitalize="characters"
          />
        </View>
        <View style={styles.half}>
          <TextField
            label={t('specialistSignup.practice.yearsLabel')}
            value={years}
            onChangeText={(v) => edit({ years: v.replace(/\D/g, '').slice(0, 2) })}
            keyboardType="number-pad"
          />
        </View>
      </View>

      <TextField
        label={t('specialistSignup.practice.bodyLabel')}
        value={licensingBody}
        onChangeText={(v) => edit({ licensingBody: v })}
        maxLength={160}
      />

      <View style={styles.field}>
        <Text variant="label" color={colors.inkMuted} style={styles.label} nativeID="areas-label">
          {t('specialistSignup.practice.areasLabel')}
        </Text>
        <View style={styles.chips} accessibilityLabelledBy="areas-label">
          {(topics.data ?? []).map((topic) => (
            <Chip
              key={topic.code}
              label={topic.label}
              on={focusAreas.includes(topic.code)}
              onToggle={() => toggleArea(topic.code)}
            />
          ))}
        </View>
      </View>

      <TextField
        label={t('specialistSignup.practice.bioLabel')}
        help={t('specialistSignup.practice.bioHelp')}
        value={bio}
        onChangeText={(v) => edit({ bio: v })}
        multiline
        maxLength={600}
      />

      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}

      <Modal visible={picking} animationType="slide" onRequestClose={() => setPicking(false)}>
        <SafeAreaView style={styles.sheet} edges={['top', 'bottom']}>
          <ScrollView contentContainerStyle={styles.sheetContent}>
            <Text variant="titleMd" accessibilityRole="header">
              {t('specialistSignup.practice.professionLabel')}
            </Text>
            <View accessibilityRole="radiogroup" style={styles.options}>
              {(professions.data ?? []).map((p) => (
                <OptionCard
                  key={p.code}
                  kind="radio"
                  title={p.label}
                  selected={p.code === profession}
                  onPress={() => {
                    edit({ profession: p.code });
                    setPicking(false);
                  }}
                />
              ))}
            </View>
          </ScrollView>
        </SafeAreaView>
      </Modal>
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  field: {
    gap: space.space6,
  },
  label: {
    fontSize: 13,
  },
  select: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: size.sizeFieldMobile,
    paddingHorizontal: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  selectText: {
    flex: 1,
    fontSize: 16,
  },
  row: {
    flexDirection: 'row',
    gap: space.space10,
  },
  half: {
    flex: 1,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space8,
  },
  sheet: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  sheetContent: {
    padding: space.space20,
    gap: space.space18,
  },
  options: {
    gap: space.space10,
  },
});
