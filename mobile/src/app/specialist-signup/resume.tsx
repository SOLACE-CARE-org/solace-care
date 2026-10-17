import { colors, space } from '@solace/tokens/native';
import { Redirect } from 'expo-router';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { resumeRoute, useApplication } from '@/api/specialist';
import { useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { Text } from '@/ui/Text';

/**
 * Where an applicant lands after sign-in or at start-up: the first unfinished step ("Your progress
 * is saved after each step"), the review screen once submitted, or the specialist app once approved.
 */
export default function ResumeApplication() {
  const { t } = useTranslation();
  const application = useApplication();
  const signedInAs = useSession((s) => s.signedInAs);
  const status = application.data?.status;
  const approved = status === 'APPROVED' || status === 'SUSPENDED';

  useEffect(() => {
    if (approved) signedInAs('specialist');
  }, [approved, signedInAs]);

  if (application.data) {
    return <Redirect href={approved ? '/(specialist)/today' : resumeRoute(application.data)} />;
  }

  return (
    <View style={styles.screen}>
      {application.isError ? (
        <>
          <Text variant="body" color={colors.inkMuted} accessibilityLiveRegion="polite">
            {t('common.checkConnection')}
          </Text>
          <Button label={t('common.tryAgain')} onPress={() => application.refetch()} />
        </>
      ) : (
        <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space16,
    padding: space.space24,
    backgroundColor: colors.surfacePage,
  },
});
