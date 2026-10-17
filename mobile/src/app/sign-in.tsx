import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { CAMEROON_PREFIX, toE164 } from '@/auth/contact';
import { requestCode } from '@/auth/keycloak';
import { Button } from '@/ui/Button';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Segmented } from '@/ui/Segmented';
import { Text } from '@/ui/Text';
import { spacedLocalNumber, TextField } from '@/ui/TextField';


type Channel = 'phone' | 'email';

/** Sign in (SignIn.dc.html): phone or email, then a code. */
export default function SignIn() {
  const { t } = useTranslation();
  const [channel, setChannel] = useState<Channel>('phone');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const [failed, setFailed] = useState(false);

  const contact = channel === 'phone' ? toE164(phone) : email.trim().toLowerCase();
  const valid = channel === 'phone' ? contact !== null : /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim());

  const send = async () => {
    if (!valid || contact === null) return;
    setSending(true);
    setFailed(false);
    try {
      await requestCode(contact);
      router.push({ pathname: '/verify', params: { contact, channel } });
    } catch {
      setFailed(true);
    } finally {
      setSending(false);
    }
  };

  return (
    <FlowScreen top={<BackButton />}>
      <View style={styles.intro}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('signIn.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted} style={styles.lead}>
          {t('signIn.subtitle')}
        </Text>
      </View>
      <Segmented
        label={t('signIn.tabsLabel')}
        value={channel}
        onChange={setChannel}
        options={[
          { value: 'phone', label: t('signIn.phone') },
          { value: 'email', label: t('signIn.email') },
        ]}
      />
      {channel === 'phone' ? (
        <TextField
          label={t('signIn.phoneLabel')}
          prefix={CAMEROON_PREFIX}
          value={spacedLocalNumber(phone)}
          onChangeText={(text) => setPhone(text.replace(/\D/g, '').slice(0, 9))}
          keyboardType="phone-pad"
          textContentType="telephoneNumber"
          autoComplete="tel"
          placeholder="6 77 00 00 00"
          onSubmitEditing={send}
          error={failed ? t('common.checkConnection') : undefined}
        />
      ) : (
        <TextField
          label={t('signIn.emailLabel')}
          value={email}
          onChangeText={setEmail}
          keyboardType="email-address"
          autoCapitalize="none"
          autoComplete="email"
          textContentType="emailAddress"
          placeholder={t('signIn.emailPlaceholder')}
          onSubmitEditing={send}
          error={failed ? t('common.checkConnection') : undefined}
        />
      )}
      <Button
        label={t('signIn.sendCode')}
        disabled={!valid || sending}
        accessibilityState={{ disabled: !valid || sending, busy: sending }}
        onPress={send}
      />
      <View style={styles.spacer} />
      <View style={styles.guest}>
        <Text variant="label" style={styles.guestTitle}>
          {t('signIn.guestTitle')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('signIn.guestBody')}
        </Text>
        <Pressable
          accessibilityRole="link"
          accessibilityLabel={t('signIn.createAccount')}
          onPress={() => router.replace('/role-choice')}
          style={styles.link}
        >
          <Text variant="label" color={colors.brand} style={styles.linkText}>
            {t('signIn.createAccount')}
          </Text>
        </Pressable>
      </View>
    </FlowScreen>
  );
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space8,
  },
  lead: {
    fontSize: 16,
    lineHeight: 24,
  },
  spacer: {
    flexGrow: 1,
  },
  guest: {
    gap: space.space10,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  guestTitle: {
    fontFamily: fontFamily.body[700],
    fontSize: 15,
  },
  link: {
    minHeight: 44,
    justifyContent: 'center',
  },
  linkText: {
    fontFamily: fontFamily.body[700],
    fontSize: 15,
    textDecorationLine: 'underline',
  },
});
