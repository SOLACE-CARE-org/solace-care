import { colors, space } from '@solace/tokens/native';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import type { ContextAnswer, ContextQuestion } from '@/api/tracking';
import { OptionCard } from '@/ui/OptionCard';
import { Text } from '@/ui/Text';

/** The question in the app language, falling back to the code. */
export function questionText(q: ContextQuestion, lang: 'en' | 'fr'): string {
  return q.labels?.[lang]?.question ?? q.code;
}

/**
 * One context question (D61): yes or no, one choice, or several. Every question can be skipped;
 * "Skip" leaves it unanswered, and an unanswered question never switches a pattern check off.
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

  return (
    <View style={styles.question} accessibilityRole={many ? undefined : 'radiogroup'}>
      <Text variant="label">{title}</Text>
      {options.map((o) => (
        <OptionCard
          key={o.code}
          kind={many ? 'checkbox' : 'radio'}
          title={o.label}
          selected={chosen(o.code)}
          onPress={() => pick(o.code)}
        />
      ))}
      <OptionCard
        kind={many ? 'checkbox' : 'radio'}
        title={t('track.about.skip')}
        selected={value === undefined}
        onPress={() => !disabled && onChange(null)}
      />
      {many ? (
        <Text variant="meta" color={colors.inkMuted}>
          {t('track.about.many')}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  question: { gap: space.space8 },
});
