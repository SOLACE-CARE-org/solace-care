import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { HeartHandshake, ShieldCheck } from 'lucide-react-native';
import type { ComponentType } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';

import { FlowScreen } from '@/ui/FlowScreen';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/** Choose account type (RoleChoice.dc.html). */
export default function RoleChoice() {
  const { t } = useTranslation();
  return (
    <FlowScreen
      top={<BackButton />}
      plainFooter
      footer={
        <View style={styles.signIn}>
          <Text variant="body" color={colors.inkMuted}>
            {t('roleChoice.haveAccount')}
          </Text>
          <Pressable
            accessibilityRole="link"
            accessibilityLabel={t('roleChoice.signIn')}
            onPress={() => router.replace('/sign-in')}
            style={styles.link}
          >
            <Text variant="body" color={colors.brand} style={styles.linkText}>
              {t('roleChoice.signIn')}
            </Text>
          </Pressable>
        </View>
      }
    >
      <View style={styles.intro}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('roleChoice.title')}
        </Text>
        <Text variant="body" color={colors.inkMuted} style={styles.lead}>
          {t('roleChoice.subtitle')}
        </Text>
      </View>
      <Choice
        icon={HeartHandshake}
        tint={colors.brandTint}
        ink={colors.brand}
        title={t('roleChoice.seekerTitle')}
        meta={t('roleChoice.seekerMeta')}
        emphasised
        onPress={() => router.push('/signup/details')}
      />
      <Choice
        icon={ShieldCheck}
        tint={colors.warningTint}
        ink={colors.warningInk}
        title={t('roleChoice.specialistTitle')}
        meta={t('roleChoice.specialistMeta')}
        onPress={() => router.push('/specialist-signup')}
      />
    </FlowScreen>
  );
}

function Choice({
  icon: Icon,
  tint,
  ink,
  title,
  meta,
  emphasised = false,
  onPress,
}: {
  icon: ComponentType<{ size: number; strokeWidth: number; color: string }>;
  tint: string;
  ink: string;
  title: string;
  meta: string;
  emphasised?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${title}. ${meta}`}
      onPress={onPress}
      style={[styles.card, emphasised && styles.cardEmphasised]}
    >
      <View style={[styles.icon, { backgroundColor: tint }]} aria-hidden>
        <Icon size={28} strokeWidth={1.8} color={ink} />
      </View>
      <View style={styles.cardText}>
        <Text variant="heading" style={styles.cardTitle}>
          {title}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {meta}
        </Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  intro: {
    gap: space.space8,
  },
  lead: {
    fontSize: 16,
    lineHeight: 24,
  },
  card: {
    gap: space.space14,
    padding: space.space20,
    borderRadius: 22,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  cardEmphasised: {
    borderWidth: 2,
    borderColor: colors.brand,
  },
  icon: {
    width: 52,
    height: 52,
    borderRadius: radius.radius16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cardText: {
    gap: space.space4,
  },
  cardTitle: {
    fontSize: 21,
  },
  signIn: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    alignItems: 'center',
    gap: space.space4,
  },
  link: {
    minHeight: 44,
    justifyContent: 'center',
  },
  linkText: {
    fontFamily: fontFamily.body[700],
    textDecorationLine: 'underline',
  },
});
