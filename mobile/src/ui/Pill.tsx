import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { StyleSheet, View } from 'react-native';

import { Text } from './Text';

export type Tone = 'brand' | 'warning' | 'neutral' | 'danger';

const tones: Record<Tone, { background: string; ink: string }> = {
  brand: { background: colors.brandTint, ink: colors.brandDeep },
  warning: { background: colors.warningTint, ink: colors.warningInk },
  neutral: { background: colors.surfaceSunken, ink: colors.inkNeutral },
  danger: { background: colors.dangerTint, ink: colors.danger },
};

/** Pill (design-system components/Pill): a read-only status word. The word carries the meaning. */
export function Pill({ label, tone }: { label: string; tone: Tone }) {
  const { background, ink } = tones[tone];
  return (
    <View style={[styles.pill, { backgroundColor: background }]}>
      <Text variant="meta" color={ink} style={styles.text}>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    alignSelf: 'flex-start',
    paddingHorizontal: space.space8,
    paddingVertical: 3,
    borderRadius: radius.radiusPill,
  },
  text: {
    fontSize: 12,
    fontFamily: fontFamily.body[700],
  },
});
