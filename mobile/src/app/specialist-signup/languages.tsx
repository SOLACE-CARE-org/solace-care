import { colors, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Info, Plus, X } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { ApiProblem } from '@/api/core';
import {
  SPECIALIST_STEPS,
  type SpecialistLanguage,
  stepRoute,
  useApplication,
  useSaveLanguages,
} from '@/api/specialist';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';

type Level = SpecialistLanguage['proficiency'];
type Choice = { code: string; proficiency: Level };

const LEVELS: Level[] = ['NATIVE', 'FLUENT', 'PROFESSIONAL'];

/** Specialist application, step 3 of 7: languages you counsel in (SpecLanguages.dc.html). */
export default function SpecialistLanguages() {
  const { t } = useTranslation();
  const appLanguage = i18n.language === 'fr' ? 'fr' : 'en';
  const application = useApplication();
  const catalogue = useSessionLanguages(appLanguage);
  const save = useSaveLanguages();
  const [adding, setAdding] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  // What the specialist changed here; until then, what Core has, or the app language to start.
  // PROFESSIONAL is the lowest bookable level; reviewers confirm every level in the interview.
  const [edited, setEdited] = useState<Choice[] | null>(null);
  const saved = application.data?.languages ?? [];
  const languages: Choice[] =
    edited ??
    (saved.length > 0
      ? saved.map((l) => ({ code: l.code, proficiency: l.proficiency }))
      : [{ code: appLanguage, proficiency: 'PROFESSIONAL' }]);

  const nameOf = (code: string) => catalogue.data?.find((l) => l.code === code)?.name ?? code;
  const remaining = (catalogue.data ?? []).filter((l) => !languages.some((c) => c.code === l.code));

  const submit = () => {
    if (languages.length === 0) return;
    setProblem(null);
    save.mutate(languages, {
      onSuccess: (next) => router.push(stepRoute(next)),
      onError: (e) =>
        setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
    });
  };

  return (
    <FlowScreen
      top={<ProgressSteps step={3} total={SPECIALIST_STEPS} />}
      footer={
        <Button
          label={t('common.continue')}
          disabled={languages.length === 0 || save.isPending}
          accessibilityState={{
            disabled: languages.length === 0 || save.isPending,
            busy: save.isPending,
          }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('specialistSignup.languages.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('specialistSignup.languages.subtitle')}
        </Text>
      </View>

      {languages.map((choice) => {
        const name = nameOf(choice.code);
        return (
          <View key={choice.code} style={styles.card}>
            <View style={styles.cardHead}>
              <Text variant="label" style={styles.name}>
                {name}
              </Text>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={t('specialistSignup.languages.remove', { language: name })}
                onPress={() => setEdited(languages.filter((c) => c.code !== choice.code))}
                style={styles.remove}
              >
                <X size={20} strokeWidth={1.8} color={colors.inkMuted} />
              </Pressable>
            </View>
            <Segmented
              label={t('specialistSignup.languages.levelLabel', { language: name })}
              options={LEVELS.map((level) => ({
                value: level,
                label: t(`specialistSignup.languages.${level}`),
              }))}
              value={choice.proficiency}
              onChange={(proficiency) =>
                setEdited(languages.map((c) => (c.code === choice.code ? { ...c, proficiency } : c)))
              }
            />
          </View>
        );
      })}

      {remaining.length > 0 ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('specialistSignup.languages.add')}
          onPress={() => setAdding(true)}
          style={styles.add}
        >
          <Plus size={18} strokeWidth={1.8} color={colors.brand} aria-hidden />
          <Text variant="label" color={colors.brand}>
            {t('specialistSignup.languages.add')}
          </Text>
        </Pressable>
      ) : null}

      <View style={styles.note}>
        <Info size={18} strokeWidth={1.8} color={colors.inkSoft} aria-hidden />
        <Text variant="meta" color={colors.inkSoft} style={styles.noteText}>
          {t('specialistSignup.languages.note')}
        </Text>
      </View>

      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}

      <Modal visible={adding} animationType="slide" onRequestClose={() => setAdding(false)}>
        <SafeAreaView style={styles.sheet} edges={['top', 'bottom']}>
          <ScrollView contentContainerStyle={styles.sheetContent}>
            <Text variant="titleMd" accessibilityRole="header">
              {t('specialistSignup.languages.add')}
            </Text>
            <View accessibilityRole="radiogroup" style={styles.options}>
              {remaining.map((l) => (
                <OptionCard
                  key={l.code}
                  kind="radio"
                  title={l.name}
                  selected={false}
                  onPress={() => {
                    setEdited([...languages, { code: l.code, proficiency: 'PROFESSIONAL' }]);
                    setAdding(false);
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
  intro: {
    gap: space.space6,
  },
  card: {
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  name: {
    fontSize: 16,
  },
  remove: {
    width: size.sizeTap,
    height: size.sizeTap,
    marginRight: -space.space10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  add: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space8,
    minHeight: size.sizeTap + 6,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.lineControl,
  },
  note: {
    flexDirection: 'row',
    gap: space.space10,
    padding: space.space14,
    borderRadius: radius.radius14,
    backgroundColor: colors.brandTint,
  },
  noteText: {
    flex: 1,
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
