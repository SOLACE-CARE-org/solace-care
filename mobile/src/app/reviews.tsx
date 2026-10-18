import { colors, fontFamily, radius, space } from '@solace/tokens/native';
import { Star } from 'lucide-react-native';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { ApiProblem } from '@/api/core';
import { type PublicReview, useMyReviews, useReplyToReview } from '@/api/reviews';
import i18n from '@/i18n';
import { capitalised } from '@/state/format';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Stars } from '@/ui/Stars';
import { Text } from '@/ui/Text';

/**
 * Reviews in the specialist app (Reviews.dc.html; D37): their average, published reviews without
 * the seeker's name, and one public reply each.
 */
export default function Reviews() {
  const { t } = useTranslation();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const reviews = useMyReviews();
  const languages = useSessionLanguages(lang);
  const nameOf = (code: string) => languages.data?.find((l) => l.code === code)?.name ?? code;
  const r = reviews.data;

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.titleRow}>
          <BackButton label={t('reviews.back')} />
          <Text variant="titleMd" accessibilityRole="header">
            {t('reviews.title')}
          </Text>
        </View>
        {!r ? (
          reviews.isError ? (
            <Button label={t('common.tryAgain')} onPress={() => reviews.refetch()} />
          ) : (
            <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
          )
        ) : (
          <>
            <View style={styles.summary}>
              {r.rating ? (
                <>
                  <View style={styles.average}>
                    <Star
                      size={28}
                      strokeWidth={1.8}
                      color={colors.ratingStar}
                      fill={colors.ratingStar}
                      aria-hidden
                    />
                    <Text style={styles.averageText}>
                      {new Intl.NumberFormat(lang, {
                        minimumFractionDigits: 1,
                        maximumFractionDigits: 1,
                      }).format(r.rating.average)}
                    </Text>
                  </View>
                  <Text variant="meta" color={colors.inkMuted}>
                    {t('reviews.from', { count: r.rating.count })}
                  </Text>
                </>
              ) : (
                <Text variant="body" color={colors.inkMuted}>
                  {t('reviews.none')}
                </Text>
              )}
              <Text variant="meta" color={colors.inkMuted}>
                {t('reviews.note')}
              </Text>
            </View>
            {r.items.length === 0 ? (
              <Text variant="body" color={colors.inkMuted}>
                {t('reviews.empty')}
              </Text>
            ) : (
              r.items.map((item) => (
                <ReviewCard
                  key={item.id}
                  review={item}
                  lang={lang}
                  language={nameOf(item.sessionLanguage)}
                />
              ))
            )}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function ReviewCard({
  review,
  lang,
  language,
}: {
  review: PublicReview;
  lang: string;
  language: string;
}) {
  const { t } = useTranslation();
  const reply = useReplyToReview();
  const [writing, setWriting] = useState(false);
  const [text, setText] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const date = capitalised(
    new Intl.DateTimeFormat(lang, { weekday: 'short', day: 'numeric', month: 'short' }).format(
      new Date(review.at),
    ),
  );

  const send = () => {
    setProblem(null);
    reply.mutate(
      { reviewId: review.id, text: text.trim() },
      {
        onSuccess: () => setWriting(false),
        onError: (e) =>
          setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection')),
      },
    );
  };

  return (
    <View style={styles.card}>
      <Stars value={review.stars} />
      <Text variant="meta" color={colors.inkMuted}>
        {t('reviews.meta', { date, language })}
      </Text>
      <Text variant="body">{review.text}</Text>
      {review.reply ? (
        <View style={styles.reply}>
          <Text variant="meta" color={colors.inkMuted} style={styles.bold}>
            {t('reviews.yourReply')}
          </Text>
          <Text variant="body">{review.reply}</Text>
        </View>
      ) : writing ? (
        <View style={styles.replyForm}>
          <TextInput
            value={text}
            onChangeText={setText}
            multiline
            maxLength={600}
            accessibilityLabel={t('reviews.replyLabel')}
            style={styles.area}
          />
          {problem ? (
            <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
              {problem}
            </Text>
          ) : null}
          <Button
            label={t('reviews.send')}
            disabled={!text.trim() || reply.isPending}
            accessibilityState={{
              disabled: !text.trim() || reply.isPending,
              busy: reply.isPending,
            }}
            onPress={send}
          />
          <Button variant="quiet" label={t('reviews.cancel')} onPress={() => setWriting(false)} />
        </View>
      ) : (
        <Button
          variant="secondary"
          label={t('reviews.replyPublicly')}
          onPress={() => setWriting(true)}
        />
      )}
    </View>
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
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  summary: {
    gap: space.space6,
  },
  average: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space8,
  },
  averageText: {
    fontFamily: fontFamily.display[700],
    fontSize: 32,
    color: colors.ink,
  },
  card: {
    gap: space.space8,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  reply: {
    gap: space.space4,
    padding: space.space12,
    borderRadius: radius.radius12,
    backgroundColor: colors.surfaceSunken,
  },
  replyForm: {
    gap: space.space8,
  },
  area: {
    minHeight: 90,
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
  bold: {
    fontFamily: fontFamily.body[700],
  },
});
