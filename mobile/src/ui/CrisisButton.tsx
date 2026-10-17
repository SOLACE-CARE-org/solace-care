import { colors, fontFamily, radius, size, space, type as typeStyles } from '@solace/tokens/native';
import { LifeBuoy } from 'lucide-react-native';
import { Pressable, StyleSheet, Text } from 'react-native';

type Props = {
  label: string;
  onPress: () => void;
};

/**
 * The crisis entry point (CLAUDE.md: works for guests, one tap away). `danger-tint` fill, `danger`
 * text and a 20px life-buoy icon, as on the Welcome screen.
 */
export function CrisisButton({ label, onPress }: Props) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => [styles.button, pressed && styles.pressed]}
    >
      <LifeBuoy size={20} strokeWidth={1.8} color={colors.danger} aria-hidden />
      <Text style={[typeStyles.body, styles.label]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space8,
    minHeight: size.sizeTap,
    paddingHorizontal: space.space12,
    borderRadius: radius.radius12,
    backgroundColor: colors.dangerTint,
  },
  pressed: {
    opacity: 0.85,
  },
  label: {
    fontFamily: fontFamily.body[600],
    color: colors.danger,
    textAlign: 'center',
    flexShrink: 1,
  },
});
