import { colors, radius, space } from '@solace/tokens/native';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from './Text';

/** Segments are 40px on the track (components/Segmented). */
const SEGMENT_HEIGHT = 40;

type Props<T extends string> = {
  label: string;
  options: { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
};

/** Segmented (design-system components/Segmented), the mobile form: 2 to 4 equal segments. */
export function Segmented<T extends string>({ label, options, value, onChange }: Props<T>) {
  return (
    <View style={styles.track} accessibilityRole="tablist" accessibilityLabel={label}>
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            accessibilityLabel={option.label}
            onPress={() => onChange(option.value)}
            // 40px segment plus the 4px track inset each side reach the 44px touch target.
            hitSlop={space.space4}
            style={[styles.segment, selected && styles.selected]}
          >
            <Text variant="label" color={selected ? colors.ink : colors.inkMuted} style={styles.text}>
              {option.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  track: {
    flexDirection: 'row',
    padding: space.space4,
    borderRadius: radius.radius14,
    backgroundColor: colors.surfaceTrack,
  },
  segment: {
    flex: 1,
    height: SEGMENT_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.radius10,
  },
  selected: {
    backgroundColor: colors.surfaceCard,
  },
  text: {
    fontSize: 15,
  },
});
