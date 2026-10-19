import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { FileText } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { useMyBookings } from '@/api/bookings';
import { type NoteSummary, useNotes } from '@/api/care';
import i18n from '@/i18n';
import { longWhen } from '@/state/bookingView';
import { Button } from '@/ui/Button';
import { Pill } from '@/ui/Pill';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/**
 * Notes (NotesList.dc.html), Phase 4: drafts to review and signed notes. A draft the AI wrote says
 * so; drafts not signed within 7 days are deleted with the session audio (D57).
 */
export default function SpecialistNotes() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const [tab, setTab] = useState<'DRAFT' | 'SIGNED'>('DRAFT');
  const [query, setQuery] = useState('');
  const drafts = useNotes('DRAFT', '');
  const notes = useNotes(tab, query.trim());
  const past = useMyBookings('PAST');
  const languages = useSessionLanguages(lang);
  const booking = (id: string) => past.data?.find((b) => b.id === id);
  const nameOf = (code: string) => languages.data?.find((l) => l.code === code)?.name ?? code;

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        refreshControl={
          <RefreshControl refreshing={notes.isRefetching} onRefresh={() => void notes.refetch()} />
        }
      >
        <Text variant="titleLg" accessibilityRole="header">
          {t('notes.title')}
        </Text>
        <TextField
          label={t('notes.search')}
          value={query}
          onChangeText={setQuery}
          autoCorrect={false}
          returnKeyType="search"
        />
        <Segmented
          label={t('notes.title')}
          value={tab}
          onChange={setTab}
          options={[
            { value: 'DRAFT', label: t('notes.toReview', { count: drafts.data?.length ?? 0 }) },
            { value: 'SIGNED', label: t('notes.signed') },
          ]}
        />

        {notes.isPending ? (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        ) : notes.isError ? (
          <View style={styles.center}>
            <Text variant="body" color={colors.inkMuted}>
              {t('common.checkConnection')}
            </Text>
            <Button label={t('common.tryAgain')} onPress={() => void notes.refetch()} />
          </View>
        ) : notes.data.length === 0 ? (
          <View style={styles.center}>
            <View style={styles.emptyIcon} aria-hidden>
              <FileText size={28} strokeWidth={1.8} color={colors.brand} />
            </View>
            <Text variant="titleSm" accessibilityRole="header">
              {t('notes.emptyTitle')}
            </Text>
            <Text variant="body" color={colors.inkMuted} style={styles.centerText}>
              {tab === 'DRAFT' ? t('notes.emptyBody') : t('notes.emptySigned')}
            </Text>
          </View>
        ) : (
          notes.data.map((n) => (
            <NoteRow
              key={n.id}
              note={n}
              name={booking(n.bookingId)?.seeker?.label ?? t('notes.seeker')}
              meta={(() => {
                const b = booking(n.bookingId);
                return b
                  ? t('notes.meta', {
                      when: longWhen(b.start, lang),
                      modality: t(`book.modality_${b.modality}`),
                      language: nameOf(b.sessionLanguage),
                      format: n.format,
                    })
                  : n.format;
              })()}
            />
          ))
        )}

        <Text variant="meta" color={colors.inkMuted}>
          {t('notes.retention')}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function NoteRow({ note, name, meta }: { note: NoteSummary; name: string; meta: string }) {
  const { t } = useTranslation();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${name}, ${meta}`}
      onPress={() => router.push({ pathname: '/note/[id]', params: { id: note.id } })}
      style={styles.row}
    >
      <View style={styles.rowTop}>
        <Text variant="label" style={styles.bold}>
          {name}
        </Text>
        {note.status === 'DRAFT' ? <Pill label={t('notes.aiDraft')} tone="warning" /> : null}
      </View>
      <Text variant="meta" color={colors.inkMuted}>
        {meta}
      </Text>
      {note.preview ? (
        <Text variant="body" numberOfLines={2}>
          {note.preview}
        </Text>
      ) : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  content: { padding: space.space20, gap: space.space14 },
  center: { alignItems: 'center', gap: space.space12, paddingVertical: space.space24 },
  centerText: { textAlign: 'center' },
  emptyIcon: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandTint,
  },
  row: {
    gap: space.space4,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  rowTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  bold: { fontWeight: '600' },
});
