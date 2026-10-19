import { colors, radius, space } from '@solace/tokens/native';
import { router } from 'expo-router';
import { Phone, X } from 'lucide-react-native';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Linking, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useMyBookings } from '@/api/bookings';
import { fetchSafetyPlan, type SafetyPlanEdit, saveSafetyPlan } from '@/api/care';
import { ApiProblem } from '@/api/core';
import { useEmergencyNumbers } from '@/content/crisis';
import { useSafetyPlan } from '@/state/safetyPlan';
import { Button } from '@/ui/Button';
import { Chip } from '@/ui/Chip';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';
import { TextField } from '@/ui/TextField';

type Person = SafetyPlanEdit['people'][number];

/**
 * My safety plan (SafetyPlan.dc.html), Phase 4: the seeker's warning signs, what calms them and
 * who they can reach, with professional help and urgent help always last. It is kept on the phone
 * so it opens with no connection (D57).
 */
export default function SafetyPlanScreen() {
  const { t } = useTranslation();
  const plan = useSafetyPlan((s) => s.plan);
  const setPlan = useSafetyPlan((s) => s.set);
  const numbers = useEmergencyNumbers((s) => s.numbers);
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    // Online, Core's copy replaces the one on the phone; offline, the phone's stays.
    fetchSafetyPlan()
      .then(setPlan)
      .catch(() => undefined);
  }, [setPlan]);

  if (editing) return <EditPlan onDone={() => setEditing(false)} />;

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
        {plan ? (
          <Button variant="quiet" label={t('safetyPlan.edit')} onPress={() => setEditing(true)} />
        ) : null}
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="titleLg" accessibilityRole="header">
          {t('safetyPlan.title')}
        </Text>
        {plan ? (
          <>
            <Text variant="body" color={colors.inkMuted}>
              {plan.madeWith
                ? t('safetyPlan.introWith', { name: plan.madeWith.name })
                : t('safetyPlan.intro')}
            </Text>
            <Step n={1} title={t('safetyPlan.warningSigns')}>
              <Text variant="body">{plan.warningSigns}</Text>
            </Step>
            <Step n={2} title={t('safetyPlan.calming')}>
              <Text variant="body">{plan.calming}</Text>
            </Step>
            <Step n={3} title={t('safetyPlan.people')}>
              {plan.people.map((p) => (
                <View key={`${p.relation}-${p.name}`} style={styles.person}>
                  <Text variant="body" style={styles.flex}>
                    {p.relation} · {p.name}
                  </Text>
                  {p.phone ? (
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={t('safetyPlan.call', { name: p.name })}
                      onPress={() => void Linking.openURL(`tel:${p.phone}`)}
                      style={styles.call}
                    >
                      <Phone size={20} strokeWidth={1.8} color={colors.brand} aria-hidden />
                    </Pressable>
                  ) : null}
                </View>
              ))}
            </Step>
          </>
        ) : (
          <View style={styles.empty}>
            <Text variant="titleSm">{t('safetyPlan.emptyTitle')}</Text>
            <Text variant="body" color={colors.inkMuted}>
              {t('safetyPlan.emptyBody')}
            </Text>
            <Button
              variant="secondary"
              label={t('safetyPlan.start')}
              onPress={() => setEditing(true)}
            />
          </View>
        )}
        <Step n={plan ? 4 : 1} title={t('safetyPlan.professional')}>
          <Text variant="body">
            {t('safetyPlan.professionalBody', { numbers: numbers.map((n) => n.number).join(', ') })}
          </Text>
        </Step>
        <Button label={t('safetyPlan.helpNow')} onPress={() => router.push('/crisis')} />
      </ScrollView>
    </SafeAreaView>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <View style={styles.card}>
      <View style={styles.stepHead}>
        <View style={styles.badge} aria-hidden>
          <Text variant="label" color={colors.onBrand}>
            {n}
          </Text>
        </View>
        <Text variant="label" style={styles.bold} accessibilityRole="header">
          {title}
        </Text>
      </View>
      {children}
    </View>
  );
}

function EditPlan({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation();
  const plan = useSafetyPlan((s) => s.plan);
  const setPlan = useSafetyPlan((s) => s.set);
  const past = useMyBookings('PAST');
  const [warningSigns, setWarningSigns] = useState(plan?.warningSigns ?? '');
  const [calming, setCalming] = useState(plan?.calming ?? '');
  const [people, setPeople] = useState<Person[]>(plan?.people ?? []);
  const [madeWith, setMadeWith] = useState<string | undefined>(plan?.madeWith?.id);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const specialists = [
    ...new Map((past.data ?? []).map((b) => [b.specialist.id, b.specialist.fullName])).entries(),
  ];
  const valid = people.every((p) => p.relation.trim() && p.name.trim());

  const change = (i: number, patch: Partial<Person>) =>
    setPeople((list) => list.map((p, j) => (j === i ? { ...p, ...patch } : p)));

  const save = async () => {
    setBusy(true);
    setProblem(null);
    try {
      const saved = await saveSafetyPlan({
        warningSigns: warningSigns.trim(),
        calming: calming.trim(),
        people: people.map((p) => ({
          relation: p.relation.trim(),
          name: p.name.trim(),
          ...(p.phone?.trim() ? { phone: p.phone.trim() } : {}),
        })),
        ...(madeWith ? { madeWithSpecialistId: madeWith } : {}),
      });
      setPlan(saved);
      onDone();
    } catch (e) {
      setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.top}>
        <BackButton />
      </View>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text variant="titleLg" accessibilityRole="header">
          {t('safetyPlan.title')}
        </Text>
        <TextField
          label={t('safetyPlan.warningSigns')}
          value={warningSigns}
          onChangeText={setWarningSigns}
          multiline
          maxLength={500}
        />
        <TextField
          label={t('safetyPlan.calming')}
          value={calming}
          onChangeText={setCalming}
          multiline
          maxLength={500}
        />
        <Text variant="label" color={colors.inkMuted} accessibilityRole="header">
          {t('safetyPlan.people')}
        </Text>
        {people.map((p, i) => (
          <View key={i} style={styles.card}>
            <View style={styles.personHead}>
              <View style={styles.flex}>
                <TextField
                  label={t('safetyPlan.relation')}
                  value={p.relation}
                  onChangeText={(v) => change(i, { relation: v })}
                  maxLength={40}
                />
              </View>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={t('safetyPlan.remove', { name: p.name || p.relation })}
                onPress={() => setPeople((list) => list.filter((_, j) => j !== i))}
                style={styles.call}
              >
                <X size={20} strokeWidth={1.8} color={colors.inkMuted} aria-hidden />
              </Pressable>
            </View>
            <TextField
              label={t('safetyPlan.name')}
              value={p.name}
              onChangeText={(v) => change(i, { name: v })}
              maxLength={60}
            />
            <TextField
              label={t('safetyPlan.phone')}
              value={p.phone ?? ''}
              onChangeText={(v) => change(i, { phone: v })}
              keyboardType="phone-pad"
              maxLength={20}
            />
          </View>
        ))}
        {people.length < 5 ? (
          <Button
            variant="secondary"
            label={t('safetyPlan.addPerson')}
            onPress={() => setPeople((list) => [...list, { relation: '', name: '' }])}
          />
        ) : null}
        {specialists.length > 0 ? (
          <>
            <Text variant="label" color={colors.inkMuted}>
              {t('safetyPlan.madeWith')}
            </Text>
            <View style={styles.chips}>
              <Chip
                role="radio"
                label={t('safetyPlan.nobody')}
                on={!madeWith}
                onToggle={() => setMadeWith(undefined)}
              />
              {specialists.map(([id, name]) => (
                <Chip
                  key={id}
                  role="radio"
                  label={name}
                  on={madeWith === id}
                  onToggle={() => setMadeWith(id)}
                />
              ))}
            </View>
          </>
        ) : null}
        {problem ? (
          <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
            {problem}
          </Text>
        ) : null}
        <Button
          label={t('safetyPlan.save')}
          disabled={busy || !valid}
          accessibilityState={{ disabled: busy || !valid, busy }}
          onPress={() => void save()}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surfacePage },
  top: {
    paddingHorizontal: space.space12,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  content: { padding: space.space20, gap: space.space14 },
  card: {
    gap: space.space8,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  empty: { gap: space.space8 },
  stepHead: { flexDirection: 'row', alignItems: 'center', gap: space.space8 },
  badge: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brand,
  },
  person: { flexDirection: 'row', alignItems: 'center', gap: space.space8 },
  personHead: { flexDirection: 'row', alignItems: 'flex-end', gap: space.space8 },
  call: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.space8 },
  flex: { flex: 1 },
  bold: { fontWeight: '600' },
});
