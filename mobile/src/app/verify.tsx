import { colors, fontFamily, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { Mail, Smartphone } from 'lucide-react-native';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { afterSignIn } from '@/auth/afterSignIn';
import { displayContact } from '@/auth/contact';
import { CodeRejected, requestCode, verifyCode } from '@/auth/keycloak';
import { env } from '@/config/env';
import { Button } from '@/ui/Button';
import { CODE_LENGTH, CodeInput, type CodeInputHandle } from '@/ui/CodeInput';
import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

type Problem =
  | { kind: 'wrong'; attemptsLeft: number }
  | { kind: 'newCode' }
  | { kind: 'expired' }
  | { kind: 'network' };

/**
 * Verify code (VerifyCode.dc.html, StateCodeWrong.dc.html). One input drawn as six boxes, so the
 * SMS one-time-code autofill works and screen readers hear a single "6-digit code" field.
 */
export default function Verify() {
  const { t } = useTranslation();
  const { contact, channel } = useLocalSearchParams<{ contact: string; channel: string }>();
  const byEmail = channel === 'email';
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [wait, setWait] = useState(env.codeResendSeconds);
  const input = useRef<CodeInputHandle>(null);

  useEffect(() => {
    if (wait <= 0) return;
    const timer = setTimeout(() => setWait((w) => w - 1), 1000);
    return () => clearTimeout(timer);
  }, [wait]);

  const resend = async () => {
    setProblem(null);
    setCode('');
    try {
      await requestCode(contact);
      setWait(env.codeResendSeconds);
    } catch {
      setProblem({ kind: 'network' });
    }
  };

  const verify = async () => {
    if (code.length !== CODE_LENGTH || busy) return;
    setBusy(true);
    setProblem(null);
    try {
      router.replace(await afterSignIn(await verifyCode(contact, code)));
    } catch (e) {
      setCode('');
      if (e instanceof CodeRejected) {
        if (e.newCodeSent) {
          setProblem({ kind: 'newCode' });
          setWait(env.codeResendSeconds);
        } else if (e.expired) {
          setProblem({ kind: 'expired' });
        } else {
          setProblem({ kind: 'wrong', attemptsLeft: e.attemptsLeft });
        }
      } else {
        setProblem({ kind: 'network' });
      }
      input.current?.focus();
    } finally {
      setBusy(false);
    }
  };

  const Icon = byEmail ? Mail : Smartphone;
  const sent = t(byEmail ? 'verify.sentEmail' : 'verify.sentSms', {
    contact: displayContact(contact),
  });
  const error =
    problem?.kind === 'wrong'
      ? t('verify.wrong', { count: problem.attemptsLeft })
      : problem?.kind === 'network'
        ? t('common.checkConnection')
        : undefined;
  const minutes = Math.floor(wait / 60);
  const seconds = String(wait % 60).padStart(2, '0');

  return (
    <FlowScreen
      top={<BackButton />}
      plainFooter
      footer={
        <Button
          label={t('verify.verify')}
          disabled={code.length !== CODE_LENGTH || busy}
          accessibilityState={{ disabled: code.length !== CODE_LENGTH || busy, busy }}
          onPress={verify}
        />
      }
    >
      <View style={styles.icon} aria-hidden>
        <Icon size={32} strokeWidth={1.8} color={colors.brand} />
      </View>
      <View style={styles.intro}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('verify.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted} style={styles.lead}>
          {sent}
        </Text>
      </View>

      <View style={styles.codeField}>
        <CodeInput
          ref={input}
          label={t('verify.codeLabel')}
          value={code}
          onChange={setCode}
          onSubmit={verify}
          wrong={error !== undefined && problem?.kind === 'wrong'}
          hint={error}
          autoFocus
        />
        {error !== undefined ? (
          <Text
            variant="meta"
            color={colors.danger}
            style={styles.error}
            accessibilityLiveRegion="polite"
          >
            {error}
          </Text>
        ) : null}
        {problem !== null && problem.kind !== 'network' ? (
          <Text variant="meta" color={colors.inkMuted}>
            {t('verify.rules', { tries: env.codeMaxAttempts, minutes: env.codeLifetimeMinutes })}
          </Text>
        ) : null}
      </View>

      <View style={styles.links}>
        {wait > 0 ? (
          <Text variant="body" color={colors.inkMuted} accessibilityLiveRegion="none">
            {t('verify.resendIn', { time: `${minutes}:${seconds}` })}
          </Text>
        ) : (
          <Link label={t('verify.sendNew')} onPress={resend} />
        )}
        <Link
          label={t(byEmail ? 'verify.differentEmail' : 'verify.differentNumber')}
          onPress={() => router.back()}
        />
      </View>
    </FlowScreen>
  );
}

function Link({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={styles.link}
    >
      <Text variant="body" color={colors.brand} style={styles.linkText}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  icon: {
    width: 64,
    height: 64,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandTint,
  },
  intro: {
    gap: space.space8,
  },
  lead: {
    fontSize: 16,
    lineHeight: 24,
  },
  codeField: {
    gap: space.space10,
  },
  error: {
    fontFamily: fontFamily.body[600],
  },
  links: {
    gap: space.space2,
  },
  link: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
    alignSelf: 'flex-start',
  },
  linkText: {
    fontFamily: fontFamily.body[600],
    textDecorationLine: 'underline',
  },
});
