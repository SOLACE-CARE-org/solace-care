import { useMutation, useQueryClient } from '@tanstack/react-query';
import { colors, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import {
  accountKeys,
  type Preferences,
  useMe,
  usePreferences,
  useSavePreferences,
  useSessionLanguages,
} from '@/api/account';
import { core, orThrow } from '@/api/core';
import { displayContact } from '@/auth/contact';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { FlowScreen } from '@/ui/FlowScreen';
import { Pill } from '@/ui/Pill';
import { BackButton } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

type Gender = Preferences['specialistGender'];

/**
 * Edit profile (EditProfile.dc.html): the name the specialist sees, session languages and the
 * specialist preference. The phone and email are shown as verified; changing one needs a code to
 * the new contact, which comes with its own flow (D48).
 */
export default function EditProfile() {
  const { t } = useTranslation();
  const language = i18n.language === 'fr' ? 'fr' : 'en';
  const me = useMe();
  const saved = usePreferences();
  const languages = useSessionLanguages(language);
  const savePreferences = useSavePreferences();
  const queries = useQueryClient();
  const saveName = useMutation({
    mutationFn: async (displayName: string) =>
      orThrow(await core.PUT('/v1/me/profile', { body: { displayName } })),
    onSuccess: () => queries.invalidateQueries({ queryKey: accountKeys.me }),
  });

  const [name, setName] = useState<string | null>(null);
  const [sessionLanguages, setSessionLanguages] = useState<string[] | null>(null);
  const [gender, setGender] = useState<Gender | null>(null);
  const shownName = name ?? me.data?.displayName ?? '';
  const shownLanguages = sessionLanguages ?? saved.data?.sessionLanguages ?? [];
  const shownGender: Gender = gender ?? saved.data?.specialistGender ?? 'ANY';

  const busy = saveName.isPending || savePreferences.isPending;
  const ready =
    !busy && shownName.trim().length > 0 && shownLanguages.length > 0 && saved.data !== undefined;
  const submit = async () => {
    if (!saved.data) return;
    try {
      if (shownName.trim() !== me.data?.displayName) await saveName.mutateAsync(shownName.trim());
      await savePreferences.mutateAsync({
        ...saved.data,
        sessionLanguages: shownLanguages,
        specialistGender: shownGender,
      });
      router.back();
    } catch {
      // The error line below says so; the edits stay on screen.
    }
  };

  return (
    <FlowScreen
      top={<BackButton />}
      footer={
        <View style={styles.footer}>
          {saveName.isError || savePreferences.isError ? (
            <Text variant="meta" color={colors.danger}>
              {t('common.checkConnection')}
            </Text>
          ) : null}
          <Button
            label={t('editProfile.save')}
            disabled={!ready}
            accessibilityState={{ disabled: !ready, busy }}
            onPress={() => void submit()}
          />
        </View>
      }
    >
      <Text variant="titleMd" accessibilityRole="header">
        {t('editProfile.title')}
      </Text>
      <TextField
        label={t('editProfile.name')}
        value={shownName}
        maxLength={60}
        onChangeText={setName}
        autoComplete="name"
      />
      {me.data?.phone ? (
        <View style={styles.contact}>
          <View style={styles.flex}>
            <Text variant="label" color={colors.inkMuted}>
              {t('editProfile.phone')}
            </Text>
            <Text variant="body">{displayContact(me.data.phone)}</Text>
          </View>
          <Pill label={t('editProfile.verified')} tone="brand" />
        </View>
      ) : null}

      <Text variant="label" color={colors.inkMuted} style={styles.section}>
        {t('editProfile.languages')}
      </Text>
      <View style={styles.chips}>
        {(languages.data ?? []).map((l) => (
          <Chip
            key={l.code}
            label={l.name}
            on={shownLanguages.includes(l.code)}
            onToggle={() =>
              setSessionLanguages(
                shownLanguages.includes(l.code)
                  ? shownLanguages.filter((c) => c !== l.code)
                  : [...shownLanguages, l.code],
              )
            }
          />
        ))}
      </View>

      <Text variant="label" color={colors.inkMuted} style={styles.section}>
        {t('editProfile.preference')}
      </Text>
      <Segmented
        label={t('editProfile.preference')}
        value={shownGender}
        onChange={setGender}
        options={[
          { value: 'ANY', label: t('editProfile.any') },
          { value: 'WOMAN', label: t('editProfile.woman') },
          { value: 'MAN', label: t('editProfile.man') },
        ]}
      />
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  footer: {
    gap: space.space8,
  },
  contact: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
  },
  flex: {
    flex: 1,
  },
  section: {
    marginTop: space.space8,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space8,
  },
});
