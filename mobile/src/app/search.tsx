import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Mic, Search as SearchIcon, ShieldCheck, Video } from 'lucide-react-native';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { type Modality, type SpecialistCard, useSpecialistSearch } from '@/api/catalogue';
import i18n from '@/i18n';
import { initials, money, whenLabel } from '@/state/format';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { BackButton } from '@/ui/ProgressSteps';
import { RatingBadge } from '@/ui/Stars';
import { Text } from '@/ui/Text';

const MODALITIES: Modality[] = ['AUDIO', 'VIDEO'];
/** Wait for typing to pause before searching, to spare weak connections. */
const TYPING_PAUSE_MS = 400;

/**
 * Find a specialist (Search.dc.html), open to guests. The session language comes first; the health
 * check card ships in Phase 2 (docs/screens.md).
 */
export default function Search() {
  const { t } = useTranslation();
  const appLanguage = i18n.language === 'fr' ? 'fr' : 'en';
  const languages = useSessionLanguages(appLanguage);
  const [language, setLanguage] = useState(appLanguage);
  const [modality, setModality] = useState<Modality | undefined>('AUDIO');
  const [typed, setTyped] = useState('');
  const [q, setQ] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => setQ(typed), TYPING_PAUSE_MS);
    return () => clearTimeout(timer);
  }, [typed]);

  const filters = useMemo(() => ({ language, modality, q }), [language, modality, q]);
  const search = useSpecialistSearch(filters, appLanguage);
  const cards = search.data?.pages.flatMap((page) => page.items) ?? [];
  const nameOf = (code: string) => languages.data?.find((l) => l.code === code)?.name ?? code;
  const other = languages.data?.find((l) => l.code !== language);

  const header = (
    <View style={styles.header}>
      <View style={styles.titleRow}>
        <BackButton />
        <Text variant="titleMd" accessibilityRole="header">
          {t('search.title')}
        </Text>
      </View>

      <View style={styles.field}>
        <Text variant="label" color={colors.inkMuted} style={styles.label}>
          {t('search.helpLabel')}
        </Text>
        <View style={styles.searchBox}>
          <SearchIcon size={20} strokeWidth={1.8} color={colors.inkMuted} aria-hidden />
          <TextInput
            value={typed}
            onChangeText={setTyped}
            placeholder={t('search.helpPlaceholder')}
            placeholderTextColor={colors.inkFaint}
            accessibilityLabel={t('search.helpLabel')}
            returnKeyType="search"
            onSubmitEditing={() => setQ(typed)}
            maxLength={120}
            style={styles.searchInput}
          />
        </View>
      </View>

      <View style={styles.field}>
        <Text
          variant="label"
          color={colors.inkMuted}
          style={styles.label}
          nativeID="language-label"
        >
          {t('search.languageLabel')}
        </Text>
        <View style={styles.chips}>
          <View
            accessibilityRole="radiogroup"
            accessibilityLabelledBy="language-label"
            style={styles.chipGroup}
          >
            {(languages.data ?? []).map((l) => (
              <Chip
                key={l.code}
                role="radio"
                label={l.name}
                on={l.code === language}
                onToggle={() => setLanguage(l.code)}
              />
            ))}
          </View>
          <View style={styles.divider} aria-hidden />
          <View accessibilityLabel={t('search.formatLabel')} style={styles.chipGroup}>
            {MODALITIES.map((m) => (
              <Chip
                key={m}
                label={t(`search.${m}`)}
                on={modality === m}
                onToggle={() => setModality(modality === m ? undefined : m)}
              />
            ))}
          </View>
        </View>
      </View>
    </View>
  );

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <FlatList
        data={cards}
        keyExtractor={(card) => card.id}
        ListHeaderComponent={
          <>
            {header}
            <View style={styles.results}>
              {cards.length > 0 ? (
                <Text variant="meta" color={colors.inkMuted}>
                  {t('search.resultsIn', { language: nameOf(language) })}
                  {modality ? t(`search.by_${modality}`) : ''}
                </Text>
              ) : null}
            </View>
          </>
        }
        renderItem={({ item }) => (
          <Card card={item} nameOf={nameOf} onPress={() => router.push(`/specialist/${item.id}`)} />
        )}
        ItemSeparatorComponent={() => <View style={styles.gap} />}
        ListEmptyComponent={
          search.isPending ? (
            <View style={styles.state} accessibilityLiveRegion="polite">
              <ActivityIndicator color={colors.brand} />
              <Text variant="body" color={colors.inkMuted}>
                {t('search.loading')}
              </Text>
            </View>
          ) : search.isError ? (
            <View style={styles.state} accessibilityLiveRegion="polite">
              <Text variant="body" color={colors.inkMuted}>
                {t('common.checkConnection')}
              </Text>
              <Button label={t('common.tryAgain')} onPress={() => search.refetch()} />
            </View>
          ) : (
            <View style={styles.state} accessibilityLiveRegion="polite">
              <Text variant="titleSm" accessibilityRole="header" style={styles.center}>
                {t('search.emptyTitle')}
              </Text>
              {other ? (
                <Button
                  variant="secondary"
                  label={t('search.searchIn', { language: other.name })}
                  onPress={() => setLanguage(other.code)}
                />
              ) : null}
            </View>
          )
        }
        ListFooterComponent={
          search.hasNextPage ? (
            <Button
              variant="quiet"
              label={t('search.more')}
              disabled={search.isFetchingNextPage}
              onPress={() => search.fetchNextPage()}
            />
          ) : undefined
        }
        contentContainerStyle={styles.list}
        keyboardShouldPersistTaps="handled"
      />
    </SafeAreaView>
  );
}

function Card({
  card,
  nameOf,
  onPress,
}: {
  card: SpecialistCard;
  nameOf: (code: string) => string;
  onPress: () => void;
}) {
  const { t } = useTranslation();
  const lang = i18n.language;
  const languages = card.languages
    .map((l) => `${nameOf(l.code)} · ${t(`search.level_${l.level}`)}`)
    .join('   ');
  const price = t('search.price', {
    price: money(card.offering.price, lang),
    minutes: card.offering.durationMinutes,
  });
  const next = card.nextAvailable
    ? t('search.next', { when: whenLabel(card.nextAvailable, t, lang) })
    : null;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={[
        card.fullName,
        card.professionLabel,
        t('search.verified'),
        languages,
        next,
        price,
      ]
        .filter(Boolean)
        .join('. ')}
      onPress={onPress}
      style={styles.card}
    >
      <View style={styles.cardHead}>
        <View style={styles.avatar} aria-hidden>
          <Text style={styles.avatarText}>{initials(card.fullName)}</Text>
        </View>
        <View style={styles.who}>
          <Text variant="label" style={styles.name}>
            {card.fullName}
          </Text>
          {card.professionLabel ? (
            <Text variant="meta" color={colors.inkMuted}>
              {card.professionLabel}
            </Text>
          ) : null}
          <RatingBadge rating={card.rating} />
        </View>
        <View style={styles.verified}>
          <ShieldCheck size={16} strokeWidth={1.8} color={colors.brand} aria-hidden />
          <Text variant="meta" color={colors.brand} style={styles.verifiedText}>
            {t('search.verified')}
          </Text>
        </View>
      </View>
      <View style={styles.tags}>
        {card.focusAreas.slice(0, 3).map((area) => (
          <Text key={area} variant="meta" style={styles.tag}>
            {area}
          </Text>
        ))}
      </View>
      <Text variant="meta" color={colors.inkSoft}>
        {languages}
      </Text>
      <View style={styles.cardFoot}>
        <Text variant="label" color={colors.brand} style={styles.next}>
          {next ?? ''}
        </Text>
        <View style={styles.price}>
          {card.offering.modality === 'AUDIO' ? (
            <Mic size={14} strokeWidth={1.8} color={colors.inkSoft} aria-hidden />
          ) : (
            <Video size={14} strokeWidth={1.8} color={colors.inkSoft} aria-hidden />
          )}
          <Text variant="label" style={styles.priceText}>
            {price}
          </Text>
        </View>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  list: {
    paddingHorizontal: space.space20,
    paddingBottom: space.space24,
  },
  header: {
    gap: space.space16,
    paddingTop: space.space12,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  field: {
    gap: space.space6,
  },
  label: {
    fontSize: 13,
  },
  searchBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space10,
    minHeight: size.sizeFieldMobile,
    paddingHorizontal: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.lineControl,
    backgroundColor: colors.surfaceCard,
  },
  searchInput: {
    flex: 1,
    minHeight: size.sizeFieldMobile - 2,
    fontFamily: fontFamily.body[500],
    fontSize: 16,
    color: colors.ink,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: space.space8,
  },
  chipGroup: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space8,
  },
  divider: {
    width: 1,
    height: 24,
    backgroundColor: colors.line,
  },
  results: {
    paddingTop: space.space18,
    paddingBottom: space.space10,
  },
  gap: {
    height: space.space12,
  },
  state: {
    alignItems: 'center',
    gap: space.space14,
    paddingVertical: space.space32,
  },
  center: {
    textAlign: 'center',
  },
  card: {
    gap: space.space10,
    padding: space.space16,
    borderRadius: radius.radius18,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
  },
  avatar: {
    width: 52,
    height: 52,
    borderRadius: radius.radiusPill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.accent,
  },
  avatarText: {
    fontFamily: fontFamily.display[700],
    fontSize: 18,
    color: colors.onAccent,
  },
  who: {
    flex: 1,
    gap: space.space2,
  },
  name: {
    fontSize: 16,
  },
  verified: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  verifiedText: {
    fontFamily: fontFamily.body[700],
  },
  tags: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.space6,
  },
  tag: {
    paddingHorizontal: space.space10,
    paddingVertical: space.space4,
    borderRadius: radius.radiusPill,
    backgroundColor: colors.surfaceSunken,
    overflow: 'hidden',
  },
  cardFoot: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: space.space10,
    borderTopWidth: 1,
    borderTopColor: colors.line,
  },
  next: {
    fontFamily: fontFamily.body[700],
  },
  price: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  priceText: {
    fontFamily: fontFamily.body[700],
  },
});
