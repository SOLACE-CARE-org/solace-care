import { colors, radius, space } from '@solace/tokens/native';
import Constants from 'expo-constants';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Image, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiProblem } from '@/api/core';
import { type CatalogueModule, imageUrl, useEnrol, useModules } from '@/api/tracking';
import i18n from '@/i18n';
import { moduleDescription, moduleName } from '@/tracking/view';
import { Button } from '@/ui/Button';
import { OptionCard } from '@/ui/OptionCard';
import { Pill } from '@/ui/Pill';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

import { accent } from '../(seeker)/track';

/** "1.2.0" ≥ "1.10.0"? Numeric, part by part. */
export function supports(appVersion: string, needed: string): boolean {
  const a = appVersion.split('.').map(Number);
  const b = needed.split('.').map(Number);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] ?? 0;
    const y = b[i] ?? 0;
    if (x !== y) return x > y;
  }
  return true;
}

/**
 * Add a module (ModuleCatalog.dc.html): every published module, with what the person already
 * tracks. A module's enrolment question can only switch rules off; it is asked once, here.
 */
export default function Catalogue() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const modules = useModules(lang);
  const appVersion = Constants.expoConfig?.version ?? '1.0.0';

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('track.catalogue.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('track.catalogue.intro')}
        </Text>
        <Text variant="titleSm" accessibilityRole="header">
          {t('track.catalogue.section')}
        </Text>
        {modules.isError ? (
          <View style={styles.card}>
            <Text variant="body">{t('common.checkConnection')}</Text>
            <Button
              variant="secondary"
              label={t('common.tryAgain')}
              onPress={() => void modules.refetch()}
            />
          </View>
        ) : modules.isPending ? (
          <Text variant="meta" color={colors.inkMuted}>
            {t('common.loading')}
          </Text>
        ) : (
          (modules.data ?? []).map((m) => (
            <ModuleRow
              key={m.code}
              module={m}
              lang={lang}
              supported={supports(appVersion, m.minAppVersion)}
            />
          ))
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

export function ModuleRow({
  module: m,
  lang,
  supported,
}: {
  module: CatalogueModule;
  lang: 'en' | 'fr';
  supported: boolean;
}) {
  const { t } = useTranslation();
  const enrol = useEnrol();
  const [open, setOpen] = useState(false);
  const [answer, setAnswer] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const name = moduleName(m, lang);
  const question = m.enrolmentQuestion;
  const options =
    question?.kind === 'YES_NO'
      ? [
          { code: 'yes', label: t('track.enrol.yes') },
          { code: 'no', label: t('track.enrol.no') },
        ]
      : (question?.options ?? []).map((o) => ({
          code: o.code ?? '',
          label: (lang === 'fr' ? o.fr : o.en) ?? '',
        }));

  const add = () =>
    enrol.mutate(
      {
        code: m.code,
        ...(answer ? { answer } : {}),
        ...(m.reminderDefault ? { reminderTime: m.reminderDefault.slice(0, 5) } : {}),
      },
      {
        onSuccess: () => setOpen(false),
        onError: (e) =>
          setProblem(
            e instanceof ApiProblem && e.code === 'tracking.not-eligible' && m.minAge
              ? t('track.catalogue.tooYoung', { age: m.minAge })
              : e instanceof ApiProblem && e.title
                ? e.title
                : t('common.checkConnection'),
          ),
      },
    );

  return (
    <View style={[styles.card, { borderTopColor: accent(m.accentColour) }, styles.accent]}>
      {m.imageId ? (
        <Image
          source={{ uri: imageUrl(m.imageId) }}
          style={styles.image}
          accessibilityLabel={(lang === 'fr' ? m.imageAlt.fr : m.imageAlt.en) || name}
        />
      ) : null}
      <View style={styles.row}>
        <View style={styles.flex}>
          <Text variant="titleSm">{name}</Text>
          <Text variant="meta" color={colors.inkMuted}>
            {moduleDescription(m, lang)}
          </Text>
        </View>
        {m.enrolled ? (
          <Pill label={t('track.catalogue.tracking')} tone="brand" />
        ) : supported && !open ? (
          <Button
            variant="secondary"
            label={t('track.catalogue.add')}
            accessibilityLabel={t('track.enrol.add', { module: name })}
            onPress={() => (question ? setOpen(true) : add())}
            disabled={enrol.isPending}
          />
        ) : null}
      </View>
      {!supported ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('track.catalogue.update')}
        </Text>
      ) : null}
      {open && question ? (
        <View style={styles.question} accessibilityRole="radiogroup">
          <Text variant="label">
            {lang === 'fr' ? question.question?.fr : question.question?.en}
          </Text>
          {options.map((o) => (
            <OptionCard
              key={o.code}
              kind="radio"
              title={o.label}
              selected={answer === o.code}
              onPress={() => setAnswer(o.code)}
            />
          ))}
          <Button
            label={t('track.enrol.add', { module: name })}
            disabled={!answer || enrol.isPending}
            accessibilityState={{ disabled: !answer || enrol.isPending, busy: enrol.isPending }}
            onPress={add}
          />
        </View>
      ) : null}
      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  content: { padding: space.space20, gap: space.space16 },
  flex: { flex: 1 },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.space12 },
  card: {
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
    overflow: 'hidden',
  },
  accent: { borderTopWidth: 4 },
  image: { width: '100%', aspectRatio: 3 / 2, borderRadius: radius.radius12 },
  question: { gap: space.space8 },
});
