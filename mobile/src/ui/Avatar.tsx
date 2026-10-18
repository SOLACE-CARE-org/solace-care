import { colors, fontFamily } from '@solace/tokens/native';
import { StyleSheet, View } from 'react-native';

import { initials } from '@/state/format';

import { Text } from './Text';

/**
 * Avatar (design-system components/Avatar): initials in a disc. Decorative: the name always sits
 * next to it, so screen readers skip it.
 */
export function Avatar({ name, size = 44 }: { name: string; size?: number }) {
  return (
    <View aria-hidden style={[styles.disc, { width: size, height: size, borderRadius: size / 2 }]}>
      <Text style={[styles.initials, { fontSize: Math.round(size * 0.36) }]}>{initials(name)}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  disc: {
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.accent,
  },
  initials: {
    fontFamily: fontFamily.display[700],
    color: colors.onAccent,
  },
});
