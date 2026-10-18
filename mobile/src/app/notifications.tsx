import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { type InboxItem, useInbox, useMarkAllRead } from '@/api/messaging';
import i18n from '@/i18n';
import { capitalised, timeOf } from '@/state/format';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

function isToday(iso: string): boolean {
  return new Date(iso).toDateString() === new Date().toDateString();
}

/**
 * Notifications (Notifications.dc.html): the messages pushed to this person, worded as they were
 * pushed, today first. A message about a booking opens it.
 */
export default function Notifications() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const inbox = useInbox();
  const markAll = useMarkAllRead();
  const items = inbox.data ?? [];
  const today = items.filter((i) => isToday(i.at));
  const earlier = items.filter((i) => !isToday(i.at));

  const row = (item: InboxItem) => {
    const when = isToday(item.at)
      ? timeOf(new Date(item.at), lang)
      : capitalised(
          new Intl.DateTimeFormat(lang, {
            weekday: 'short',
            day: 'numeric',
            month: 'short',
          }).format(new Date(item.at)),
        );
    const label = item.read
      ? `${item.text}, ${when}`
      : `${t('inbox.unread')}, ${item.text}, ${when}`;
    return (
      <Pressable
        key={item.id}
        accessibilityRole={item.bookingId ? 'button' : 'text'}
        accessibilityLabel={label}
        disabled={!item.bookingId}
        onPress={() =>
          item.bookingId &&
          router.push({ pathname: '/session/[id]', params: { id: item.bookingId } })
        }
        style={styles.item}
      >
        {!item.read ? <View style={styles.dot} aria-hidden /> : null}
        <View style={styles.flex}>
          <Text variant="label" style={item.read ? undefined : styles.bold}>
            {item.text}
          </Text>
          <Text variant="meta" color={colors.inkMuted}>
            {when}
          </Text>
        </View>
      </Pressable>
    );
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.titleRow}>
          <BackButton />
          <Text variant="titleMd" accessibilityRole="header" style={styles.flex}>
            {t('inbox.title')}
          </Text>
          {items.some((i) => !i.read) ? (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('inbox.markAll')}
              onPress={() => markAll.mutate()}
              style={styles.markAll}
            >
              <Text variant="meta" color={colors.brand} style={styles.bold}>
                {t('inbox.markAll')}
              </Text>
            </Pressable>
          ) : null}
        </View>
        {inbox.isPending ? (
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        ) : inbox.isError ? (
          <Button label={t('common.tryAgain')} onPress={() => inbox.refetch()} />
        ) : items.length === 0 ? (
          <Text variant="body" color={colors.inkMuted}>
            {t('inbox.empty')}
          </Text>
        ) : (
          <>
            {today.length > 0 ? (
              <Text
                variant="meta"
                color={colors.inkMuted}
                style={styles.section}
                accessibilityRole="header"
              >
                {t('inbox.today').toUpperCase()}
              </Text>
            ) : null}
            {today.map(row)}
            {earlier.length > 0 ? (
              <Text
                variant="meta"
                color={colors.inkMuted}
                style={styles.section}
                accessibilityRole="header"
              >
                {t('inbox.earlier').toUpperCase()}
              </Text>
            ) : null}
            {earlier.map(row)}
          </>
        )}
        <Button
          variant="secondary"
          label={t('inbox.settings')}
          onPress={() => router.push('/settings')}
        />
      </ScrollView>
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
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  markAll: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
  section: {
    marginTop: space.space8,
    fontFamily: fontFamily.body[700],
    letterSpacing: 0.5,
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: colors.brand,
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
});
