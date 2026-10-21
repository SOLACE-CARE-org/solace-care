import { colors, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useMyBookings } from '@/api/bookings';
import { useAnswerCompanion, useCompanion, useMyHomework } from '@/api/care';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

type Bubble = { from: 'companion' | 'me'; text: string };

/**
 * Companion & homework (Companion.dc.html), Phase 4: an AI guide that follows the specialist's
 * plan step by step. Every answer is checked by the safety monitor first; on a risk signal the
 * companion stops and urgent help opens (D57).
 */
export default function CompanionScreen() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const turn = useCompanion(id, lang);
  const answer = useAnswerCompanion(id, lang);
  const homework = useMyHomework(true);
  const past = useMyBookings('PAST');
  const [text, setText] = useState('');
  const [bubbles, setBubbles] = useState<Bubble[]>([]);

  const h = homework.data?.find((x) => x.id === id);
  const specialist = past.data?.find((b) => b.id === h?.bookingId)?.specialist.fullName;
  const current = answer.data ?? turn.data;
  // The opening question, until the seeker answers.
  const opening: Bubble[] = turn.data ? [{ from: 'companion', text: turn.data.prompt }] : [];
  const shown = bubbles.length ? bubbles : opening;

  const send = () => {
    const value = text.trim();
    if (!value || answer.isPending) return;
    setBubbles((b) => [...(b.length ? b : opening), { from: 'me', text: value }]);
    setText('');
    answer.mutate(value, {
      onSuccess: (next) => {
        setBubbles((b) => [...b, { from: 'companion', text: next.prompt }]);
        if (next.crisis) router.push('/crisis');
      },
    });
  };

  if (turn.isPending || turn.isError) {
    return (
      <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
        <View style={styles.top}>
          <BackButton />
        </View>
        <View style={styles.center}>
          {turn.isPending ? (
            <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
          ) : (
            <>
              <Text variant="body" color={colors.inkMuted}>
                {t('common.checkConnection')}
              </Text>
              <Button label={t('common.tryAgain')} onPress={() => void turn.refetch()} />
            </>
          )}
        </View>
      </SafeAreaView>
    );
  }

  const record = Object.entries(current?.record ?? {});
  const finished = current?.finished ?? false;

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <KeyboardAvoidingView
        style={styles.screen}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View style={styles.top}>
          <BackButton />
        </View>
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <View>
            <Text variant="titleLg" accessibilityRole="header">
              {t('companion.heading')}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('companion.guide')}
            </Text>
          </View>

          {h ? (
            <View style={styles.card}>
              <Text variant="label" style={styles.bold}>
                {t('homework.progress', {
                  exercise:
                    h.exercise === 'CUSTOM' ? (h.customTask ?? '') : t(`homework.${h.exercise}`),
                  done: current?.done ?? h.done,
                  target: h.target,
                })}
              </Text>
              {specialist ? (
                <Text variant="meta" color={colors.inkMuted}>
                  {t('homework.setBy', {
                    name: specialist,
                    date: new Intl.DateTimeFormat(lang, {
                      weekday: 'short',
                      day: 'numeric',
                      month: 'short',
                    }).format(new Date(h.dueBy)),
                  })}
                </Text>
              ) : null}
              {h.note ? <Text variant="body">{h.note}</Text> : null}
            </View>
          ) : null}

          {shown.map((b, i) => (
            <View
              key={i}
              style={[styles.bubble, b.from === 'me' ? styles.mine : styles.theirs]}
              accessibilityLiveRegion={b.from === 'companion' ? 'polite' : undefined}
            >
              <Text variant="body" color={b.from === 'me' ? colors.onBrand : colors.ink}>
                {b.text}
              </Text>
            </View>
          ))}

          {record.length > 0 ? (
            <View style={styles.card}>
              <Text variant="label" color={colors.inkMuted}>
                {t('companion.recordSoFar')}
              </Text>
              {record.map(([step, value]) => (
                <Text key={step} variant="meta">
                  {t(`companion.${step}`)}: {value}
                </Text>
              ))}
            </View>
          ) : null}
        </ScrollView>

        <View style={styles.footer}>
          {finished ? (
            <Text variant="body" color={colors.inkMuted} style={styles.centerText}>
              {t('companion.finished')}
            </Text>
          ) : (
            <>
              <View style={styles.flex}>
                <TextField
                  label={t('companion.answer')}
                  value={text}
                  onChangeText={setText}
                  multiline
                  maxLength={2000}
                />
              </View>
              <Button
                label={t('companion.send')}
                disabled={!text.trim() || answer.isPending}
                accessibilityState={{
                  disabled: !text.trim() || answer.isPending,
                  busy: answer.isPending,
                }}
                onPress={send}
              />
            </>
          )}
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: space.space14 },
  centerText: { textAlign: 'center', flex: 1 },
  content: { padding: space.space20, gap: space.space12 },
  card: {
    gap: space.space4,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  bubble: { maxWidth: '85%', padding: space.space12, borderRadius: radius.radius16 },
  theirs: { alignSelf: 'flex-start', backgroundColor: colors.brandTint },
  mine: { alignSelf: 'flex-end', backgroundColor: colors.brand },
  footer: {
    gap: space.space8,
    padding: space.space16,
    borderTopWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
});
