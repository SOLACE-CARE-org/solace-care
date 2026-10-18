import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { type Href, router } from 'expo-router';
import { ChevronRight } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useMe } from '@/api/account';
import { useInbox } from '@/api/messaging';
import { displayContact } from '@/auth/contact';
import { signOut } from '@/notifications/push';
import { useSession } from '@/state/session';
import { Avatar } from '@/ui/Avatar';
import { CrisisButton } from '@/ui/CrisisButton';
import { Text } from '@/ui/Text';

/**
 * You (Profile.dc.html), Phase 1: who is signed in, Notifications with what is new, Settings, and
 * signing out. Tracking modules join in Phase 2; Privacy & data with epic 11.
 */
export default function You() {
  const { t } = useTranslation();
  const me = useMe();
  const inbox = useInbox();
  const language = useSession((s) => s.language) ?? 'en';
  const unread = (inbox.data ?? []).filter((i) => !i.read).length;

  return (
    <SafeAreaView style={styles.screen} edges={['top']}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.head}>
          <Avatar name={me.data?.displayName ?? ''} size={56} />
          <View style={styles.flex}>
            <Text variant="titleMd" accessibilityRole="header">
              {me.data?.displayName ?? ''}
            </Text>
            {me.data?.phone ? (
              <Text variant="meta" color={colors.inkMuted}>
                {displayContact(me.data.phone)}
              </Text>
            ) : null}
          </View>
        </View>

        <View style={styles.card}>
          <Row
            label={t('you.notifications')}
            value={unread > 0 ? t('you.unread', { count: unread }) : undefined}
            to="/notifications"
          />
          <Row
            label={t('you.settings')}
            value={language === 'fr' ? t('common.french') : t('common.english')}
            to="/settings"
            divider
          />
          <Row label={t('you.privacy')} to="/privacy" divider />
        </View>

        <CrisisButton label={t('crisis.button')} onPress={() => router.push('/crisis')} />

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('you.signOut')}
          onPress={async () => {
            await signOut();
            useSession.getState().signedOut();
            router.replace('/welcome');
          }}
          style={styles.signOut}
        >
          <Text variant="label" color={colors.danger}>
            {t('you.signOut')}
          </Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

function Row({
  label,
  value,
  to,
  divider = false,
}: {
  label: string;
  value?: string;
  to: Href;
  divider?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={value ? `${label}, ${value}` : label}
      onPress={() => router.push(to)}
      style={[styles.row, divider && styles.divider]}
    >
      <Text variant="label" style={[styles.flex, styles.bold]}>
        {label}
      </Text>
      {value ? (
        <Text variant="meta" color={colors.inkMuted}>
          {value}
        </Text>
      ) : null}
      <ChevronRight size={20} strokeWidth={2} color={colors.inkMuted} aria-hidden />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space14,
  },
  card: {
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
    paddingHorizontal: space.space14,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
    minHeight: 56,
  },
  divider: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  signOut: {
    minHeight: size.sizeTap,
    alignItems: 'center',
    justifyContent: 'center',
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[600],
  },
});
