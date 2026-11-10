import { colors, radius, space } from '@solace/tokens/native';
import { useTranslation } from 'react-i18next';
import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  useAnswerContextQuestion,
  useContextAnswers,
  useContextQuestions,
} from '@/api/tracking';
import i18n from '@/i18n';
import { ContextQuestionField } from '@/tracking/ContextQuestionField';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/**
 * About you for tracking (Tracking Service Spec, App): the published profile-scoped context
 * questions. Every answer can be skipped or changed, and an answer can only switch a pattern
 * check off. No screen design exists yet, so the layout follows the catalogue and the copy is
 * provisional.
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
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('track.about.title')}
        </Text>
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
          <Text variant="body" color={colors.inkMuted}>
            {t('track.about.none')}
          </Text>
        ) : (
          profile.map((q) => (
            <View key={q.code} style={styles.card}>
              <ContextQuestionField
                question={q}
                lang={lang}
                value={answers.data?.[q.code]}
                disabled={answer.isPending}
                onChange={(value) => answer.mutate({ code: q.code, value })}
              />
            </View>
          ))
        )}
        {answer.isError ? (
          <Text variant="meta" color={colors.danger} accessibilityRole="alert">
            {t('common.checkConnection')}
          </Text>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  content: { padding: space.space20, gap: space.space16 },
  card: {
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
});
