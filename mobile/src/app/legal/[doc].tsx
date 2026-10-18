import { colors, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useMe } from '@/api/account';
import { LEGAL_SLUGS, useAcceptLegal, useContent } from '@/api/content';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/**
 * Terms, privacy notice, specialist terms and code of conduct, as published in the Content module
 * (D44). With `accept=1`, a seeker who accepted an older version reads the new one and accepts it
 * before going on.
 */
export default function LegalText() {
  const { t, i18n } = useTranslation();
  const { doc, accept } = useLocalSearchParams<{
    doc: string;
    accept?: string;
  }>();
  const slug = LEGAL_SLUGS[doc] ?? doc;
  const content = useContent(slug, i18n.language);
  const me = useMe(accept === '1');
  const acceptLegal = useAcceptLegal();
  const pending = me.data?.legalToAccept ?? [];

  const onAccept = () => {
    acceptLegal.mutate(pending, {
      onSuccess: () => (router.canGoBack() ? router.back() : router.replace('/')),
    });
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        {accept === '1' ? null : <BackButton />}
        {content.isPending ? (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        ) : content.isError ? (
          <View style={styles.state}>
            <Text variant="body">{t('common.checkConnection')}</Text>
            <Button label={t('common.tryAgain')} onPress={() => content.refetch()} />
          </View>
        ) : content.data === null ? (
          <Text variant="body" color={colors.inkMuted}>
            {t('legal.notPublished')}
          </Text>
        ) : (
          <>
            {accept === '1' ? (
              <Text variant="meta" color={colors.inkMuted}>
                {t('legal.updated')}
              </Text>
            ) : null}
            <Text variant="titleLg" accessibilityRole="header">
              {content.data.title}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('legal.version', { version: content.data.version })}
            </Text>
            <Text variant="body" style={styles.body}>
              {content.data.body}
            </Text>
          </>
        )}
      </ScrollView>
      {accept === '1' && content.data ? (
        <View style={styles.footer}>
          {acceptLegal.isError ? (
            <Text variant="meta" color={colors.danger}>
              {t('common.checkConnection')}
            </Text>
          ) : null}
          <Button
            label={t('legal.accept')}
            onPress={onAccept}
            disabled={acceptLegal.isPending || pending.length === 0}
          />
        </View>
      ) : null}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space12,
  },
  state: {
    gap: space.space12,
  },
  body: {
    marginTop: space.space8,
  },
  footer: {
    padding: space.space20,
    gap: space.space8,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
});
