import { colors, fontFamily } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { Image, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Text } from '@/ui/Text';

// Splash.dc.html: the lockup and two decorative discs, sized and placed as on the screen.
//
// STAND-IN: the splash lockup (solace-care-splash.svg in the Logos README) is not in the bundle.
// The stacked logo that is supplied is drawn in brand-splash itself, so it vanishes here. Until the
// lockup arrives, the app icon stands in: a light mark on exactly the brand-splash ground. Swap the
// file and LOCKUP size when the real lockup is added; nothing else changes.
const LOCKUP = { width: 200, height: 200 };
const DISC_TOP = { size: 340, right: -150, top: -120 };
const DISC_BOTTOM = { size: 260, left: -130, bottom: -70 };
const LANGUAGES_BOTTOM = 56;

/** First launch: the splash lockup on brand-splash. Tap to continue to the language choice. */
export default function Splash() {
  const { t } = useTranslation();
  const insets = useSafeAreaInsets();

  return (
    <Pressable
      style={styles.screen}
      accessibilityRole="link"
      accessibilityLabel={t('splash.a11yLabel')}
      onPress={() => router.replace('/language')}
    >
      <View style={[styles.disc, styles.discTop]} aria-hidden />
      <View style={[styles.disc, styles.discBottom]} aria-hidden />
      <Image
        source={require('../../../design/design-system/assets/Logos/png/solace-care-app-icon-512.png')}
        style={LOCKUP}
        resizeMode="contain"
        accessible={false}
      />
      <View style={[styles.languages, { bottom: LANGUAGES_BOTTOM + insets.bottom }]}>
        <Text variant="body" color={colors.onSplashMuted} style={styles.languagesText}>
          {t('splash.languages')}
        </Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    backgroundColor: colors.brandSplash,
  },
  disc: {
    position: 'absolute',
    borderRadius: 9999,
    backgroundColor: colors.brandSplashRing,
  },
  discTop: {
    width: DISC_TOP.size,
    height: DISC_TOP.size,
    right: DISC_TOP.right,
    top: DISC_TOP.top,
  },
  discBottom: {
    width: DISC_BOTTOM.size,
    height: DISC_BOTTOM.size,
    left: DISC_BOTTOM.left,
    bottom: DISC_BOTTOM.bottom,
  },
  languages: {
    position: 'absolute',
    left: 0,
    right: 0,
  },
  // 14px, weight 500 on the screen.
  languagesText: {
    fontFamily: fontFamily.body[500],
    fontSize: 14,
    textAlign: 'center',
  },
});
