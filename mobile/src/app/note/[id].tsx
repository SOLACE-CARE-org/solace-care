import { colors, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { Check, TriangleAlert } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { useMyBookings } from '@/api/bookings';
import {
  type Note,
  type NoteFormat,
  type NoteSection,
  useDiscardNote,
  useEditNote,
  useNote,
  useRedraftNote,
  useSignNote,
} from '@/api/care';
import { ApiProblem } from '@/api/core';
import i18n from '@/i18n';
import { longWhen } from '@/state/bookingView';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

const KEYS: Record<NoteFormat, NoteSection['key'][]> = {
  SOAP: ['S', 'O', 'A', 'P'],
  DAP: ['D', 'A', 'P'],
};

/**
 * Review AI note draft (NoteReview.dc.html), Phase 4: the specialist checks and edits every line,
 * writes the assessment, and signs; signing deletes the session audio (D57). A signed note reads
 * only.
 */
export default function NoteReview() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const note = useNote(id);

  if (note.isPending || note.isError) {
    return (
      <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
        <View style={styles.top}>
          <BackButton />
        </View>
        <View style={styles.center}>
          {note.isPending ? (
            <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
          ) : (
            <>
              <Text variant="body" color={colors.inkMuted}>
                {t('common.checkConnection')}
              </Text>
              <Button label={t('common.tryAgain')} onPress={() => void note.refetch()} />
            </>
          )}
        </View>
      </SafeAreaView>
    );
  }
  return <Review key={`${note.data.format}-${note.data.status}`} note={note.data} />;
}

function Review({ note }: { note: Note }) {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const past = useMyBookings('PAST');
  const languages = useSessionLanguages(lang);
  const edit = useEditNote(note.id);
  const redraft = useRedraftNote(note.id);
  const sign = useSignNote(note.id);
  const discard = useDiscardNote(note.id);
  const [texts, setTexts] = useState<Record<string, string>>(() =>
    Object.fromEntries(note.sections.map((s) => [s.key, s.text])),
  );
  const [problem, setProblem] = useState<string | null>(null);
  const draft = note.status === 'DRAFT';
  const b = past.data?.find((x) => x.id === note.bookingId);
  const language = b
    ? (languages.data?.find((l) => l.code === b.sessionLanguage)?.name ?? b.sessionLanguage)
    : '';
  const sections: NoteSection[] = KEYS[note.format].map((key) => ({
    key,
    text: texts[key] ?? '',
  }));
  const changed = sections.some(
    (s) => s.text !== (note.sections.find((x) => x.key === s.key)?.text ?? ''),
  );
  const complete = sections.every((s) => s.text.trim().length > 0);
  const busy = edit.isPending || redraft.isPending || sign.isPending || discard.isPending;
  const fail = (e: unknown) =>
    setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));

  const switchFormat = (format: NoteFormat) => {
    if (format === note.format || !note.canRedraft) return;
    setProblem(null);
    redraft.mutate(format, { onError: () => setProblem(t('note.redraftFailed')) });
  };

  const signNow = async () => {
    setProblem(null);
    try {
      if (changed) await edit.mutateAsync({ format: note.format, sections });
      await sign.mutateAsync();
    } catch (e) {
      fail(e);
    }
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View>
          <Text variant="titleLg" accessibilityRole="header">
            {draft ? t('note.title') : t('note.signedTitle')}
          </Text>
          {b ? (
            <Text variant="body" color={colors.inkMuted}>
              {t('note.sub', {
                name: b.seeker?.label ?? t('notes.seeker'),
                when: longWhen(b.start, lang),
                language,
              })}
            </Text>
          ) : null}
        </View>

        {draft && note.aiDrafted ? (
          <View style={styles.notice} accessibilityRole="summary">
            <Text variant="body" color={colors.warningInk}>
              <Text variant="body" color={colors.warningInk} style={styles.bold}>
                {t('note.aiNotice')}
              </Text>{' '}
              {t('note.aiNoticeBody')}
            </Text>
          </View>
        ) : null}

        <View style={styles.checks}>
          {note.aiDrafted ? <Line ok text={t('note.consented')} /> : null}
          <Line
            ok={!note.riskSignals}
            text={note.riskSignals ? t('note.risk') : t('note.noRisk')}
          />
          {note.signedAt ? (
            <Line ok text={t('note.signedOn', { when: longWhen(note.signedAt, lang) })} />
          ) : null}
        </View>

        {draft ? (
          <Segmented
            label={t('note.format')}
            value={note.format}
            onChange={switchFormat}
            options={[
              { value: 'SOAP', label: 'SOAP' },
              { value: 'DAP', label: 'DAP' },
            ]}
          />
        ) : null}

        {sections.map((s) =>
          draft ? (
            <TextField
              key={s.key}
              label={t(`note.${s.key}`)}
              value={s.text}
              onChangeText={(text) => setTexts((x) => ({ ...x, [s.key]: text }))}
              placeholder={s.key === 'A' ? t('note.assessmentPlaceholder') : undefined}
              multiline
              maxLength={4000}
            />
          ) : (
            <View key={s.key} style={styles.card}>
              <Text variant="label" color={colors.inkMuted}>
                {t(`note.${s.key}`)}
              </Text>
              <Text variant="body">{s.text}</Text>
            </View>
          ),
        )}

        {problem ? (
          <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
            {problem}
          </Text>
        ) : null}

        {draft ? (
          <View style={styles.actions}>
            {changed ? (
              <Button
                variant="secondary"
                label={t('note.save')}
                disabled={busy}
                onPress={() => edit.mutate({ format: note.format, sections }, { onError: fail })}
              />
            ) : null}
            <Button
              label={t('note.sign')}
              disabled={busy || !complete}
              accessibilityState={{ disabled: busy || !complete, busy }}
              onPress={() => void signNow()}
            />
            <Button
              variant="quiet"
              label={t('note.discard')}
              disabled={busy}
              onPress={() =>
                discard.mutate(undefined, { onSuccess: () => router.back(), onError: fail })
              }
            />
          </View>
        ) : (
          <Button
            variant="secondary"
            label={t('note.assignHomework')}
            onPress={() =>
              router.push({
                pathname: '/homework/assign/[bookingId]',
                params: { bookingId: note.bookingId },
              })
            }
          />
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function Line({ ok, text }: { ok: boolean; text: string }) {
  const Icon = ok ? Check : TriangleAlert;
  return (
    <View style={styles.line}>
      <Icon size={18} strokeWidth={1.8} color={ok ? colors.brand : colors.warningInk} aria-hidden />
      <Text variant="meta" color={ok ? colors.inkMuted : colors.warningInk} style={styles.flex}>
        {text}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: space.space14 },
  content: { padding: space.space20, gap: space.space16 },
  notice: {
    padding: space.space12,
    borderRadius: radius.radius12,
    backgroundColor: colors.warningTint,
  },
  checks: { gap: space.space6 },
  line: { flexDirection: 'row', alignItems: 'center', gap: space.space8 },
  card: {
    gap: space.space4,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  actions: { gap: space.space8 },
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
});
