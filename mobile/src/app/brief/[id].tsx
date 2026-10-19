import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { useBrief, usePrepNote, useSavePrepNote } from '@/api/brief';
import i18n from '@/i18n';
import { joinOpensAt, longWhen } from '@/state/bookingView';
import { timeOf } from '@/state/format';
import { useNow } from '@/state/useNow';
import { Button } from '@/ui/Button';
import { Pill } from '@/ui/Pill';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/**
 * The pre-session brief (SessionBrief.dc.html), Phase 3: what the seeker chose to share, built by
 * rules, and the specialist's private prep notes. Health check totals reach the specialist only;
 * a tracking summary is worded by AI only from the lines shown under it (D56).
 */
export default function SessionBriefScreen() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const now = useNow(30_000);
  const brief = useBrief(id, lang);
  const languages = useSessionLanguages(lang);

  if (brief.isPending || brief.isError) {
    return (
      <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
        <View style={styles.top}>
          <BackButton />
        </View>
        <View style={[styles.content, styles.center]}>
          {brief.isPending ? (
            <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
          ) : (
            <>
              <Text variant="body" color={colors.inkMuted}>
                {t('common.checkConnection')}
              </Text>
              <Button label={t('common.tryAgain')} onPress={() => void brief.refetch()} />
            </>
          )}
        </View>
      </SafeAreaView>
    );
  }

  const b = brief.data;
  const language =
    languages.data?.find((l) => l.code === b.sessionLanguage)?.name ?? b.sessionLanguage;
  const opens = joinOpensAt({ start: b.startsAt });
  const open = now >= opens.getTime();

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <View>
          <Text variant="titleLg" accessibilityRole="header">
            {t('brief.title')}
          </Text>
          <Text variant="body" color={colors.inkMuted}>
            {t('brief.when', {
              when: longWhen(b.startsAt, lang),
              modality: t(`book.modality_${b.modality}`),
              language,
            })}
          </Text>
        </View>

        <View style={styles.pills}>
          <Pill label={b.guest ? t('brief.guest') : t('brief.registered')} tone="neutral" />
          {b.firstSession ? <Pill label={t('brief.firstSession')} tone="neutral" /> : null}
        </View>

        <View style={styles.flags}>
          <Text variant="label" color={b.healthCheckSafetyStop ? colors.danger : colors.inkMuted}>
            {b.healthCheckSafetyStop ? t('brief.safetyStop') : t('brief.noSafetyFlags')}
          </Text>
          <Text variant="label" color={colors.inkMuted}>
            {b.scribeConsent ? t('brief.scribeYes') : t('brief.scribeNo')}
          </Text>
        </View>

        <View style={styles.card}>
          <Text variant="titleSm" accessibilityRole="header">
            {t('brief.healthCheck')}
          </Text>
          {b.healthCheckShared ? (
            <>
              {b.healthCheckSharedAt ? (
                <Text variant="meta" color={colors.inkMuted}>
                  {t('brief.sharedOn', {
                    date: new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short' }).format(
                      new Date(b.healthCheckSharedAt),
                    ),
                  })}
                </Text>
              ) : null}
              {b.healthCheckSummary ? <Text variant="body">{b.healthCheckSummary}</Text> : null}
              {b.scores.length > 0 ? (
                <View style={styles.scores}>
                  <Text variant="meta" color={colors.inkMuted}>
                    {b.scores.map((s) => s.screener).join(' · ')}
                  </Text>
                  <Text variant="label" style={styles.bold}>
                    {b.scores
                      .map((s) => (s.band ? `${s.total} ${s.band}` : `${s.total}`))
                      .join(' · ')}
                  </Text>
                </View>
              ) : null}
            </>
          ) : (
            <Text variant="body" color={colors.inkMuted}>
              {t('brief.notShared')}
            </Text>
          )}
        </View>

        <View style={styles.card}>
          <Text variant="titleSm" accessibilityRole="header">
            {t('brief.tracking')}
          </Text>
          {b.tracking ? (
            <>
              {b.tracking.summary ? <Text variant="body">{b.tracking.summary}</Text> : null}
              {b.tracking.lines.map((line) => (
                <Text
                  key={line}
                  variant="body"
                  color={b.tracking?.summary ? colors.inkMuted : undefined}
                >
                  {line}
                </Text>
              ))}
              {b.tracking.aiWorded ? (
                <Text variant="meta" color={colors.inkMuted}>
                  {t('brief.aiWorded')}
                </Text>
              ) : null}
            </>
          ) : (
            <Text variant="body" color={colors.inkMuted}>
              {b.guest ? t('brief.trackingGuest') : t('brief.trackingNone')}
            </Text>
          )}
        </View>

        <PrepNotes bookingId={id} />

        <Button
          label={open ? t('brief.join') : t('brief.opensAt', { time: timeOf(opens, lang) })}
          disabled={!open}
          accessibilityState={{ disabled: !open }}
          onPress={() => router.push({ pathname: '/call/[id]', params: { id } })}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

/** Private to the specialist: the seeker never sees these, and nothing reads them (D56). */
function PrepNotes({ bookingId }: { bookingId: string }) {
  const { t } = useTranslation();
  const note = usePrepNote(bookingId);
  const save = useSavePrepNote(bookingId);
  const [draft, setDraft] = useState<string | null>(null);

  const value = draft ?? note.data?.body ?? '';
  const changed = draft !== null && draft !== (note.data?.body ?? '');
  return (
    <View style={styles.card}>
      <TextField
        label={t('brief.prepNotes')}
        value={value}
        onChangeText={setDraft}
        multiline
        maxLength={4000}
        help={save.isError ? undefined : t('brief.prepHelp')}
        error={save.isError ? t('common.checkConnection') : undefined}
      />
      {changed ? (
        <Button
          variant="secondary"
          label={t('brief.saveNotes')}
          disabled={save.isPending}
          onPress={() => save.mutate(value, { onSuccess: () => setDraft(null) })}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: { paddingHorizontal: space.space12 },
  content: { padding: space.space20, gap: space.space16 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: space.space14 },
  pills: { flexDirection: 'row', flexWrap: 'wrap', gap: space.space8 },
  flags: { gap: space.space4 },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  scores: { gap: space.space4 },
  bold: { fontFamily: fontFamily.body[700] },
});
