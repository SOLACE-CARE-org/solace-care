import { colors, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Check } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { startSignup } from '@/api/account';
import { ApiProblem } from '@/api/core';
import { CAMEROON_PREFIX, toE164 } from '@/auth/contact';
import { requestCode } from '@/auth/keycloak';
import { env } from '@/config/env';
import i18n from '@/i18n';
import { age, maskDate, parseDate, signupSteps } from '@/state/signup';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { ProgressSteps } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { spacedLocalNumber, TextField } from '@/ui/TextField';

const MINIMUM_AGE = 18;

/** Seeker sign-up, step 1 (SeekerSignup.dc.html). */
export default function SignupDetails() {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [dob, setDob] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [tooYoung, setTooYoung] = useState(false);

  const e164 = toE164(phone);
  const birthDate = parseDate(dob);
  const emailOk = email.trim() === '' || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim());
  const ready = name.trim() !== '' && e164 !== null && birthDate !== null && emailOk && agreed;

  const submit = async () => {
    if (!ready || busy || e164 === null || birthDate === null) return;
    if (age(birthDate) < MINIMUM_AGE) {
      setTooYoung(true);
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      await startSignup({
        displayName: name.trim(),
        phone: e164,
        email: email.trim() === '' ? undefined : email.trim(),
        dateOfBirth: birthDate,
        termsVersion: env.termsVersion,
        privacyNoticeVersion: env.privacyNoticeVersion,
        appLanguage: i18n.language === 'fr' ? 'fr' : 'en',
      });
      await requestCode(e164);
      router.push({ pathname: '/verify', params: { contact: e164, channel: 'phone' } });
    } catch (e) {
      if (e instanceof ApiProblem && e.code === 'account.too-young') setTooYoung(true);
      else setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <FlowScreen
      top={<ProgressSteps step={1} total={signupSteps()} />}
      footer={
        <Button
          label={t('signupDetails.submit')}
          disabled={!ready || busy}
          accessibilityState={{ disabled: !ready || busy, busy }}
          onPress={submit}
        />
      }
    >
      <View style={styles.intro}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('signupDetails.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('signupDetails.subtitle')}
        </Text>
      </View>
      <TextField
        label={t('signupDetails.nameLabel')}
        help={t('signupDetails.nameHelp')}
        value={name}
        onChangeText={setName}
        maxLength={60}
        autoComplete="given-name"
        textContentType="givenName"
      />
      <TextField
        label={t('signupDetails.phoneLabel')}
        prefix={CAMEROON_PREFIX}
        value={spacedLocalNumber(phone)}
        onChangeText={(text) => setPhone(text.replace(/\D/g, '').slice(0, 9))}
        keyboardType="phone-pad"
        autoComplete="tel"
        textContentType="telephoneNumber"
        placeholder="6 77 00 00 00"
      />
      <TextField
        label={t('signupDetails.emailLabel')}
        value={email}
        onChangeText={setEmail}
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        textContentType="emailAddress"
        placeholder={t('signupDetails.emailPlaceholder')}
      />
      <TextField
        label={t('signupDetails.dobLabel')}
        value={dob}
        onChangeText={(text) => {
          setTooYoung(false);
          setDob(maskDate(text));
        }}
        keyboardType="number-pad"
        placeholder={t('signupDetails.dobPlaceholder')}
        help={tooYoung ? undefined : t('signupDetails.dobHelp')}
        error={tooYoung ? t('signupDetails.dobHelp') : undefined}
      />
      <View style={styles.agree}>
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{ checked: agreed }}
          accessibilityLabel={`${t('signupDetails.agreeBefore')}${t('signupDetails.terms')}${t('signupDetails.agreeMiddle')}${t('signupDetails.privacyNotice')}`}
          onPress={() => setAgreed((a) => !a)}
          style={styles.checkTarget}
        >
          <View style={[styles.checkbox, agreed && styles.checkboxOn]}>
            {agreed ? <Check size={16} strokeWidth={3} color={colors.onBrand} /> : null}
          </View>
        </Pressable>
        <Text variant="body" color={colors.inkSoft} style={styles.agreeText}>
          {t('signupDetails.agreeBefore')}
          <Text
            variant="body"
            color={colors.brand}
            accessibilityRole="link"
            onPress={() => router.push('/legal/terms')}
            style={styles.inlineLink}
          >
            {t('signupDetails.terms')}
          </Text>
          {t('signupDetails.agreeMiddle')}
          <Text
            variant="body"
            color={colors.brand}
            accessibilityRole="link"
            onPress={() => router.push('/legal/privacy')}
            style={styles.inlineLink}
          >
            {t('signupDetails.privacyNotice')}
          </Text>
          {t('signupDetails.agreeAfter')}
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
