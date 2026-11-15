import { colors, space } from '@solace/tokens/native';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import type { ContextAnswer, ContextQuestion } from '@/api/tracking';
import { Chip } from '@/ui/Chip';
import { Text } from '@/ui/Text';

/** The question in the app language, falling back to the code. */
export function questionText(q: ContextQuestion, lang: 'en' | 'fr'): string {
  return q.labels?.[lang]?.question ?? q.code;
}

/**
 * One context question (D61), answered with chips as on the health check preferences
 * (HealthCheckPrefs): yes or no, one choice, or several. Every question is optional: until it is
 * answered it says so, and "Clear answer" takes an answer back. An unanswered question never
 * switches a pattern check off.
 */
export function ContextQuestionField({
  question: q,
  lang,
  value,
  onChange,
  disabled,
}: {
  question: ContextQuestion;
  lang: 'en' | 'fr';
  value: ContextAnswer | undefined;
  onChange: (value: ContextAnswer | null) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const many = q.type === 'MANY_CHOICES';
  const options =
    q.type === 'YES_NO'
      ? [
          { code: 'true', label: t('track.enrol.yes') },
          { code: 'false', label: t('track.enrol.no') },
        ]
      : q.options.map((o) => ({ code: o.code, label: o.labels[lang] || o.code }));
  const chosen = (code: string) =>
    q.type === 'YES_NO'
      ? value !== undefined && String(value) === code
      : Array.isArray(value)
        ? value.includes(code)
        : value === code;
  const pick = (code: string) => {
    if (disabled) return;
    if (q.type === 'YES_NO') return onChange(code === 'true');
    if (!many) return onChange(code);
    const now = Array.isArray(value) ? value : [];
    const next = now.includes(code) ? now.filter((c) => c !== code) : [...now, code];
    onChange(next.length ? next : null);
  };
  const title = questionText(q, lang);
  const answered = value !== undefined && !(Array.isArray(value) && value.length === 0);

  return (
    <View style={styles.question}>
      <Text variant="label" nativeID={`q-${q.code}`}>
        {title}
      </Text>
      {many ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('track.about.many')}
        </Text>
      ) : null}
      <View
        style={styles.chips}
        accessibilityRole={many ? undefined : 'radiogroup'}
        accessibilityLabel={title}
      >
        {options.map((o) => (
          <Chip
            key={o.code}
            label={o.label}
            on={chosen(o.code)}
            role={many ? 'checkbox' : 'radio'}
            onToggle={() => pick(o.code)}
          />
        ))}
      </View>
      {answered ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('track.about.clearFor', { question: title })}
          onPress={() => !disabled && onChange(null)}
          hitSlop={8}
          style={styles.clear}
        >
          <Text variant="label" color={colors.brand}>
            {t('track.about.clear')}
          </Text>
        </Pressable>
      ) : (
        <Text variant="meta" color={colors.inkMuted}>
          {t('track.about.unanswered')}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  question: { gap: space.space10 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.space8 },
  clear: { alignSelf: 'flex-start', minHeight: 24, justifyContent: 'center' },
});
