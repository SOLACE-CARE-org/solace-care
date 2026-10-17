import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { Pressable, StyleSheet } from 'react-native';

import { Text } from './Text';

type Props = {
  label: string;
  on: boolean;
  onToggle: () => void;
};

/**
 * Chip (design-system components/Chip): a pill that toggles. The heavier border and weight carry
 * the "on" state, not the colour alone. Wrap chips in a View with a group label.
 */
export function Chip({ label, on, onToggle }: Props) {
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityState={{ checked: on }}
      accessibilityLabel={label}
      onPress={onToggle}
      hitSlop={(size.sizeTap - size.sizeChip) / 2}
      style={[styles.chip, on && styles.on]}
    >
      <Text
        variant="label"
        color={on ? colors.brandDeep : colors.ink}
        style={[styles.text, { fontFamily: fontFamily.body[on ? 700 : 600] }]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  chip: {
    minHeight: size.sizeChip,
    justifyContent: 'center',
    paddingHorizontal: space.space14,
    borderRadius: radius.radiusPill,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  on: {
    borderWidth: 2,
    borderColor: colors.brand,
    backgroundColor: colors.brandTint,
    paddingHorizontal: space.space14 - 1,
  },
  text: {
    fontSize: 14,
  },
});
