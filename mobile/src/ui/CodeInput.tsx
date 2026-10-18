import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { type ComponentRef, forwardRef, useImperativeHandle, useRef } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import { Text } from './Text';

export const CODE_LENGTH = 6;

type Props = {
  label: string;
  value: string;
  onChange: (code: string) => void;
  onSubmit?: () => void;
  /** Draws the boxes in the danger colour after a wrong code. */
  wrong?: boolean;
  /** Read out with the field, e.g. the error. */
  hint?: string;
  autoFocus?: boolean;
  /** 58px on Verify code, 50px inside Checkout. */
  compact?: boolean;
};

export type CodeInputHandle = { focus: () => void };

/**
 * A 6-digit code: one input drawn as six boxes, so the SMS one-time-code autofill works and screen
 * readers hear a single field (VerifyCode.dc.html, Checkout.dc.html).
 */
export const CodeInput = forwardRef<CodeInputHandle, Props>(function CodeInput(
  { label, value, onChange, onSubmit, wrong = false, hint, autoFocus = false, compact = false },
  ref,
) {
  const input = useRef<ComponentRef<typeof TextInput>>(null);
  useImperativeHandle(ref, () => ({ focus: () => input.current?.focus() }));
  return (
    <View style={styles.field}>
      <Text variant="label" color={colors.inkMuted} style={styles.label}>
        {label}
      </Text>
      <Pressable onPress={() => input.current?.focus()} accessible={false}>
        <View style={styles.boxes} aria-hidden>
          {Array.from({ length: CODE_LENGTH }, (_, i) => (
            <View
              key={i}
              style={[
                styles.box,
                compact && styles.compact,
                i === value.length && styles.boxCurrent,
                wrong && styles.boxWrong,
              ]}
            >
              <Text style={[styles.digit, compact && styles.digitCompact]}>{value[i] ?? ''}</Text>
            </View>
          ))}
        </View>
      </Pressable>
      <TextInput
        ref={input}
        value={value}
        onChangeText={(text) => onChange(text.replace(/\D/g, '').slice(0, CODE_LENGTH))}
        onSubmitEditing={onSubmit}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        autoComplete="sms-otp"
        autoFocus={autoFocus}
        maxLength={CODE_LENGTH}
        accessibilityLabel={label}
        accessibilityHint={hint}
        style={styles.hiddenInput}
      />
    </View>
  );
});

const styles = StyleSheet.create({
  field: {
    gap: space.space10,
  },
  label: {
    fontSize: 13,
  },
  boxes: {
    flexDirection: 'row',
    gap: space.space8,
  },
  box: {
    flex: 1,
    height: 58,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  compact: {
    height: 50,
    borderRadius: radius.radius12,
  },
  boxCurrent: {
    borderWidth: 2,
    borderColor: colors.brand,
  },
  boxWrong: {
    borderWidth: 2,
    borderColor: colors.danger,
  },
  digit: {
    fontFamily: fontFamily.body[700],
    fontSize: 22,
    color: colors.ink,
  },
  digitCompact: {
    fontSize: 20,
  },
  hiddenInput: {
    position: 'absolute',
    width: 1,
    height: 1,
    opacity: 0,
  },
});
