import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Phone, Wind, X } from 'lucide-react-native';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Animated, Easing, Linking, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { flags } from '@/config/flags';
import { EMERGENCY_NUMBERS } from '@/content/crisis';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';

/** One breath: 4 seconds in, 6 out (a slow one-minute exercise, 6 breaths). */
const IN_MS = 4_000;
const OUT_MS = 6_000;
const BREATHS = 6;

/**
 * Urgent help (Crisis.dc.html). Works for guests and with no connection: the numbers are in the app
 * and nothing here calls Solace's servers (System Design, Crisis button; AI-4).
 */
export default function UrgentHelp() {
  const { t } = useTranslation();
  const [breathing, setBreathing] = useState(false);

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.top}>
          <Text variant="titleLg" accessibilityRole="header" style={styles.flex}>
            {t('crisis.title')}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('crisis.close')}
            onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))}
            style={styles.close}
          >
            <X size={24} strokeWidth={2} color={colors.ink} aria-hidden />
          </Pressable>
        </View>
        <Text variant="titleSm">{t('crisis.lead')}</Text>
        <Text variant="body" color={colors.inkSoft} style={styles.lead}>
          {t('crisis.body')}
        </Text>

        {EMERGENCY_NUMBERS.map((n) => (
          <Pressable
            key={n.number}
            accessibilityRole="button"
            accessibilityLabel={`${t('crisis.call', { number: n.number })}, ${t(`crisis.${n.service}`)}`}
            onPress={() => void Linking.openURL(`tel:${n.number}`)}
            style={[styles.option, styles.call]}
          >
            <Phone size={22} strokeWidth={1.8} color={colors.onBrand} aria-hidden />
            <View style={styles.flex}>
              <Text variant="label" color={colors.onBrand} style={styles.bold}>
                {t('crisis.call', { number: n.number })}
              </Text>
              <Text variant="meta" color={colors.onBrand}>
                {t(`crisis.${n.service}`)} · {t('crisis.anyTime')}
              </Text>
            </View>
          </Pressable>
        ))}

        {flags.onCallSpecialistCall ? (
          <View style={styles.option}>
            <View style={styles.flex}>
              <Text variant="label" style={styles.bold}>
                {t('crisis.onCall')}
              </Text>
              <Text variant="meta" color={colors.inkMuted}>
                {t('crisis.onCallMeta')}
              </Text>
            </View>
          </View>
        ) : null}

        <View style={[styles.option, styles.column]}>
          <View style={styles.row}>
            <Wind size={22} strokeWidth={1.8} color={colors.brand} aria-hidden />
            <View style={styles.flex}>
              <Text variant="label" style={styles.bold}>
                {t('crisis.breathe')}
              </Text>
              <Text variant="meta" color={colors.inkMuted}>
                {t('crisis.breatheMeta')}
              </Text>
            </View>
            {!breathing ? (
              <Button
                variant="secondary"
                label={t('crisis.start')}
                onPress={() => setBreathing(true)}
              />
            ) : null}
          </View>
          {breathing ? <Breathing /> : null}
        </View>

        <Text variant="meta" color={colors.inkMuted} style={styles.offline}>
          {t('crisis.offline')}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

/** A circle that grows as you breathe in and shrinks as you breathe out, six times. */
function Breathing() {
  const { t } = useTranslation();
  const [scale] = useState(() => new Animated.Value(0.6));
  const [phase, setPhase] = useState<'in' | 'out' | 'done'>('in');
  const [run, setRun] = useState(0);

  useEffect(() => {
    let breath = 0;
    let stopped = false;
    const step = () => {
      if (stopped) return;
      if (breath >= BREATHS) {
        setPhase('done');
        return;
      }
      setPhase('in');
      Animated.timing(scale, {
        toValue: 1,
        duration: IN_MS,
        easing: Easing.inOut(Easing.ease),
        useNativeDriver: true,
      }).start(() => {
        if (stopped) return;
        setPhase('out');
        Animated.timing(scale, {
          toValue: 0.6,
          duration: OUT_MS,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }).start(() => {
          breath += 1;
          step();
        });
      });
    };
    step();
    return () => {
      stopped = true;
      scale.stopAnimation();
    };
  }, [run, scale]);

  return (
    <View style={styles.breathing}>
      <Animated.View style={[styles.circle, { transform: [{ scale }] }]} aria-hidden />
      <Text variant="titleSm" accessibilityLiveRegion="polite" style={styles.centerText}>
        {phase === 'in'
          ? t('crisis.breatheIn')
          : phase === 'out'
            ? t('crisis.breatheOut')
            : t('crisis.breatheDone')}
      </Text>
      {phase === 'done' ? (
        <Button variant="quiet" label={t('crisis.again')} onPress={() => setRun((r) => r + 1)} />
      ) : null}
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
    gap: space.space14,
  },
  top: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  close: {
    width: size.sizeTap,
    height: size.sizeTap,
    alignItems: 'center',
    justifyContent: 'center',
  },
  lead: {
    fontSize: 16,
    lineHeight: 24,
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    minHeight: 64,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  call: {
    borderColor: colors.dangerStrong,
    backgroundColor: colors.dangerStrong,
  },
  column: {
    flexDirection: 'column',
    alignItems: 'stretch',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
  },
  breathing: {
    alignItems: 'center',
    gap: space.space16,
    paddingVertical: space.space16,
  },
  circle: {
    width: 140,
    height: 140,
    borderRadius: 70,
    backgroundColor: colors.brandTint,
    borderWidth: 2,
    borderColor: colors.brand,
  },
  centerText: {
    textAlign: 'center',
  },
  offline: {
    textAlign: 'center',
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
});
