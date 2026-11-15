import { colors, radius, space } from '@solace/tokens/native';
import { Lock } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useAnswerContextQuestion, useContextAnswers, useContextQuestions } from '@/api/tracking';
import i18n from '@/i18n';
import { ContextQuestionField } from '@/tracking/ContextQuestionField';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/**
 * About you (Tracking Service Spec, App; D61): the published profile-scoped context questions,
 * answered with chips. Every answer is optional, can be cleared or changed at any time, and can
 * only switch a pattern check off. Layout follows Privacy & data (title row) and the health check
 * preferences (questions as chip groups).
 */
export default function AboutYou() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const questions = useContextQuestions();
  const answers = useContextAnswers();
  const answer = useAnswerContextQuestion();
  const profile = (questions.data ?? []).filter((q) => q.scope === 'PROFILE');

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.titleRow}>
          <BackButton />
          <Text variant="titleMd" accessibilityRole="header">
            {t('track.about.title')}
          </Text>
        </View>
        <Text variant="body" color={colors.inkMuted}>
          {t('track.about.intro')}
        </Text>

        {questions.isError || answers.isError ? (
          <View style={styles.card}>
            <Text variant="body">{t('common.checkConnection')}</Text>
            <Button
              variant="secondary"
              label={t('common.tryAgain')}
              onPress={() => {
                void questions.refetch();
                void answers.refetch();
              }}
            />
          </View>
        ) : questions.isPending || answers.isPending ? (
          <Text variant="meta" color={colors.inkMuted}>
            {t('common.loading')}
          </Text>
        ) : profile.length === 0 ? (
          <View style={styles.card}>
            <Text variant="label">{t('track.about.noneTitle')}</Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('track.about.none')}
            </Text>
          </View>
        ) : (
          <View style={styles.card}>
            {profile.map((q, i) => (
              <View key={q.code} style={[styles.row, i > 0 && styles.divider]}>
                <ContextQuestionField
                  question={q}
                  lang={lang}
                  value={answers.data?.[q.code]}
                  disabled={answer.isPending}
                  onChange={(value) => answer.mutate({ code: q.code, value })}
                />
              </View>
            ))}
          </View>
        )}

        <View accessibilityLiveRegion="polite">
          {answer.isError ? (
            <Text variant="meta" color={colors.danger} accessibilityRole="alert">
              {t('track.about.notSaved')}
            </Text>
          ) : answer.isSuccess ? (
            <Text variant="meta" color={colors.brand}>
              {t('track.about.saved')}
            </Text>
          ) : null}
        </View>

        <View style={styles.privacy}>
          <Lock size={18} strokeWidth={1.8} color={colors.inkMuted} aria-hidden />
          <Text variant="meta" color={colors.inkMuted} style={styles.privacyText}>
            {t('track.about.privacy')}
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  content: { padding: space.space20, gap: space.space16 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: space.space4 },
  card: {
    gap: space.space12,
    paddingHorizontal: space.space16,
    paddingVertical: space.space4,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  row: { paddingVertical: space.space14 },
  divider: { borderTopWidth: 1, borderTopColor: colors.line },
  privacy: { flexDirection: 'row', gap: space.space8, alignItems: 'flex-start' },
  privacyText: { flex: 1 },
});
