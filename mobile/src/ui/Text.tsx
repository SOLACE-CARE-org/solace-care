import { colors, type as typeStyles, type TypeToken } from '@solace/tokens/native';
import { Text as NativeText, type TextProps } from 'react-native';

type Props = TextProps & {
  /** A type style from the design system: displayXl, titleLg, body, meta... */
  variant?: TypeToken;
  color?: string;
};

/** Text in a design-system type style. Defaults to body text in ink. */
export function Text({ variant = 'body', color = colors.ink, style, ...props }: Props) {
  return <NativeText {...props} style={[typeStyles[variant], { color }, style]} />;
}
