import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Check } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiProblem } from '@/api/core';
import { useMe } from '@/api/account';
import { useApplication, useConfirmBio, useWeeklyHours } from '@/api/specialist';
import { signOut as signOutEverywhere } from '@/notifications/push';
import { initials } from '@/state/format';
import { useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

/**
 * Profile tab (SpecProfile.dc.html). Editing each row arrives with profile editing; for now the
 * rows summarise, and the specialist checks the bio Solace drafted in their other language (D30)
 * before search lists them.
 */
export default function SpecialistProfileTab() {
  const { t } = useTranslation();
  const me = useMe();
  const application = useApplication();
  const hours = useWeeklyHours();
  const confirm = useConfirmBio();
  const [draft, setDraft] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const a = application.data;
  const pending = a?.draftedBio && !a.draftedBio.confirmed ? a.draftedBio : null;
  const languageName = (code: string) => t(`specialistProfileTab.language_${code}`);

  const services = (a?.offerings ?? [])
    .filter((o) => o.enabled)
    .map((o) => t(`search.${o.modality}`))
    .join(', ');
  const days = (hours.data?.days ?? []).filter((d) => d.ranges.length > 0).length;
  const languages = (a?.languages ?? []).map((l) => l.code.toUpperCase()).join(', ');
  const payout = a?.payout ? t(`specialistSignup.payout.${a.payout.method}`) : '';

  const signOut = async () => {
    await signOutEverywhere();
    useSession.getState().signedOut();
    router.replace('/welcome');
  };

  const confirmDraft = () => {
    setProblem(null);
    const corrected = draft === null || draft.trim() === pending?.text ? undefined : draft.trim();
    confirm.mutate(corrected, {
      onSuccess: () => setDraft(null),
      onError: (e) =>
        setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
    });
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.head}>
          <View style={styles.avatar} aria-hidden>
            <Text style={styles.avatarText}>{initials(a?.fullName ?? '')}</Text>
          </View>
          <View style={styles.who}>
            <Text variant="titleSm" accessibilityRole="header">
              {a?.fullName}
            </Text>
            <View style={styles.badge}>
              <Check size={14} strokeWidth={2.4} color={colors.brandDeep} aria-hidden />
              <Text variant="meta" color={colors.brandDeep} style={styles.strong}>
                {pending ? t('specialistProfileTab.verified') : t('specialistProfileTab.live')}
              </Text>
            </View>
          </View>
        </View>

        {pending ? (
          <View style={styles.notice} accessibilityLiveRegion="polite">
            <Text variant="label" accessibilityRole="header">
              {t('specialistProfileTab.draftTitle', { language: languageName(pending.language) })}
            </Text>
            <Text variant="meta" color={colors.inkSoft}>
              {t('specialistProfileTab.draftBody')}
            </Text>
            <TextField
              label={t('specialistProfileTab.draftLabel', {
                language: languageName(pending.language),
              })}
              value={draft ?? pending.text}
              onChangeText={setDraft}
              multiline
              maxLength={600}
            />
            {problem ? (
              <Text variant="meta" color={colors.danger}>
                {problem}
              </Text>
            ) : null}
            <Button
              label={t('specialistProfileTab.draftConfirm')}
              disabled={confirm.isPending || (draft !== null && draft.trim() === '')}
              accessibilityState={{ busy: confirm.isPending }}
              onPress={confirmDraft}
            />
          </View>
        ) : null}

        {me.data ? (
          <Button
            variant="secondary"
            label={t('specialistProfileTab.preview')}
            onPress={() => router.push(`/specialist/${me.data.id}`)}
          />
        ) : null}

        <View style={styles.card}>
          <Row label={t('specialistProfileTab.services')} value={services} />
          <Row
            label={t('specialistProfileTab.hours')}
            value={
              days === 1
                ? t('specialistProfileTab.dayOne')
                : t('specialistProfileTab.days', { count: days })
            }
            divider
          />
          <Row label={t('specialistProfileTab.languages')} value={languages} divider />
        </View>
        <View style={styles.card}>
          <Row label={t('specialistProfileTab.payout')} value={payout} />
        </View>
        <Button
          variant="secondary"
          label={t('reviews.title')}
          onPress={() => router.push('/reviews')}
        />
        <Button
          variant="secondary"
          label={t('you.notifications')}
          onPress={() => router.push('/notifications')}
        />
        <Button
          variant="secondary"
          label={t('you.settings')}
          onPress={() => router.push('/settings')}
        />
        <Button
          variant="secondary"
          label={t('you.privacy')}
          onPress={() => router.push('/privacy')}
        />

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('specialistProfileTab.signOut')}
          onPress={signOut}
          style={styles.signOut}
        >
          <Text variant="label" color={colors.danger}>
            {t('specialistProfileTab.signOut')}
          </Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

function Row({
  label,
  value,
  divider = false,
}: {
  label: string;
  value: string;
  divider?: boolean;
}) {
  return (
    <View
      style={[styles.row, divider && styles.divider]}
      accessible
      accessibilityLabel={`${label}: ${value}`}
    >
      <Text variant="label" style={styles.rowLabel}>
        {label}
      </Text>
      <Text variant="meta" color={colors.inkMuted}>
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space14,
  },
  avatar: {
    width: 64,
    height: 64,
    borderRadius: radius.radiusPill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.accent,
  },
  avatarText: {
    fontFamily: fontFamily.display[700],
    fontSize: 22,
    color: colors.onAccent,
  },
  who: {
    flex: 1,
    gap: space.space6,
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    gap: space.space4,
    paddingHorizontal: space.space10,
    paddingVertical: space.space2,
    borderRadius: radius.radiusPill,
    backgroundColor: colors.brandTint,
  },
  strong: {
    fontFamily: fontFamily.body[700],
  },
  notice: {
    gap: space.space10,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.accent,
    backgroundColor: colors.warningTint,
  },
  card: {
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: size.sizeTap + 8,
    paddingHorizontal: space.space16,
  },
  divider: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  rowLabel: {
    fontSize: 15,
  },
  signOut: {
    minHeight: size.sizeTap,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
