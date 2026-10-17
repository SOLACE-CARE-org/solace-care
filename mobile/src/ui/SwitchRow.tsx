import { colors, radius, space } from '@solace/tokens/native';
import type { ReactNode } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from './Text';

// components/Switch: track 48x28, thumb 22px.
const TRACK = { width: 48, height: 28 };
const THUMB = 22;

type Props = {
  title: string;
  /** A line under the title; rows without one centre the title on the switch. */
  meta?: string;
  /** An icon before the title (SpecServices: microphone, camera). */
  icon?: ReactNode;
  on: boolean;
  onToggle: () => void;
};

/**
 * A setting row with a Switch (design-system components/Switch). The whole row is the control;
 * screen readers hear the title, the meta and the on/off state.
 */
export function SwitchRow({ title, meta, icon, on, onToggle }: Props) {
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityState={{ checked: on }}
      accessibilityLabel={title}
      accessibilityHint={meta}
      onPress={onToggle}
      style={[styles.row, !meta && styles.centred]}
    >
      {icon ? <View aria-hidden>{icon}</View> : null}
      <View style={styles.text}>
        <Text variant="label" style={styles.title}>
          {title}
        </Text>
        {meta ? (
          <Text variant="meta" color={colors.inkMuted}>
            {meta}
          </Text>
        ) : null}
      </View>
      <View aria-hidden style={[styles.track, on && styles.trackOn]}>
        <View style={[styles.thumb, on && styles.thumbOn]} />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: space.space14,
    paddingVertical: space.space16,
  },
  centred: {
    alignItems: 'center',
  },
  text: {
    flex: 1,
    gap: space.space4,
  },
  title: {
    fontSize: 16,
  },
  track: {
    width: TRACK.width,
    height: TRACK.height,
    borderRadius: TRACK.height / 2,
    justifyContent: 'center',
    paddingHorizontal: (TRACK.height - THUMB) / 2,
    backgroundColor: colors.lineControl,
  },
  trackOn: {
    backgroundColor: colors.brand,
  },
  thumb: {
    width: THUMB,
    height: THUMB,
    borderRadius: radius.radiusPill,
    backgroundColor: colors.surfaceCard,
  },
  thumbOn: {
    alignSelf: 'flex-end',
  },
});
