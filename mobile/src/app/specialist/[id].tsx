import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { Globe, Mic, ShieldCheck, Video } from 'lucide-react-native';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { useSpecialistProfile } from '@/api/catalogue';
import { useSpecialistReviews } from '@/api/reviews';
import i18n from '@/i18n';
import { capitalised, initials, money, practiceLine, whenLabel } from '@/state/format';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { RatingBadge, Stars } from '@/ui/Stars';
import { Text } from '@/ui/Text';

/**
 * Specialist profile (Specialist.dc.html), open to guests. The rating line appears with Ratings
 * and reviews (epic 8); booking arrives with epic 5.
 */
export default function SpecialistProfileScreen() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const profile = useSpecialistProfile(id, lang);
  const languages = useSessionLanguages(lang);
  const nameOf = (code: string) => languages.data?.find((l) => l.code === code)?.name ?? code;
  const p = profile.data;
  const reviews = useSpecialistReviews(id);

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      {p ? (
        <>
          <ScrollView contentContainerStyle={styles.content}>
            <View style={styles.head}>
              <View style={styles.avatar} aria-hidden>
                <Text style={styles.avatarText}>{initials(p.fullName)}</Text>
              </View>
              <View style={styles.who}>
                <Text variant="titleMd" accessibilityRole="header">
                  {p.fullName}
                </Text>
                {p.professionLabel || p.specialties?.length ? (
                  <Text variant="body" color={colors.inkMuted}>
                    {practiceLine(p.professions, p.specialties, p.professionLabel)}
                  </Text>
                ) : null}
                <RatingBadge rating={p.rating} />
                <View style={styles.verified}>
                  <ShieldCheck size={16} strokeWidth={1.8} color={colors.brand} aria-hidden />
                  <Text variant="meta" color={colors.brand} style={styles.strong}>
                    {t('specialistProfile.licenceVerified')}
                  </Text>
                </View>
              </View>
            </View>

            <Text variant="body" style={styles.bio}>
              {p.bio}
            </Text>

            <Text
              variant="meta"
              color={colors.inkMuted}
              style={styles.overline}
              accessibilityRole="header"
            >
              {t('specialistProfile.languages')}
            </Text>
            <View style={styles.languages}>
              {p.languages.map((l) => (
                <View key={l.code} style={styles.language}>
                  <Globe size={18} strokeWidth={1.8} color={colors.inkSoft} aria-hidden />
                  <View>
                    <Text variant="label">{nameOf(l.code)}</Text>
                    <Text variant="meta" color={colors.inkMuted}>
                      {capitalised(t(`specialistSignup.languages.${l.level}`))}
                    </Text>
                  </View>
                </View>
              ))}
            </View>

            <Text
              variant="meta"
              color={colors.inkMuted}
              style={styles.overline}
              accessibilityRole="header"
            >
              {t('specialistProfile.sessions')}
            </Text>
            <View style={styles.sessions}>
              {p.offerings.map((o, i) => (
                <View key={o.modality} style={[styles.session, i > 0 && styles.sessionDivider]}>
                  {o.modality === 'AUDIO' ? (
                    <Mic size={18} strokeWidth={1.8} color={colors.inkSoft} aria-hidden />
                  ) : (
                    <Video size={18} strokeWidth={1.8} color={colors.inkSoft} aria-hidden />
                  )}
                  <View style={styles.sessionText}>
                    <Text variant="label">
                      {t('specialistProfile.session', {
                        modality: t(`search.${o.modality}`),
                        minutes: o.durationMinutes,
                      })}
                    </Text>
                    <Text variant="meta" color={colors.inkMuted}>
                      {t(`specialistProfile.${o.modality}_meta`)}
                    </Text>
                  </View>
                  <Text variant="label" style={styles.strong}>
                    {t('specialistProfile.sessionPrice', { price: money(o.price, lang) })}
                  </Text>
                </View>
              ))}
            </View>

            <View style={styles.tags}>
              {p.focusAreas.map((area) => (
                <Text key={area} variant="meta" color={colors.brandDeep} style={styles.tag}>
                  {area}
                </Text>
              ))}
            </View>
            {reviews.data && reviews.data.length > 0 ? (
              <>
                <Text
                  variant="meta"
                  color={colors.inkMuted}
                  style={styles.overline}
                  accessibilityRole="header"
                >
                  {t('reviews.title')}
                </Text>
                {reviews.data.slice(0, 3).map((r) => (
                  <View key={r.id} style={styles.review}>
                    <Stars value={r.stars} />
                    <Text variant="body">{r.text}</Text>
                    {r.reply ? (
                      <Text variant="meta" color={colors.inkMuted}>
                        {r.reply}
                      </Text>
                    ) : null}
                  </View>
                ))}
              </>
            ) : null}
          </ScrollView>

          <View style={styles.footer}>
            <View style={styles.next}>
              {p.nextAvailable ? (
                <>
                  <Text variant="meta" color={colors.inkMuted}>
                    {t('specialistProfile.nextAvailable')}
                  </Text>
                  <Text variant="label" style={styles.strong}>
                    {capitalised(whenLabel(p.nextAvailable, t, lang))}
                  </Text>
                </>
              ) : null}
            </View>
            <Button
              label={t('specialistProfile.book')}
              onPress={() => router.push(`/book/${p.id}`)}
            />
          </View>
        </>
      ) : profile.isError ? (
        <View style={styles.state} accessibilityLiveRegion="polite">
          <Text variant="body" color={colors.inkMuted}>
            {t('common.checkConnection')}
          </Text>
          <Button label={t('common.tryAgain')} onPress={() => profile.refetch()} />
        </View>
      ) : (
        <View style={styles.state}>
          <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
        </View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  top: {
    paddingHorizontal: space.space20,
    paddingTop: space.space8,
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space16,
  },
  avatar: {
    width: 80,
    height: 80,
    borderRadius: radius.radiusPill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.accent,
  },
  avatarText: {
    fontFamily: fontFamily.display[700],
    fontSize: 28,
    color: colors.onAccent,
  },
  who: {
    flex: 1,
    gap: space.space4,
  },
  verified: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  strong: {
    fontFamily: fontFamily.body[700],
  },
  bio: {
    fontSize: 16,
    lineHeight: 24,
  },
  review: {
    gap: space.space6,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  overline: {
    fontFamily: fontFamily.body[700],
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  languages: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space10,
  },
  language: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space10,
    minWidth: 150,
    flexGrow: 1,
    padding: space.space14,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  sessions: {
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  session: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
  },
  sessionDivider: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  sessionText: {
    flex: 1,
    gap: space.space2,
  },
  tags: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space6,
  },
  tag: {
    paddingHorizontal: space.space12,
    paddingVertical: space.space6,
    borderRadius: radius.radiusPill,
    backgroundColor: colors.brandTint,
    overflow: 'hidden',
    fontFamily: fontFamily.body[600],
  },
  footer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space16,
    paddingHorizontal: space.space20,
    paddingVertical: space.space14,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  next: {
    flex: 1,
  },
  state: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space14,
    padding: space.space24,
  },
});
