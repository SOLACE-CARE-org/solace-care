import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { type ComponentRef, forwardRef, useState } from 'react';
import { StyleSheet, TextInput, type TextInputProps, View } from 'react-native';

import { Text } from './Text';

type Props = Omit<TextInputProps, 'style'> & {
  label: string;
  /** Meta text under the field. */
  help?: string;
  /** Shown instead of the help text, with a danger border; says what went wrong and what to do. */
  error?: string;
  /** A fixed prefix inside the field, e.g. +237 for phone numbers. */
  prefix?: string;
};

/**
 * TextField (design-system components/TextField). The label sits above the field, always visible;
 * the placeholder only gives an example. 50px high with 16px text so iOS does not zoom.
 */
export const TextField = forwardRef<ComponentRef<typeof TextInput>, Props>(function TextField(
  { label, help, error, prefix, onFocus, onBlur, ...input },
  ref,
) {
  const [focused, setFocused] = useState(false);
  return (
    <View style={styles.field}>
      <Text variant="label" color={colors.inkMuted} style={styles.label} nativeID={`${label}-label`}>
        {label}
      </Text>
      <View
        style={[
          styles.box,
          focused && styles.focused,
          error !== undefined && styles.invalid,
        ]}
      >
        {prefix ? (
          <View style={styles.prefix} aria-hidden>
            <Text variant="label" style={styles.prefixText}>
              {prefix}
            </Text>
          </View>
        ) : null}
        <TextInput
          ref={ref}
          accessibilityLabel={prefix ? `${label}, ${prefix}` : label}
          accessibilityHint={error ?? help}
          placeholderTextColor={colors.inkFaint}
          {...input}
          onFocus={(e) => {
            setFocused(true);
            onFocus?.(e);
          }}
          onBlur={(e) => {
            setFocused(false);
            onBlur?.(e);
          }}
          style={styles.input}
        />
      </View>
      {error !== undefined ? (
        <Text variant="meta" color={colors.danger} style={styles.error} accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : help ? (
        <Text variant="meta" color={colors.inkMuted}>
          {help}
        </Text>
      ) : null}
    </View>
  );
});

/** Shows +237 677000000 as "6 77 00 00 00", as on the screens. */
export function spacedLocalNumber(digits: string): string {
  const d = digits.replace(/\D/g, '').slice(0, 9);
  return [d.slice(0, 1), d.slice(1, 3), d.slice(3, 5), d.slice(5, 7), d.slice(7, 9)]
    .filter(Boolean)
    .join(' ');
}

const styles = StyleSheet.create({
  field: {
    gap: space.space6,
  },
  label: {
    fontSize: 13,
  },
  box: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: size.sizeFieldMobile,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
    overflow: 'hidden',
  },
  focused: {
    borderWidth: 2,
    borderColor: colors.brand,
  },
  invalid: {
    borderWidth: 2,
    borderColor: colors.danger,
  },
  prefix: {
    alignSelf: 'stretch',
    justifyContent: 'center',
    paddingHorizontal: space.space12,
    backgroundColor: colors.surfaceSunken,
  },
  prefixText: {
    fontSize: 15,
  },
  input: {
    flex: 1,
    minHeight: size.sizeFieldMobile - 2,
    paddingHorizontal: space.space14,
    fontFamily: fontFamily.body[500],
    fontSize: 16,
    color: colors.ink,
  },
  error: {
    fontFamily: fontFamily.body[600],
  },
});
