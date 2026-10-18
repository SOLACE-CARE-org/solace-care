import { colors, space } from '@solace/tokens/native';
import { Star } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from './Text';

/** Stars to read: decorative, with the number in words for screen readers. */
export function Stars({ value, size = 16 }: { value: number; size?: number }) {
  const { t } = useTranslation();
  return (
    <View style={styles.row} accessible accessibilityLabel={t('rate.star', { count: value })}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          size={size}
          strokeWidth={1.8}
          color={colors.ratingStar}
          fill={n <= value ? colors.ratingStar : 'transparent'}
          aria-hidden
        />
      ))}
    </View>
  );
}

/** Stars to choose (Rate your session): five 44px targets, a radio group out of 5. */
export function StarInput({
  value,
  onChange,
}: {
  value: number;
  onChange: (stars: number) => void;
}) {
  const { t } = useTranslation();
  return (
    <View style={styles.input} accessibilityRole="radiogroup" accessibilityLabel={t('rate.outOf')}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Pressable
          key={n}
          accessibilityRole="radio"
          accessibilityLabel={t('rate.star', { count: n })}
          accessibilityState={{ checked: n === value }}
          onPress={() => onChange(n)}
          style={styles.target}
        >
          <Star
            size={36}
            strokeWidth={1.8}
            color={colors.ratingStar}
            fill={n <= value ? colors.ratingStar : 'transparent'}
            aria-hidden
          />
        </Pressable>
      ))}
    </View>
  );
}

/** "★ 4.8 (23)" on search cards and profiles; nothing until a rating is shown (D37). */
export function RatingBadge({ rating }: { rating?: { average: number; count: number } }) {
  const { t, i18n } = useTranslation();
  if (!rating) return null;
  const average = new Intl.NumberFormat(i18n.language, {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).format(rating.average);
  return (
    <View
      style={styles.badge}
      accessible
      accessibilityLabel={t('ratingLabel', { average, count: rating.count })}
    >
      <Star
        size={14}
        strokeWidth={1.8}
        color={colors.ratingStar}
        fill={colors.ratingStar}
        aria-hidden
      />
      <Text variant="meta" color={colors.inkSoft}>
        {t('ratingShort', { average, count: rating.count })}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    gap: space.space2,
  },
  input: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: space.space4,
  },
  target: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
});
