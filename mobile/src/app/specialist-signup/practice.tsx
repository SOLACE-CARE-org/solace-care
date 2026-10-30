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
  type PracticeProfession,
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

const EMPTY: PracticeProfession = {
  profession: '',
  licenceNumber: '',
  licensingBody: '',
  specialties: [],
};

/**
 * Specialist application, step 2 of 7: your practice (SpecProfessional.dc.html). One or more
 * professions, each with its own licence and the specialties it allows (D62); the first is the
 * main one. The licence covers its specialties, so no other evidence is asked for here.
 */
export default function SpecialistPractice() {
  const { t } = useTranslation();
  const language = i18n.language === 'fr' ? 'fr' : 'en';
  const application = useApplication();
  const professions = useProfessions(language);
  const topics = useHelpTopics(language);
  const save = useSavePractice();
  const [picking, setPicking] = useState<number | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  // What the specialist changed here; until then, what Core has.
  const saved = application.data?.practice;
  const [edited, setEdited] = useState<PracticeProfession[] | null>(null);
  const [years, setYears] = useState<string | null>(null);
  const [areas, setAreas] = useState<string[] | null>(null);
  const [bio, setBio] = useState<string | null>(null);
  const rows = edited ?? (saved?.professions.length ? saved.professions : [EMPTY]);
  const shownYears = years ?? (saved ? String(saved.yearsInPractice) : '');
  const focusAreas = areas ?? saved?.focusAreas ?? [];
  const shownBio = bio ?? saved?.bio ?? '';

  const editRow = (i: number, patch: Partial<PracticeProfession>) =>
    setEdited(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const catalogueOf = (code: string) => professions.data?.find((p) => p.code === code);
  // Professions already saved can ask for a specialty the catalogue lacks.
  const savedCodes = new Set((application.data?.professions ?? []).map((p) => p.profession));

  const yearsNumber = /^\d{1,2}$/.test(shownYears) ? Number(shownYears) : null;
  const ready =
    rows.every(
      (r) =>
        r.profession !== '' &&
        r.licenceNumber.trim() !== '' &&
        r.licensingBody.trim() !== '' &&
        r.specialties.length > 0,
    ) &&
    yearsNumber !== null &&
    focusAreas.length > 0 &&
    shownBio.trim() !== '';

  const toggleArea = (code: string) =>
    setAreas(
      focusAreas.includes(code)
        ? focusAreas.filter((c) => c !== code)
        : focusAreas.length < MAX_AREAS
          ? [...focusAreas, code]
          : focusAreas,
    );

  const toggleSpecialty = (i: number, code: string) => {
    const current = rows[i].specialties;
    editRow(i, {
      specialties: current.includes(code) ? current.filter((c) => c !== code) : [...current, code],
    });
  };

  const submit = () => {
    if (!ready || yearsNumber === null) return;
    setProblem(null);
    save.mutate(
      {
        professions: rows.map((r) => ({
          profession: r.profession,
          licenceNumber: r.licenceNumber.trim(),
          licensingBody: r.licensingBody.trim(),
          specialties: r.specialties,
        })),
        yearsInPractice: yearsNumber,
        focusAreas,
        bio: shownBio.trim(),
      },
      {
        onSuccess: (next) => router.push(stepRoute(next)),
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  const taken = new Set(rows.map((r) => r.profession));
  const canAddRow = (professions.data ?? []).some((p) => !taken.has(p.code));

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

      {rows.map((row, i) => {
        const entry = catalogueOf(row.profession);
        const label = entry?.label ?? '';
        const heading =
          i === 0
            ? t('specialistSignup.practice.mainProfession')
            : t('specialistSignup.practice.otherProfession', { n: i + 1 });
        return (
          <View key={i} style={styles.block}>
            <View style={styles.blockHead}>
              <Text variant="label" accessibilityRole="header">
                {heading}
              </Text>
              {rows.length > 1 ? (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={t('specialistSignup.practice.removeProfession', {
                    profession: label || heading,
                  })}
                  onPress={() => setEdited(rows.filter((_, j) => j !== i))}
                  style={styles.link}
                >
                  <Text variant="label" color={colors.brand}>
                    {t('specialistSignup.practice.remove')}
                  </Text>
                </Pressable>
              ) : null}
            </View>

            <View style={styles.field}>
              <Text variant="label" color={colors.inkMuted} style={styles.label}>
                {t('specialistSignup.practice.professionLabel')}
              </Text>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`${t('specialistSignup.practice.professionLabel')}, ${label}`}
                onPress={() => setPicking(i)}
                style={styles.select}
              >
                <Text variant="body" style={styles.selectText}>
                  {label}
                </Text>
                <ChevronDown size={20} strokeWidth={1.8} color={colors.inkMuted} aria-hidden />
              </Pressable>
            </View>

            <TextField
              label={t('specialistSignup.practice.licenceLabel')}
              value={row.licenceNumber}
              onChangeText={(v) => editRow(i, { licenceNumber: v })}
              maxLength={60}
              autoCapitalize="characters"
            />
            <TextField
              label={t('specialistSignup.practice.bodyLabel')}
              value={row.licensingBody}
              onChangeText={(v) => editRow(i, { licensingBody: v })}
              maxLength={160}
            />

            {entry ? (
              <View style={styles.field}>
                <Text
                  variant="label"
                  color={colors.inkMuted}
                  style={styles.label}
                  nativeID={`specialties-${i}`}
                >
                  {t('specialistSignup.practice.specialtiesLabel')}
                </Text>
                <View style={styles.chips} accessibilityLabelledBy={`specialties-${i}`}>
                  {(entry.specialties ?? []).map((s) => (
                    <Chip
                      key={s.code}
                      label={s.label}
                      on={row.specialties.includes(s.code)}
                      onToggle={() => toggleSpecialty(i, s.code)}
                    />
                  ))}
                </View>
                {savedCodes.has(row.profession) ? (
                  <Pressable
                    accessibilityRole="link"
                    onPress={() =>
                      router.push({
                        pathname: '/specialist-practice/request',
                        params: { profession: row.profession },
                      })
                    }
                    style={styles.link}
                  >
                    <Text variant="label" color={colors.brand}>
                      {t('specialistSignup.practice.notListed')}
                    </Text>
                  </Pressable>
                ) : (
                  <Text variant="meta" color={colors.inkMuted}>
                    {t('specialistSignup.practice.notListedLater')}
                  </Text>
                )}
              </View>
            ) : null}
          </View>
        );
      })}

      {canAddRow ? (
        <Button
          variant="secondary"
          label={t('specialistSignup.practice.addProfession')}
          onPress={() => setEdited([...rows, EMPTY])}
        />
      ) : null}

      <TextField
        label={t('specialistSignup.practice.yearsLabel')}
        value={shownYears}
        onChangeText={(v) => setYears(v.replace(/\D/g, '').slice(0, 2))}
        keyboardType="number-pad"
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
        value={shownBio}
        onChangeText={setBio}
        multiline
        maxLength={600}
      />

      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}

      <Modal
        visible={picking !== null}
        animationType="slide"
        onRequestClose={() => setPicking(null)}
      >
        <SafeAreaView style={styles.sheet} edges={['top', 'bottom']}>
          <ScrollView contentContainerStyle={styles.sheetContent}>
            <Text variant="titleMd" accessibilityRole="header">
              {t('specialistSignup.practice.professionLabel')}
            </Text>
            <View accessibilityRole="radiogroup" style={styles.options}>
              {(professions.data ?? [])
                .filter(
                  (p) =>
                    picking === null || !taken.has(p.code) || rows[picking]?.profession === p.code,
                )
                .map((p) => (
                  <OptionCard
                    key={p.code}
                    kind="radio"
                    title={p.label}
                    selected={picking !== null && p.code === rows[picking]?.profession}
                    onPress={() => {
                      if (picking !== null && rows[picking].profession !== p.code) {
                        // Another profession allows other specialties.
                        editRow(picking, { profession: p.code, specialties: [] });
                      }
                      setPicking(null);
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
  block: {
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  blockHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  link: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
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
