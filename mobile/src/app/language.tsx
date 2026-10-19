import { colors, fontFamily, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Image, Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import i18n, { deviceLanguage } from '@/i18n';
import { type AppLanguage, useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { spokenIn } from '@/ui/lang';
import { Text } from '@/ui/Text';

// Language.dc.html values that are not tokens: card radius and height, radio size and ring.
const CARD = { radius: 20, minHeight: 76, paddingV: 14, paddingH: 18 };
const RADIO = { size: 22, ringOn: 7, ringOff: 2 };
const MARK = { width: 54, height: 44 };
const TOP = 56;
/** The language names, shown in their own language. */
const NAME_SIZE = 21;

/**
 * Choose the app language on first launch. Picking a language switches the screen to it at once;
 * Continue saves it. The title is always shown in both languages.
 */
export default function Language() {
  const { t } = useTranslation();
  const chooseLanguage = useSession((s) => s.chooseLanguage);
  const [selected, setSelected] = useState<AppLanguage>(
    (i18n.language as AppLanguage) ?? deviceLanguage(),
  );

  const select = (language: AppLanguage) => {
    setSelected(language);
    i18n.changeLanguage(language);
  };

  return (
    <SafeAreaView style={styles.screen} edges={['bottom']}>
      <View style={styles.content}>
        <Image
          source={require('../../../design/design-system/assets/Logos/png/solace-care-mark.png')}
          style={MARK}
          resizeMode="contain"
          accessible={false}
        />
        <View style={styles.heading}>
          <Text variant="titleLg" accessibilityRole="header" {...spokenIn('en')}>
            {t('language.title')}
          </Text>
          <Text variant="heading" color={colors.inkMuted} {...spokenIn('fr')}>
            {t('language.titleOther')}
          </Text>
        </View>

        <View
          style={styles.options}
          accessibilityRole="radiogroup"
          accessibilityLabel={t('common.appLanguage')}
        >
          <LanguageOption
            name={t('common.english')}
            meta={t('language.englishMeta')}
            selected={selected === 'en'}
            onPress={() => select('en')}
            lang="en"
          />
          <LanguageOption
            name={t('common.french')}
            meta={t('language.frenchMeta')}
            selected={selected === 'fr'}
            onPress={() => select('fr')}
            lang="fr"
          />
        </View>

        <Text variant="label" color={colors.inkMuted} style={styles.note}>
          {t('language.note')}
        </Text>
      </View>

      <Button
        label={t('language.continue')}
        onboarding
        style={styles.continue}
        onPress={() => {
          chooseLanguage(selected);
          router.replace('/intro');
        }}
      />
    </SafeAreaView>
  );
}

function LanguageOption(props: {
  name: string;
  meta: string;
  selected: boolean;
  onPress: () => void;
  lang: AppLanguage;
}) {
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ checked: props.selected }}
      accessibilityLabel={`${props.name}. ${props.meta}`}
      {...spokenIn(props.lang)}
      onPress={props.onPress}
      style={[styles.card, props.selected ? styles.cardOn : styles.cardOff]}
    >
      <View style={styles.cardText}>
        <Text variant="heading" style={styles.name}>
          {props.name}
        </Text>
        <Text variant="label" color={colors.inkMuted} style={styles.meta}>
          {props.meta}
        </Text>
      </View>
      <View style={[styles.radio, props.selected ? styles.radioOn : styles.radioOff]} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
    paddingHorizontal: space.space24,
    paddingBottom: space.space32,
  },
  content: {
    flex: 1,
    paddingTop: TOP,
    gap: space.space28,
  },
  heading: {
    gap: space.space6,
  },
  options: {
    gap: space.space12,
  },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space.space12,
    minHeight: CARD.minHeight,
    paddingVertical: CARD.paddingV,
    paddingHorizontal: CARD.paddingH,
    borderRadius: CARD.radius,
    backgroundColor: colors.surfaceCard,
  },
  // The selected card's border grows by 1px; padding shrinks by 1px so nothing shifts.
  cardOn: {
    borderWidth: 2,
    borderColor: colors.brand,
    paddingVertical: CARD.paddingV - 1,
    paddingHorizontal: CARD.paddingH - 1,
  },
  cardOff: {
    borderWidth: 1,
    borderColor: colors.lineControl,
  },
  cardText: {
    flexShrink: 1,
    gap: space.space2,
  },
  name: {
    fontSize: NAME_SIZE,
  },
  meta: {
    fontFamily: fontFamily.body[400],
  },
  radio: {
    width: RADIO.size,
    height: RADIO.size,
    borderRadius: RADIO.size / 2,
    backgroundColor: colors.surfaceCard,
  },
  radioOn: {
    borderWidth: RADIO.ringOn,
    borderColor: colors.brand,
  },
  radioOff: {
    borderWidth: RADIO.ringOff,
    borderColor: colors.inkFaint,
  },
  // 14.5px with a 1.5 line height on the screen.
  note: {
    fontFamily: fontFamily.body[400],
    lineHeight: 14.5 * 1.5,
  },
  continue: {
    alignSelf: 'stretch',
  },
});
