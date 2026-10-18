import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useBooking, useGuestToken } from '@/api/bookings';
import { ApiProblem } from '@/api/core';
import { type RatingTag, useRateSession } from '@/api/reviews';
import { Avatar } from '@/ui/Avatar';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { StarInput } from '@/ui/Stars';
import { Text } from '@/ui/Text';

const TAGS: RatingTag[] = ['LISTENED', 'PRACTICAL_TOOLS', 'CALL_QUALITY', 'ON_TIME'];

/**
 * Rate your session (RateSession.dc.html; D37): stars, what stood out, an optional review shown
 * without the seeker's name once Solace has checked it, and a private note for the Solace team.
 */
export default function RateSession() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const token = useGuestToken(id);
  const access = { bookingId: id, guestToken: token.data ?? undefined };
  const booking = useBooking(access, token.isSuccess);
  const rate = useRateSession(access);
  const [stars, setStars] = useState(0);
  const [tags, setTags] = useState<RatingTag[]>([]);
  const [text, setText] = useState('');
  const [privateOpen, setPrivateOpen] = useState(false);
  const [privateNote, setPrivateNote] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const b = booking.data;

  const leave = () =>
    router.canGoBack()
      ? router.back()
      : router.replace({ pathname: '/session/[id]', params: { id } });

  if (!b) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
      </SafeAreaView>
    );
  }

  if (rate.isSuccess) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <Text variant="body" style={styles.centerText} accessibilityLiveRegion="polite">
          {text.trim() ? t('rate.thanks') : t('rate.thanksStars')}
        </Text>
        <Button
          label={t('rate.followUp')}
          onPress={() =>
            router.replace({ pathname: '/book/[id]', params: { id: b.specialist.id } })
          }
        />
        <Button
          variant="quiet"
          label={t('call.back')}
          onPress={() => router.replace('/(seeker)/sessions')}
        />
      </SafeAreaView>
    );
  }

  const submit = () => {
    setProblem(null);
    rate.mutate(
      {
        stars,
        tags,
        text: text.trim() || undefined,
        privateNote: privateNote.trim() || undefined,
      },
      {
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('rate.skip')}
          onPress={leave}
          style={styles.skip}
        >
          <Text variant="label" color={colors.brand} style={styles.bold}>
            {t('rate.skip')}
          </Text>
        </Pressable>
        <View style={styles.head}>
          <Avatar name={b.specialist.fullName} size={64} />
          <Text variant="titleMd" accessibilityRole="header" style={styles.centerText}>
            {t('rate.title', { name: b.specialist.fullName })}
          </Text>
        </View>
        <StarInput value={stars} onChange={setStars} />

        <Text variant="label" style={styles.bold}>
          {t('rate.standOut')}
        </Text>
        <View style={styles.tags} accessibilityLabel={t('rate.standOut')}>
          {TAGS.map((tag) => (
            <Chip
              key={tag}
              label={t(`rate.${tag}`)}
              on={tags.includes(tag)}
              onToggle={() =>
                setTags((all) => (all.includes(tag) ? all.filter((x) => x !== tag) : [...all, tag]))
              }
            />
          ))}
        </View>

        <Text variant="label" style={styles.bold}>
          {t('rate.write')}{' '}
          <Text variant="label" color={colors.inkMuted}>
            {t('rate.optional')}
          </Text>
        </Text>
        <TextInput
          value={text}
          onChangeText={setText}
          multiline
          maxLength={1000}
          placeholder={t('rate.placeholder')}
          placeholderTextColor={colors.inkFaint}
          accessibilityLabel={t('rate.write')}
          accessibilityHint={t('rate.placeholder')}
          style={styles.area}
        />

        {privateOpen ? (
          <TextInput
            value={privateNote}
            onChangeText={setPrivateNote}
            multiline
            maxLength={1000}
            accessibilityLabel={t('rate.privateLabel')}
            placeholder={t('rate.privateLabel')}
            placeholderTextColor={colors.inkFaint}
            style={styles.area}
          />
        ) : (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('rate.privateLink')}
            onPress={() => setPrivateOpen(true)}
            style={styles.link}
          >
            <Text variant="body" color={colors.brand} style={styles.underline}>
              {t('rate.privateLink')}
            </Text>
          </Pressable>
        )}
        {problem ? (
          <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
            {problem}
          </Text>
        ) : null}
      </ScrollView>
      <View style={styles.footer}>
        <Button
          label={t('rate.submit')}
          disabled={stars === 0 || rate.isPending}
          accessibilityState={{ disabled: stars === 0 || rate.isPending, busy: rate.isPending }}
          onPress={submit}
        />
        <Button
          variant="quiet"
          label={t('rate.followUp')}
          onPress={() => router.push({ pathname: '/book/[id]', params: { id: b.specialist.id } })}
        />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  center: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space14,
    padding: space.space24,
  },
  centerText: {
    textAlign: 'center',
  },
  content: {
    padding: space.space20,
    gap: space.space16,
  },
  skip: {
    alignSelf: 'flex-end',
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
  head: {
    alignItems: 'center',
    gap: space.space12,
  },
  tags: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space8,
  },
  area: {
    minHeight: 110,
    padding: space.space12,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
    fontFamily: fontFamily.body[400],
    fontSize: 16,
    color: colors.ink,
    textAlignVertical: 'top',
  },
  link: {
    minHeight: size.sizeTap,
    justifyContent: 'center',
  },
  underline: {
    textDecorationLine: 'underline',
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
  footer: {
    gap: space.space8,
    paddingHorizontal: space.space20,
    paddingTop: space.space16,
    paddingBottom: space.space14,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
});
