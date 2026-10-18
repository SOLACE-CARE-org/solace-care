import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { File, Paths } from 'expo-file-system';
import { router } from 'expo-router';
import * as Sharing from 'expo-sharing';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useConsents, useSaveConsents } from '@/api/account';
import { ApiProblem } from '@/api/core';
import { downloadExport, useEraseAccount, useLatestExport, useRequestExport } from '@/api/privacy';
import i18n from '@/i18n';
import { signOut } from '@/notifications/push';
import { useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { SwitchRow } from '@/ui/SwitchRow';
import { Text } from '@/ui/Text';

/**
 * Privacy & data (PrivacyCentre.dc.html; D40): the purposes this release asks about, a copy of
 * one's data, and deleting the account. Only the purposes Core offers are shown.
 */
export default function PrivacyCentre() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const consents = useConsents();
  const saveConsents = useSaveConsents();
  const latest = useLatestExport();
  const request = useRequestExport();
  const erase = useEraseAccount();
  const [note, setNote] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const copy = latest.data;

  const say = (e: unknown) =>
    setNote(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));

  const saveCopy = async () => {
    if (!copy) return;
    setSaving(true);
    try {
      const text = await downloadExport(copy.id);
      const file = new File(Paths.cache, 'solace-data.json');
      if (file.exists) file.delete();
      file.write(text);
      await Sharing.shareAsync(file.uri, { mimeType: 'application/json' });
    } catch (e) {
      say(e);
    } finally {
      setSaving(false);
    }
  };

  const confirmErase = () =>
    Alert.alert(t('privacy.confirmTitle'), t('privacy.confirmBody'), [
      { text: t('privacy.cancel'), style: 'cancel' },
      {
        text: t('privacy.confirmDelete'),
        style: 'destructive',
        onPress: () =>
          erase.mutate(undefined, {
            onSuccess: async (result) => {
              if (result.outcome === 'ERASED') {
                await signOut();
                useSession.getState().signedOut();
                router.replace('/welcome');
              } else {
                setNote(t('privacy.requested'));
              }
            },
            onError: say,
          }),
      },
    ]);

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.titleRow}>
          <BackButton />
          <Text variant="titleMd" accessibilityRole="header">
            {t('privacy.title')}
          </Text>
        </View>

        <Text
          variant="meta"
          color={colors.inkMuted}
          style={styles.section}
          accessibilityRole="header"
        >
          {t('privacy.what').toUpperCase()}
        </Text>
        {consents.data ? (
          <View style={styles.card}>
            {consents.data.consents.map((c) => (
              <SwitchRow
                key={c.purpose}
                title={t(`privacy.${c.purpose}`)}
                on={c.granted}
                onToggle={() =>
                  saveConsents.mutate([{ purpose: c.purpose, granted: !c.granted }], {
                    onError: say,
                  })
                }
              />
            ))}
          </View>
        ) : (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        )}

        <Text
          variant="meta"
          color={colors.inkMuted}
          style={styles.section}
          accessibilityRole="header"
        >
          {t('privacy.yourData').toUpperCase()}
        </Text>
        <View style={styles.card}>
          <Row
            label={t('privacy.download')}
            busy={request.isPending}
            onPress={() => request.mutate(undefined, { onError: say })}
          />
          {copy?.status === 'PREPARING' ? (
            <Text
              variant="meta"
              color={colors.inkMuted}
              style={styles.status}
              accessibilityLiveRegion="polite"
            >
              {t('privacy.preparing')}
            </Text>
          ) : null}
          {copy?.status === 'READY' && copy.expiresAt ? (
            <View style={styles.status}>
              <Text variant="meta" color={colors.inkMuted}>
                {t('privacy.ready', {
                  date: new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'long' }).format(
                    new Date(copy.expiresAt),
                  ),
                })}
              </Text>
              <Button
                variant="secondary"
                label={t('privacy.save')}
                disabled={saving}
                accessibilityState={{ disabled: saving, busy: saving }}
                onPress={saveCopy}
              />
            </View>
          ) : null}
          <Row label={t('privacy.notice')} onPress={() => router.push('/legal/privacy')} divider />
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('privacy.delete')}
          accessibilityHint={t('privacy.deleteNote')}
          disabled={erase.isPending}
          onPress={confirmErase}
          style={styles.delete}
        >
          <Text variant="label" color={colors.danger} style={styles.bold}>
            {t('privacy.delete')}
          </Text>
        </Pressable>
        <Text variant="meta" color={colors.inkMuted}>
          {t('privacy.deleteNote')}
        </Text>
        {note ? (
          <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
            {note}
          </Text>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function Row({
  label,
  onPress,
  busy = false,
  divider = false,
}: {
  label: string;
  onPress: () => void;
  busy?: boolean;
  divider?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ busy }}
      onPress={onPress}
      style={[styles.row, divider && styles.divider]}
    >
      <Text variant="label" style={styles.bold}>
        {label}
      </Text>
      {busy ? <ActivityIndicator color={colors.brand} /> : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space12,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  section: {
    marginTop: space.space8,
    fontFamily: fontFamily.body[700],
    letterSpacing: 0.5,
  },
  card: {
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
    paddingHorizontal: space.space14,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 56,
  },
  divider: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  status: {
    gap: space.space8,
    paddingBottom: space.space12,
  },
  delete: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
    marginTop: space.space8,
  },
  bold: {
    fontFamily: fontFamily.body[600],
  },
});
