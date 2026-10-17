import { colors, fontFamily, radius, size, space, type as typeStyles } from '@solace/tokens/native';
import { Pressable, StyleSheet, Text, type PressableProps } from 'react-native';

/** Onboarding screens use a 56px primary button (components/Button/README.md). */
const ONBOARDING_HEIGHT = 56;

type Variant = 'primary' | 'secondary' | 'quiet';

type Props = Omit<PressableProps, 'children'> & {
  label: string;
  variant?: Variant;
  /** 56px instead of 54px, as on the onboarding and welcome screens. */
  onboarding?: boolean;
};

/**
 * Button (design-system components/Button). One primary per screen, full width at the bottom.
 * The label comes from the i18n catalogue and is allowed to wrap: French runs longer.
 */
export function Button({ label, variant = 'primary', onboarding = false, style, ...props }: Props) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      {...props}
      style={(state) => [
        styles.base,
        variant === 'primary' && styles.primary,
        variant === 'primary' && onboarding && { minHeight: ONBOARDING_HEIGHT },
        variant === 'primary' && state.pressed && styles.primaryPressed,
        variant === 'secondary' && styles.secondary,
        variant === 'secondary' && onboarding && styles.secondaryOnboarding,
        variant === 'quiet' && styles.quiet,
        typeof style === 'function' ? style(state) : style,
      ]}
    >
      <Text
        style={[
          variant === 'quiet' ? styles.quietLabel : typeStyles.buttonLg,
          { color: variant === 'primary' ? colors.onBrand : variant === 'quiet' ? colors.brand : colors.ink },
          styles.label,
        ]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: space.space16,
  },
  primary: {
    minHeight: size.sizeButtonMobile,
    borderRadius: radius.radius16,
    backgroundColor: colors.brand,
  },
  primaryPressed: {
    backgroundColor: colors.brandDeep,
  },
  secondary: {
    minHeight: size.sizeFieldMobile,
    borderRadius: radius.radius14,
    backgroundColor: colors.surfaceCard,
    borderWidth: 1,
    borderColor: colors.lineControl,
  },
  // Welcome's "Create an account" matches the primary's size and radius (Main.dc.html).
  secondaryOnboarding: {
    minHeight: ONBOARDING_HEIGHT,
    borderRadius: radius.radius16,
  },
  quiet: {
    minHeight: size.sizeTap,
  },
  quietLabel: {
    ...typeStyles.body,
    fontFamily: fontFamily.body[700],
  },
  label: {
    textAlign: 'center',
  },
});
