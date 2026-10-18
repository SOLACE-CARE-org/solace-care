import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { ChevronLeft } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from './Text';

type Props = { step: number; total: number };

/**
 * ProgressSteps (design-system components/ProgressSteps) in its row: back button, the segmented bar
 * and "1 of 3".
 */
export function ProgressSteps({ step, total }: Props) {
  const { t } = useTranslation();
  return (
    <View style={styles.row}>
      <BackButton />
      <View
        style={styles.bar}
        accessibilityRole="progressbar"
        accessibilityLabel={t('signup.progressLabel', { step, total })}
        accessibilityValue={{ min: 1, max: total, now: step }}
      >
        {Array.from({ length: total }, (_, i) => (
          <View key={i} style={[styles.segment, i < step && styles.done]} />
        ))}
      </View>
      <Text variant="meta" color={colors.inkMuted} style={styles.count} aria-hidden>
        {t('signup.progress', { step, total })}
      </Text>
    </View>
  );
}

/** The 44px back arrow at the top left of every flow screen; {@code label} is for screen readers. */
export function BackButton({ onPress, label }: { onPress?: () => void; label?: string }) {
  const { t } = useTranslation();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label ?? t('common.back')}
      onPress={onPress ?? (() => router.back())}
      style={styles.back}
    >
      <ChevronLeft size={22} strokeWidth={2} color={colors.ink} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
  },
  back: {
    width: size.sizeTap,
    height: size.sizeTap,
    marginLeft: -space.space10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bar: {
    flex: 1,
    flexDirection: 'row',
    gap: space.space4,
  },
  segment: {
    flex: 1,
    height: 6,
    borderRadius: radius.radius3,
    backgroundColor: colors.line,
  },
  done: {
    backgroundColor: colors.brand,
  },
  count: {
    fontFamily: fontFamily.body[600],
  },
});
