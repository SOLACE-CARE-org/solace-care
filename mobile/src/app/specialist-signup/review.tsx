import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Check, ShieldEllipsis } from 'lucide-react-native';
import { type TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { type SpecialistApplication, useApplication } from '@/api/specialist';
import { flags } from '@/config/flags';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';

type StepState = 'done' | 'current' | 'todo';

/**
 * Application under review (SpecReview.dc.html). Staff book the onboarding interview (D25); its time
 * shows here once booked. "Pick an interview time" waits behind its flag.
 */
export default function SpecialistReview() {
  const { t } = useTranslation();
  const application = useApplication();
  const data = application.data;

  const steps = data ? timeline(data) : null;

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.badge} aria-hidden>
          <ShieldEllipsis size={30} strokeWidth={1.8} color={colors.warningInk} />
        </View>
        <View style={styles.intro}>
          <Text variant="titleMd" accessibilityRole="header">
            {t('specialistSignup.review.title')}
          </Text>
          <Text variant="body" color={colors.inkMuted}>
            {t('specialistSignup.review.subtitle')}
          </Text>
        </View>

        {data?.reviewNote ? (
          <Text
            variant="body"
            color={colors.ink}
            style={styles.note}
            accessibilityLiveRegion="polite"
          >
            {data.reviewNote}
          </Text>
        ) : null}

        {steps ? (
          <View accessibilityRole="list">
            <Step
              state="done"
              title={t('specialistSignup.review.submitted')}
              meta={data?.submittedAt ? when(data.submittedAt, t) : undefined}
            />
            <Step
              state={steps.check}
              title={t('specialistSignup.review.check')}
              meta={steps.check === 'current' ? t('specialistSignup.review.inProgress') : undefined}
            />
            <Step
              state={steps.interview}
              title={t('specialistSignup.review.interview')}
              meta={
                data?.interviewAt
                  ? when(data.interviewAt, t)
                  : t('specialistSignup.review.interviewMeta')
              }
            />
            <Step
              state={steps.live}
              title={t('specialistSignup.review.live')}
              meta={t('specialistSignup.review.liveMeta')}
              last
            />
          </View>
        ) : application.isError ? (
          <View style={styles.error}>
            <Text variant="body" color={colors.inkMuted} accessibilityLiveRegion="polite">
              {t('common.checkConnection')}
            </Text>
            <Button label={t('common.tryAgain')} onPress={() => application.refetch()} />
          </View>
        ) : null}
      </ScrollView>

      <View style={styles.footer}>
        {data?.status === 'DRAFT' ? (
          // Changes were requested: back to the steps, which keep what was saved.
          <Button
            label={t('common.continue')}
            onPress={() => router.push('/specialist-signup/practice')}
          />
        ) : null}
        {flags.specialistInterviewPicker ? (
          <Button label={t('specialistSignup.review.pickInterview')} onPress={() => undefined} />
        ) : null}
        <Pressable
          accessibilityRole="link"
          accessibilityLabel={t('specialistSignup.review.preview')}
          onPress={() => router.push('/(specialist)/today')}
          style={styles.preview}
        >
          <Text variant="label" color={colors.brand} style={styles.previewText}>
            {t('specialistSignup.review.preview')}
          </Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}

/** Where the application stands on the four steps SpecReview shows. */
function timeline(application: SpecialistApplication): {
  check: StepState;
  interview: StepState;
  live: StepState;
} {
  if (application.status === 'APPROVED') return { check: 'done', interview: 'done', live: 'done' };
  if (application.interviewAt) return { check: 'done', interview: 'current', live: 'todo' };
  return { check: 'current', interview: 'todo', live: 'todo' };
}

/** "Today, 16:52", or the date and time in the viewer's language and time zone. */
function when(iso: string, t: TFunction): string {
  const date = new Date(iso);
  const time = new Intl.DateTimeFormat(i18n.language, { hour: '2-digit', minute: '2-digit' }).format(
    date,
  );
  if (date.toDateString() === new Date().toDateString()) {
    return t('specialistSignup.review.today', { time });
  }
  return new Intl.DateTimeFormat(i18n.language, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

function Step({
  state,
  title,
  meta,
  last = false,
}: {
  state: StepState;
  title: string;
  meta?: string;
  last?: boolean;
}) {
  return (
    <View style={styles.step} accessibilityRole="text" accessibilityLabel={meta ? `${title}. ${meta}` : title}>
      <View style={styles.rail} aria-hidden>
        <View
          style={[
            styles.dot,
            state === 'done' && styles.dotDone,
            state === 'current' && styles.dotCurrent,
          ]}
        >
          {state === 'done' ? <Check size={14} strokeWidth={3} color={colors.onBrand} /> : null}
        </View>
        {last ? null : <View style={[styles.line, state === 'done' && styles.lineDone]} />}
      </View>
      <View style={styles.stepText}>
        <Text variant="label" style={styles.stepTitle}>
          {title}
        </Text>
        {meta ? (
          <Text
            variant="meta"
            color={state === 'current' ? colors.brand : colors.inkMuted}
            style={state === 'current' && styles.metaCurrent}
          >
            {meta}
          </Text>
        ) : null}
      </View>
    </View>
  );
}

const DOT = 26;

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space24,
    gap: space.space20,
  },
  badge: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.warningTint,
  },
  intro: {
    gap: space.space8,
  },
  note: {
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  step: {
    flexDirection: 'row',
    gap: space.space14,
  },
  rail: {
    alignItems: 'center',
  },
  dot: {
    width: DOT,
    height: DOT,
    borderRadius: DOT / 2,
    borderWidth: 2,
    borderColor: colors.inkMuted,
    backgroundColor: colors.surfaceCard,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dotDone: {
    borderColor: colors.brand,
    backgroundColor: colors.brand,
  },
  dotCurrent: {
    borderColor: colors.brand,
    backgroundColor: colors.brandTint,
  },
  line: {
    flex: 1,
    width: 2,
    minHeight: space.space24,
    backgroundColor: colors.lineControl,
  },
  lineDone: {
    backgroundColor: colors.brand,
  },
  stepText: {
    flex: 1,
    gap: space.space2,
    paddingBottom: space.space18,
  },
  stepTitle: {
    fontSize: 15,
  },
  metaCurrent: {
    fontFamily: fontFamily.body[600],
  },
  error: {
    gap: space.space12,
  },
  footer: {
    gap: space.space8,
    paddingHorizontal: space.space24,
    paddingBottom: space.space16,
  },
  preview: {
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  previewText: {
    textDecorationLine: 'underline',
  },
});
