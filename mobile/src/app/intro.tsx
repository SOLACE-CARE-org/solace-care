import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { ChartLine, MessageSquare, Smartphone, Video } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Button } from '@/ui/Button';
import { CrisisButton } from '@/ui/CrisisButton';
import { Text } from '@/ui/Text';

const STEPS = 3;

/**
 * The three intro screens after the language choice (Onboard1–3.dc.html): talk to a specialist,
 * book without an account, track privately. Urgent help stays one tap away; Skip goes to Welcome.
 */
export default function Intro() {
  const { t } = useTranslation();
  const [step, setStep] = useState(1);
  const done = () => router.replace('/welcome');

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <View style={styles.flex}>
          <CrisisButton label={t('crisis.button')} onPress={() => router.push('/crisis')} />
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('intro.skip')}
          onPress={done}
          style={styles.skip}
        >
          <Text variant="label" color={colors.brand} style={styles.bold}>
            {t('intro.skip')}
          </Text>
        </Pressable>
      </View>

      <View style={styles.art} aria-hidden>
        {step === 1 ? (
          <>
            <Video size={48} strokeWidth={1.8} color={colors.brand} />
            <View style={styles.chips}>
              <Chip label={t('common.english')} />
              <Chip label={t('common.french')} />
            </View>
          </>
        ) : step === 2 ? (
          <>
            <Smartphone size={48} strokeWidth={1.8} color={colors.brand} />
            <View style={styles.chips}>
              <Chip label={t('intro.momo')} />
              <Chip label={t('intro.orange')} />
              <Chip label={t('intro.sms')} icon />
            </View>
          </>
        ) : (
          <ChartLine size={48} strokeWidth={1.8} color={colors.brand} />
        )}
      </View>

      <View style={styles.text}>
        <Text variant="titleLg" accessibilityRole="header">
          {t(`intro.title${step}`)}
        </Text>
        <Text variant="body" color={colors.inkSoft}>
          {t(`intro.body${step}`)}
        </Text>
      </View>

      <View
        style={styles.dots}
        accessibilityRole="progressbar"
        accessibilityLabel={t('signup.progressLabel', { step, total: STEPS })}
        accessibilityValue={{ min: 1, max: STEPS, now: step }}
      >
        {Array.from({ length: STEPS }, (_, i) => (
          <View key={i} style={[styles.dot, i + 1 === step && styles.dotOn]} />
        ))}
      </View>

      <Button
        label={step < STEPS ? t('intro.next') : t('intro.start')}
        onboarding
        onPress={() => (step < STEPS ? setStep(step + 1) : done())}
      />
    </SafeAreaView>
  );
}

function Chip({ label, icon = false }: { label: string; icon?: boolean }) {
  return (
    <View style={styles.chip}>
      {icon ? <MessageSquare size={14} strokeWidth={1.8} color={colors.brand} /> : null}
      <Text variant="meta" style={styles.bold}>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
    padding: space.space20,
    gap: space.space16,
  },
  top: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
  },
  flex: {
    flex: 1,
  },
  skip: {
    minHeight: 44,
    minWidth: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bold: {
    fontWeight: '700',
  },
  art: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space16,
    borderRadius: radius.radius28,
    backgroundColor: colors.brandTint,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: space.space8,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space6,
    paddingHorizontal: space.space12,
    paddingVertical: space.space6,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  text: {
    gap: space.space8,
  },
  dots: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: space.space6,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: colors.line,
  },
  dotOn: {
    width: 24,
    backgroundColor: colors.brand,
  },
});
