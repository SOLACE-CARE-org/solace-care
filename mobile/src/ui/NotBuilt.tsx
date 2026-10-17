import { colors, space } from '@solace/tokens/native';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { Text } from './Text';

/**
 * Development stand-in for screens that later epics build. Never shipped: every route using it is
 * replaced before the pilot.
 */
export function NotBuilt() {
  const { t } = useTranslation();
  return (
    <View style={styles.screen}>
      <Text variant="body" color={colors.inkMuted}>
        {t('dev.notBuilt')}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: space.space24,
    backgroundColor: colors.surfacePage,
  },
});
