import { colors, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { Image, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import i18n from '@/i18n';
import { type AppLanguage, useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { CrisisButton } from '@/ui/CrisisButton';
import { Text } from '@/ui/Text';

// Main.dc.html values that are not tokens: the wordmark, the language toggle and the illustration.
const WORDMARK = { width: 108, height: 36 };
const TOGGLE = { minWidth: 48, height: 36, inset: 4 };
const HERO_HEIGHT = 230;

/**
 * Welcome: what Solace is, find a specialist without an account, create one, or sign in. The crisis
 * entry point is here for guests, one tap away.
 */
export default function Welcome() {
  const { t } = useTranslation();
  const chooseLanguage = useSession((s) => s.chooseLanguage);
  const current = i18n.language as AppLanguage;

  const switchTo = (language: AppLanguage) => {
    chooseLanguage(language);
    i18n.changeLanguage(language);
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content} bounces={false}>
        <View style={styles.header}>
          <Image
            source={require('../../../design/design-system/assets/Logos/png/solace-care-logo.png')}
            style={WORDMARK}
            resizeMode="contain"
            accessible
            accessibilityRole="image"
            accessibilityLabel={t('common.appName')}
          />
          <View
            style={styles.toggle}
            accessibilityRole="radiogroup"
            accessibilityLabel={t('common.appLanguage')}
          >
            {(['en', 'fr'] as const).map((language) => {
              const on = current === language;
              return (
                <Pressable
                  key={language}
                  accessibilityRole="radio"
                  accessibilityState={{ checked: on }}
                  accessibilityLabel={language === 'en' ? t('common.english') : t('common.french')}
                  // The toggle is 36px high on the screen; the touch area reaches the 44px minimum.
                  hitSlop={(size.sizeTap - TOGGLE.height) / 2 + TOGGLE.inset}
                  onPress={() => switchTo(language)}
                  style={[styles.toggleOption, on && styles.toggleOptionOn]}
                >
                  <Text
                    variant="label"
                    color={on ? colors.onBrand : colors.inkMuted}
                    style={styles.toggleText}
                  >
                    {language.toUpperCase()}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        </View>

        <Hero />

        <View style={styles.intro}>
          <Text variant="displayXl" accessibilityRole="header">
            {t('welcome.title')}
          </Text>
          <Text variant="body" color={colors.inkMuted} style={styles.subtitle}>
            {t('welcome.subtitle')}
          </Text>
        </View>

        <View style={styles.spacer} />

        <View style={styles.actions}>
          <Button
            label={t('welcome.findSpecialist')}
            onboarding
            onPress={() => router.push('/search')}
          />
          <Button
            label={t('welcome.createAccount')}
            variant="secondary"
            onboarding
            onPress={() => router.push('/role-choice')}
          />
          <Text variant="meta" color={colors.inkMuted} style={styles.center}>
            {t('welcome.accountNote')}
          </Text>
          <Button
            label={t('welcome.signIn')}
            variant="quiet"
            onPress={() => router.push('/sign-in')}
          />
        </View>

        <CrisisButton label={t('welcome.crisis')} onPress={() => router.push('/crisis')} />
      </ScrollView>
    </SafeAreaView>
  );
}

/** The decorative conversation illustration from Main.dc.html. Hidden from screen readers. */
function Hero() {
  return (
    <View style={hero.frame} aria-hidden>
      <View style={hero.disc} />
      <View style={[hero.bubble, hero.bubbleLeft]}>
        <View style={[hero.line, { width: 110, backgroundColor: colors.brandTintStrong }]} />
        <View style={[hero.line, { width: 74, backgroundColor: colors.brandTintStrong }]} />
      </View>
      <View style={[hero.bubble, hero.bubbleRight]}>
        <View style={[hero.line, { width: 96, backgroundColor: colors.accent }]} />
        <View style={[hero.line, { width: 60, backgroundColor: colors.brandLineOnDark }]} />
      </View>
      <View style={hero.dot} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    flexGrow: 1,
    paddingTop: space.space28,
    paddingHorizontal: space.space24,
    paddingBottom: space.space32,
    gap: space.space24,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  toggle: {
    flexDirection: 'row',
    gap: space.space2,
    padding: TOGGLE.inset,
    borderRadius: radius.radiusPill,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  toggleOption: {
    minWidth: TOGGLE.minWidth,
    height: TOGGLE.height,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.radiusPill,
  },
  toggleOptionOn: {
    backgroundColor: colors.brand,
  },
  toggleText: {
    fontSize: 14,
  },
  intro: {
    gap: space.space12,
  },
  // 16px with a 1.5 line height on the screen.
  subtitle: {
    fontSize: 16,
    lineHeight: 24,
  },
  spacer: {
    flexGrow: 1,
  },
  actions: {
    gap: space.space12,
  },
  center: {
    textAlign: 'center',
  },
});

// Illustration geometry, copied from Main.dc.html.
const hero = StyleSheet.create({
  frame: {
    height: HERO_HEIGHT,
    borderRadius: radius.radius28,
    backgroundColor: colors.brandTint,
    overflow: 'hidden',
  },
  disc: {
    position: 'absolute',
    width: 220,
    height: 220,
    borderRadius: 110,
    right: -60,
    top: -70,
    backgroundColor: colors.brandTintStrong,
  },
  bubble: {
    position: 'absolute',
    height: 64,
    justifyContent: 'center',
    gap: space.space8,
    paddingHorizontal: space.space18,
  },
  bubbleLeft: {
    left: 28,
    top: 44,
    width: 170,
    backgroundColor: colors.surfaceCard,
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    borderBottomRightRadius: 24,
    borderBottomLeftRadius: 6,
  },
  bubbleRight: {
    right: 28,
    top: 124,
    width: 160,
    backgroundColor: colors.brand,
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    borderBottomRightRadius: 6,
    borderBottomLeftRadius: 24,
  },
  line: {
    height: 8,
    borderRadius: 4,
  },
  dot: {
    position: 'absolute',
    left: 40,
    bottom: 26,
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: colors.accent,
  },
});
