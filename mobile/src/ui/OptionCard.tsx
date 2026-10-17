import { colors, radius, space } from '@solace/tokens/native';
import { Check } from 'lucide-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from './Text';

/** The control inside the card is 18px (components/OptionCard). */
const CONTROL = 18;

type Props = {
  kind: 'radio' | 'checkbox';
  title: string;
  meta?: string;
  selected: boolean;
  onPress: () => void;
};

/**
 * OptionCard (design-system components/OptionCard): a full-width radio or checkbox row. Selected:
 * brand tint, 2px brand border, padding one less so nothing shifts. Wrap radios in a View with
 * accessibilityRole="radiogroup".
 */
export function OptionCard({ kind, title, meta, selected, onPress }: Props) {
  return (
    <Pressable
      accessibilityRole={kind}
      accessibilityState={{ checked: selected }}
      accessibilityLabel={meta ? `${title}. ${meta}` : title}
      onPress={onPress}
      style={[styles.card, selected && styles.selected]}
    >
      <View
        aria-hidden
        style={[
          styles.control,
          kind === 'radio' ? styles.radio : styles.checkbox,
          selected && (kind === 'radio' ? styles.radioOn : styles.checkboxOn),
        ]}
      >
        {selected && kind === 'radio' ? <View style={styles.dot} /> : null}
        {selected && kind === 'checkbox' ? (
          <Check size={14} strokeWidth={3} color={colors.onBrand} />
        ) : null}
      </View>
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
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space10,
    minHeight: 50,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  selected: {
    padding: space.space14 - 1,
    borderWidth: 2,
    borderColor: colors.brand,
    backgroundColor: colors.brandTint,
  },
  control: {
    width: CONTROL,
    height: CONTROL,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  radio: {
    borderRadius: CONTROL / 2,
  },
  radioOn: {
    borderColor: colors.brand,
  },
  dot: {
    width: CONTROL / 2,
    height: CONTROL / 2,
    borderRadius: CONTROL / 4,
    backgroundColor: colors.brand,
  },
  checkbox: {
    borderRadius: radius.radius3,
  },
  checkboxOn: {
    borderColor: colors.brand,
    backgroundColor: colors.brand,
  },
  text: {
    flex: 1,
    gap: space.space2,
  },
  title: {
    fontSize: 16,
  },
});
