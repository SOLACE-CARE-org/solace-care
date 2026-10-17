import { colors, fontFamily, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { type ReactNode, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import {
  type Preferences,
  useHelpTopics,
  usePreferences,
  useSavePreferences,
  useSessionLanguages,
} from '@/api/account';
import i18n from '@/i18n';
import { type AppLanguage, useSession } from '@/state/session';
import { SIGNUP_STEPS } from '@/state/signup';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { FlowScreen } from '@/ui/FlowScreen';
import { OptionCard } from '@/ui/OptionCard';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

type Gender = Preferences['specialistGender'];

/** Seeker sign-up, step 2: Make it yours (SeekerPreferences.dc.html). */
export default function SignupPreferences() {
  const { t } = useTranslation();
  const language = i18n.language === 'fr' ? 'fr' : 'en';
  const saved = usePreferences();
  const languages = useSessionLanguages(language);
  const topics = useHelpTopics(language);
  const save = useSavePreferences();
  const chooseLanguage = useSession((s) => s.chooseLanguage);

  // What the seeker changed here; until then, what Core has (the app language as the session
  // language until the seeker chooses).
  const [edits, setEdits] = useState<
    Partial<Pick<Preferences, 'sessionLanguages' | 'helpTopics' | 'specialistGender'>>
  >({});
  const sessionLanguages = edits.sessionLanguages ?? saved.data?.sessionLanguages ?? [];
  const helpTopics = edits.helpTopics ?? saved.data?.helpTopics ?? [];
  const gender: Gender = edits.specialistGender ?? saved.data?.specialistGender ?? 'ANY';
  const setSessionLanguages = (next: (list: string[]) => string[]) =>
    setEdits((e) => ({ ...e, sessionLanguages: next(e.sessionLanguages ?? sessionLanguages) }));
  const setHelpTopics = (next: (list: string[]) => string[]) =>
    setEdits((e) => ({ ...e, helpTopics: next(e.helpTopics ?? helpTopics) }));
  const setGender = (next: Gender) => setEdits((e) => ({ ...e, specialistGender: next }));

  const switchApp = (next: AppLanguage) => {
    chooseLanguage(next);
    i18n.changeLanguage(next);
  };
  const toggle = (list: string[], code: string) =>
    list.includes(code) ? list.filter((c) => c !== code) : [...list, code];

  const ready = sessionLanguages.length > 0 && !save.isPending;
  const submit = () =>
    save.mutate(
      { appLanguage: language, sessionLanguages, helpTopics, specialistGender: gender },
      { onSuccess: () => router.push('/signup/privacy') },
    );

  return (
    <FlowScreen
      top={<ProgressSteps step={2} total={SIGNUP_STEPS} />}
      footer={
        <Button
          label={t('common.continue')}
          disabled={!ready}
          accessibilityState={{ disabled: !ready, busy: save.isPending }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('signupPreferences.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('signupPreferences.subtitle')}
        </Text>
      </View>

      <Group legend={t('signupPreferences.appLanguage')} role="radiogroup">
        <OptionCard
          kind="radio"
          title={t('common.english')}
          selected={language === 'en'}
          onPress={() => switchApp('en')}
        />
        <OptionCard
          kind="radio"
          title={t('common.french')}
          selected={language === 'fr'}
          onPress={() => switchApp('fr')}
        />
      </Group>

      <Group legend={t('signupPreferences.sessionLanguages')}>
        {(languages.data ?? []).map((l) => (
          <OptionCard
            key={l.code}
            kind="checkbox"
            title={l.name}
            selected={sessionLanguages.includes(l.code)}
            onPress={() => setSessionLanguages((list) => toggle(list, l.code))}
          />
        ))}
      </Group>

      <View style={styles.group} accessibilityRole="none">
        <Text variant="label" style={styles.legend}>
          {t('signupPreferences.helpTopics')}{' '}
          <Text variant="label" color={colors.inkMuted} style={styles.optional}>
            {t('signupPreferences.optional')}
          </Text>
        </Text>
        <View style={styles.chips} accessibilityLabel={t('signupPreferences.helpTopics')}>
          {(topics.data ?? []).map((topic) => (
            <Chip
              key={topic.code}
              label={topic.label}
              on={helpTopics.includes(topic.code)}
              onToggle={() => setHelpTopics((list) => toggle(list, topic.code))}
            />
          ))}
        </View>
      </View>

      <Group legend={t('signupPreferences.specialistGender')} role="radiogroup" columns={3}>
        {(['ANY', 'WOMAN', 'MAN'] as const).map((g) => (
          <OptionCard
            key={g}
            kind="radio"
            title={t(`signupPreferences.${g === 'ANY' ? 'any' : g === 'WOMAN' ? 'woman' : 'man'}`)}
            selected={gender === g}
            onPress={() => setGender(g)}
          />
        ))}
      </Group>

      {languages.isError || topics.isError || save.isError ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {t('common.checkConnection')}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

/** A labelled set of options laid out two (or three) to a row, as on the screen. */
function Group({
  legend,
  role,
  columns = 2,
  children,
}: {
  legend: string;
  role?: 'radiogroup';
  columns?: number;
  children: ReactNode;
}) {
  return (
    <View style={styles.group}>
      <Text variant="label" style={styles.legend}>
        {legend}
      </Text>
      <View
        style={[styles.row, columns === 3 && styles.rowTight]}
        accessibilityRole={role}
        accessibilityLabel={legend}
      >
        {children}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space6,
  },
  group: {
    gap: space.space10,
  },
  legend: {
    fontSize: 15,
  },
  optional: {
    fontFamily: fontFamily.body[400],
  },
  row: {
    flexDirection: 'row',
    gap: space.space10,
  },
  rowTight: {
    gap: space.space8,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space8,
  },
});
