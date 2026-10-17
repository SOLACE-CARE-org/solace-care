import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Check } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { ApiProblem } from '@/api/core';
import { SPECIALIST_STEPS, startSpecialistSignup } from '@/api/specialist';
import { CAMEROON_PREFIX, toE164 } from '@/auth/contact';
import { requestCode } from '@/auth/keycloak';
import { env } from '@/config/env';
import i18n from '@/i18n';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { spacedLocalNumber, TextField } from '@/ui/TextField';

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/**
 * Specialist application, step 1 of 7 (SpecSignup.dc.html). No password: specialists sign in with
 * a code like seekers (D24), so the next screen is Verify code.
 */
export default function SpecialistAccount() {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const e164 = toE164(phone);
  const ready = name.trim() !== '' && EMAIL.test(email.trim()) && e164 !== null && agreed;

  const submit = async () => {
    if (!ready || busy || e164 === null) return;
    setBusy(true);
    setProblem(null);
    try {
      await startSpecialistSignup({
        fullName: name.trim(),
        phone: e164,
        email: email.trim(),
        termsVersion: env.specialistTermsVersion,
        codeOfConductVersion: env.codeOfConductVersion,
        appLanguage: i18n.language === 'fr' ? 'fr' : 'en',
      });
      await requestCode(e164);
      router.push({ pathname: '/verify', params: { contact: e164, channel: 'phone' } });
    } catch (e) {
      setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <FlowScreen
      top={<ProgressSteps step={1} total={SPECIALIST_STEPS} />}
      footer={
        <Button
          label={t('specialistSignup.account.submit')}
          disabled={!ready || busy}
          accessibilityState={{ disabled: !ready || busy, busy }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="meta" color={colors.warningInk} style={styles.eyebrow}>
          {t('specialistSignup.eyebrow')}
        </Text>
        <Text variant="titleMd" accessibilityRole="header">
          {t('specialistSignup.account.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('specialistSignup.account.subtitle')}
        </Text>
      </View>
      <TextField
        label={t('specialistSignup.account.nameLabel')}
        value={name}
        onChangeText={setName}
        maxLength={120}
        autoComplete="name"
        textContentType="name"
      />
      <TextField
        label={t('specialistSignup.account.emailLabel')}
        value={email}
        onChangeText={setEmail}
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        textContentType="emailAddress"
      />
      <TextField
        label={t('specialistSignup.account.phoneLabel')}
        prefix={CAMEROON_PREFIX}
        value={spacedLocalNumber(phone)}
        onChangeText={(text) => setPhone(text.replace(/\D/g, '').slice(0, 9))}
        keyboardType="phone-pad"
        autoComplete="tel"
        textContentType="telephoneNumber"
        placeholder="6 99 00 00 00"
      />
      <View style={styles.agree}>
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{ checked: agreed }}
          accessibilityLabel={`${t('specialistSignup.account.agreeBefore')}${t('specialistSignup.account.terms')}${t('specialistSignup.account.agreeMiddle')}${t('specialistSignup.account.conduct')}`}
          onPress={() => setAgreed((a) => !a)}
          style={styles.checkTarget}
        >
          <View style={[styles.checkbox, agreed && styles.checkboxOn]}>
            {agreed ? <Check size={16} strokeWidth={3} color={colors.onBrand} /> : null}
          </View>
        </Pressable>
        <Text variant="body" color={colors.inkSoft} style={styles.agreeText}>
          {t('specialistSignup.account.agreeBefore')}
          <Text
            variant="body"
            color={colors.brand}
            accessibilityRole="link"
            onPress={() => router.push('/legal/specialist-terms')}
            style={styles.inlineLink}
          >
            {t('specialistSignup.account.terms')}
          </Text>
          {t('specialistSignup.account.agreeMiddle')}
          <Text
            variant="body"
            color={colors.brand}
            accessibilityRole="link"
            onPress={() => router.push('/legal/code-of-conduct')}
            style={styles.inlineLink}
          >
            {t('specialistSignup.account.conduct')}
          </Text>
          {t('specialistSignup.account.agreeAfter')}
        </Text>
      </View>
      {problem ? (
        <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
          {problem}
        </Text>
      ) : null}
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space6,
  },
  eyebrow: {
    fontFamily: fontFamily.body[700],
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  agree: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: space.space2,
  },
  checkTarget: {
    width: size.sizeTap,
    height: size.sizeTap,
    marginLeft: -space.space10,
    marginTop: -space.space10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkbox: {
    width: 22,
    height: 22,
    borderRadius: radius.radius6,
    borderWidth: 2,
    borderColor: colors.lineControl,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceCard,
  },
  checkboxOn: {
    borderColor: colors.brand,
    backgroundColor: colors.brand,
  },
  agreeText: {
    flex: 1,
    fontSize: 14,
  },
  inlineLink: {
    fontSize: 14,
    textDecorationLine: 'underline',
  },
});
